package com.alphactx;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Bisected;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import net.milkbowl.vault.economy.Economy;
import org.bstats.bukkit.Metrics;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.POIMarker;
import com.flowpowered.math.vector.Vector3d;

import java.io.File;
import java.util.*;
import java.sql.*;

public class MinecraftHouses extends JavaPlugin implements Listener {
    private Economy economy;
    private FileConfiguration housesConfig;
    private Connection sqlConnection;
    private boolean sqlDebug;
    private int autoSaveTask = -1;

    public final Map<UUID, Integer> awaitingDoorAdd = new HashMap<>();
    public final Map<UUID, Integer> awaitingDoorRemove = new HashMap<>();

    public enum MarketFilter { ALL, AVAILABLE, OWNED }
    private final Map<UUID, MarketFilter> marketFilters = new HashMap<>();
    private final Map<UUID, Integer> marketPages = new HashMap<>();

    private final Map<UUID, Integer> confirmBuy = new HashMap<>();
    private final Map<UUID, Integer> confirmSell = new HashMap<>();

    private final Map<UUID, Integer> pendingTeleports = new HashMap<>();
    private final Map<UUID, org.bukkit.Location> teleportLocations = new HashMap<>();
    public final Map<UUID, Integer> wandSelections = new HashMap<>();
    private int rentTask = -1;
    private int cleanupTask = -1;

    private boolean dynmapEnabled;
    private boolean bluemapEnabled;
    private Object dynmapMarkerAPI;
    private Object dynmapMarkerSet;
    private final java.util.List<MarkerSet> bluemapSets = new java.util.ArrayList<>();
    private java.util.function.Consumer<BlueMapAPI> bluemapEnableListener;
    private java.util.function.Consumer<BlueMapAPI> bluemapDisableListener;

    private boolean useMysql() {
        return getConfig().getBoolean("database.use-mysql", false);
    }

    @Override
    public void onEnable() {
        if (!setupEconomy()) {
            getLogger().severe("Vault not found. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        loadHouses();
        saveDefaultConfig();
        sqlDebug = getConfig().getBoolean("database.debug", false);
        connectDatabase();
        loadHousesFromDatabase();
        startAutoSave();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("houses")).setExecutor(new HousesCommand(this));
        startRentTask();
        startCleanupTask();
        new Metrics(this, 26286);
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new HousesPlaceholder(this).register();
        }
        setupMapIntegrations();
                getLogger().info("  ╭───────────────────────╮");
                getLogger().info("  │      AlphaCTX's       │");
                getLogger().info("  │        Houses         │");
		getLogger().info("  │         Plugin        │");
		getLogger().info("  ╰───────────────────────╯");
		getLogger().info("        Houses Enabled!");
    }

    @Override
    public void onDisable() {
        saveHousesSync();
        stopAutoSave();
        stopRentTask();
        stopCleanupTask();
        closeDatabase();
        cleanupMapIntegrations();
                getLogger().info("Houses Disabled!");
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        economy = rsp.getProvider();
        return economy != null;
    }

    public Economy getEconomy() {
        return economy;
    }

    private void loadHouses() {
        housesConfig = new YamlConfiguration();
    }

    public void reloadPlugin() {
        reloadConfig();
        loadHouses();
        sqlDebug = getConfig().getBoolean("database.debug", false);
        stopAutoSave();
        closeDatabase();
        connectDatabase();
        loadHousesFromDatabase();
        startAutoSave();
        cleanupMapIntegrations();
        setupMapIntegrations();
    }
    /**
     * Save houses asynchronously to avoid blocking the server thread.
     */
    public void saveHouses() {
        Bukkit.getScheduler().runTaskAsynchronously(this, this::saveHousesToDatabase);
    }

    /**
     * Save houses synchronously. Used during plugin shutdown where we
     * need to ensure everything is written before closing the database.
     */
    public void saveHousesSync() {
        saveHousesToDatabase();
    }

    public FileConfiguration getHousesConfig() {
        return housesConfig;
    }

    private void connectDatabase() {
        closeDatabase();
        try {
            if (useMysql()) {
                sqlConnection = connectMysql();
            } else {
                sqlConnection = connectSqlite();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private Connection connectMysql() throws SQLException {
        String host = getConfig().getString("database.host");
        int port = getConfig().getInt("database.port", 3306);
        String user = getConfig().getString("database.user");
        String pass = getConfig().getString("database.pass");
        String db = getConfig().getString("database.database");
        String url = "jdbc:mysql://" + host + ":" + port + "/" + db + "?useSSL=false";
        debug("Connecting to " + url);
        Connection conn = DriverManager.getConnection(url, user, pass);
        try (PreparedStatement ps = conn.prepareStatement(
                "CREATE TABLE IF NOT EXISTS houses (id INTEGER PRIMARY KEY, rent TINYINT(1), price DOUBLE, owner VARCHAR(36), next_rent BIGINT, world VARCHAR(64), x INT, y INT, z INT, doors TEXT, trusted TEXT)")) {
            ps.executeUpdate();
        }
        debug("Connected to MySQL");
        return conn;
    }

    private Connection connectSqlite() throws SQLException {
        File file = new File(getDataFolder(), getConfig().getString("database.sqlite-file", "houses.db"));
        file.getParentFile().mkdirs();
        String url = "jdbc:sqlite:" + file.getAbsolutePath();
        debug("Connecting to " + url);
        Connection conn = DriverManager.getConnection(url);
        try (PreparedStatement ps = conn.prepareStatement(
                "CREATE TABLE IF NOT EXISTS houses (id INTEGER PRIMARY KEY, rent TINYINT(1), price DOUBLE, owner VARCHAR(36), next_rent BIGINT, world VARCHAR(64), x INT, y INT, z INT, doors TEXT, trusted TEXT)")) {
            ps.executeUpdate();
        }
        debug("Connected to SQLite");
        return conn;
    }

    private void closeDatabase() {
        if (sqlConnection != null) {
            try { sqlConnection.close(); } catch (SQLException ignored) {}
            sqlConnection = null;
            debug("Database connection closed");
        }
    }

    private void startAutoSave() {
        int interval = getConfig().getInt("database.save-interval", 10);
        if (interval <= 0) return;
        autoSaveTask = getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            saveHousesToDatabase();
            debug("Auto-saved houses to database");
        }, interval * 20L, interval * 20L).getTaskId();
    }

    private void stopAutoSave() {
        if (autoSaveTask != -1) {
            getServer().getScheduler().cancelTask(autoSaveTask);
            autoSaveTask = -1;
        }
    }

    private void startRentTask() {
        int interval = getConfig().getInt("rent.check-interval", 600);
        if (interval <= 0) return;
        rentTask = getServer().getScheduler()
    .runTaskTimer(this, (Runnable) this::checkRentPayments,
                  interval * 20L, interval * 20L)
    .getTaskId();
    }

    private void startCleanupTask() {
        int interval = getConfig().getInt("cleanup.check-interval", 86400);
        if (interval <= 0) return;
        cleanupTask = getServer().getScheduler()
    .runTaskTimer(this, (Runnable) this::checkInactiveOwners,
                  interval * 20L, interval * 20L)
    .getTaskId();
    }

    private void stopRentTask() {
        if (rentTask != -1) {
            getServer().getScheduler().cancelTask(rentTask);
            rentTask = -1;
        }
    }

    private void stopCleanupTask() {
        if (cleanupTask != -1) {
            getServer().getScheduler().cancelTask(cleanupTask);
            cleanupTask = -1;
        }
    }

    private void setupMapIntegrations() {
        dynmapEnabled = getConfig().getBoolean("integrations.dynmap", false);
        bluemapEnabled = getConfig().getBoolean("integrations.bluemap", false);
        if (dynmapEnabled) initDynmap();
        if (bluemapEnabled) initBlueMap();
    }

    private void cleanupMapIntegrations() {
        if (dynmapMarkerSet != null) {
            removeAllDynmapMarkers();
            dynmapMarkerSet = null;
            dynmapMarkerAPI = null;
        }
        if (!bluemapSets.isEmpty()) {
            removeAllBlueMapMarkers();
            bluemapSets.clear();
        }
        if (bluemapEnableListener != null) {
            BlueMapAPI.unregisterListener(bluemapEnableListener);
            BlueMapAPI.unregisterListener(bluemapDisableListener);
            bluemapEnableListener = null;
            bluemapDisableListener = null;
        }
    }

    private void initDynmap() {
        try {
            org.bukkit.plugin.Plugin dyn = getServer().getPluginManager().getPlugin("dynmap");
            if (dyn == null) return;
            Class<?> apiClass = Class.forName("org.dynmap.DynmapCommonAPI");
            if (!apiClass.isInstance(dyn)) return;
            dynmapMarkerAPI = apiClass.getMethod("getMarkerAPI").invoke(dyn);
            if (dynmapMarkerAPI == null) return;
            Class<?> markerAPI = Class.forName("org.dynmap.markers.MarkerAPI");
            Object set = markerAPI.getMethod("getMarkerSet", String.class).invoke(dynmapMarkerAPI, "houses");
            if (set == null) {
                set = markerAPI.getMethod("createMarkerSet", String.class, String.class, java.util.Set.class, boolean.class)
                        .invoke(dynmapMarkerAPI, "houses", "Houses", null, true);
            }
            dynmapMarkerSet = set;
        } catch (Exception ex) {
            getLogger().warning("Dynmap hook failed: " + ex.getMessage());
        }
        updateAllDynmapMarkers();
    }

    private void initBlueMap() {
        bluemapEnableListener = api -> {
            setupBlueMap(api);
            updateAllBlueMapMarkers();
        };
        bluemapDisableListener = api -> {
            removeAllBlueMapMarkers();
            bluemapSets.clear();
        };
        BlueMapAPI.onEnable(bluemapEnableListener);
        BlueMapAPI.onDisable(bluemapDisableListener);
        BlueMapAPI.getInstance().ifPresent(bluemapEnableListener);
    }

    private void setupBlueMap(BlueMapAPI api) {
        bluemapSets.clear();
        for (BlueMapMap map : api.getMaps()) {
            MarkerSet set = map.getMarkerSets().get("houses");
            if (set == null) {
                set = MarkerSet.builder()
                        .label("Houses")
                        .toggleable(true)
                        .build();
                map.getMarkerSets().put("houses", set);
            }
            bluemapSets.add(set);
        }
    }

    private void updateMapMarkers(int id) {
        updateDynmapMarker(id);
        updateBlueMapMarker(id);
    }

    private void removeMapMarker(int id) {
        removeDynmapMarker(id);
        removeBlueMapMarker(id);
    }

    private void updateAllMapMarkers() {
        if (housesConfig.isConfigurationSection("houses")) {
            for (String idStr : housesConfig.getConfigurationSection("houses").getKeys(false)) {
                try { updateMapMarkers(Integer.parseInt(idStr)); } catch (Exception ignored) {}
            }
        }
    }

    private void removeAllDynmapMarkers() {
        if (dynmapMarkerSet == null) return;
        try {
            java.util.Set<?> markers = (java.util.Set<?>) dynmapMarkerSet.getClass().getMethod("getMarkers").invoke(dynmapMarkerSet);
            for (Object m : markers.toArray()) {
                m.getClass().getMethod("deleteMarker").invoke(m);
            }
        } catch (Exception ignored) {}
    }

    private void removeAllBlueMapMarkers() {
        for (MarkerSet set : bluemapSets) {
            set.getMarkers().clear();
        }
    }

    private void updateAllDynmapMarkers() {
        if (dynmapMarkerSet != null)
            updateAllMapMarkers();
    }

    private void updateAllBlueMapMarkers() {
        if (!bluemapSets.isEmpty())
            updateAllMapMarkers();
    }

    private void updateDynmapMarker(int id) {
        if (dynmapMarkerSet == null) return;
        try {
            String path = "houses." + id;
            String world = housesConfig.getString(path + ".world");
            if (world == null) return;
            double x = housesConfig.getDouble(path + ".x") + 0.5;
            double y = housesConfig.getDouble(path + ".y");
            double z = housesConfig.getDouble(path + ".z") + 0.5;
            boolean rent = housesConfig.getBoolean(path + ".rent");
            double price = housesConfig.getDouble(path + ".price");
            String owner = housesConfig.getString(path + ".owner");
            String ownerName = owner == null ? null : Bukkit.getOfflinePlayer(java.util.UUID.fromString(owner)).getName();
            String label = "House #" + id;
            String desc = (ownerName == null ? "Available" : "Owner: " + ownerName) + "<br>Price: " + price + (rent ? " rent" : " buy");
            Class<?> markerSet = dynmapMarkerSet.getClass();
            Object marker = markerSet.getMethod("findMarker", String.class).invoke(dynmapMarkerSet, "house-" + id);
            Class<?> markerAPI = Class.forName("org.dynmap.markers.MarkerAPI");
            Object icon = markerAPI.getMethod("getMarkerIcon", String.class).invoke(dynmapMarkerAPI, "default");
            if (marker == null) {
                marker = markerSet.getMethod("createMarker", String.class, String.class, String.class, double.class, double.class, double.class, Class.forName("org.dynmap.markers.MarkerIcon"), boolean.class)
                        .invoke(dynmapMarkerSet, "house-" + id, label, world, x, y, z, icon, true);
            } else {
                marker.getClass().getMethod("setLocation", String.class, double.class, double.class, double.class)
                        .invoke(marker, world, x, y, z);
                marker.getClass().getMethod("setLabel", String.class).invoke(marker, label);
            }
            marker.getClass().getMethod("setDescription", String.class).invoke(marker, desc);
        } catch (Exception ex) {
            getLogger().warning("Dynmap marker error: " + ex.getMessage());
        }
    }

    private void removeDynmapMarker(int id) {
        if (dynmapMarkerSet == null) return;
        try {
            Object marker = dynmapMarkerSet.getClass().getMethod("findMarker", String.class).invoke(dynmapMarkerSet, "house-" + id);
            if (marker != null) marker.getClass().getMethod("deleteMarker").invoke(marker);
        } catch (Exception ignored) {}
    }

    private void updateBlueMapMarker(int id) {
        if (bluemapSets.isEmpty()) return;
        String path = "houses." + id;
        int x = housesConfig.getInt(path + ".x");
        int y = housesConfig.getInt(path + ".y");
        int z = housesConfig.getInt(path + ".z");
        boolean rent = housesConfig.getBoolean(path + ".rent");
        double price = housesConfig.getDouble(path + ".price");
        String owner = housesConfig.getString(path + ".owner");
        String ownerName = owner == null ? null : Bukkit.getOfflinePlayer(java.util.UUID.fromString(owner)).getName();
        String label = (rent ? "[Rent] " : "[Buy] ") + "House #" + id;
        String desc = (ownerName == null ? "Available" : "Owner: " + ownerName) + " Price: " + price;
        Vector3d pos = new Vector3d(x + 0.5, y, z + 0.5);
        for (MarkerSet set : bluemapSets) {
            java.util.Map<String, de.bluecolored.bluemap.api.markers.Marker> markers = set.getMarkers();
            de.bluecolored.bluemap.api.markers.Marker base = markers.get("house-" + id);
            if (!(base instanceof POIMarker)) {
                POIMarker marker = POIMarker.builder()
                        .label(label)
                        .position(pos)
                        .detail(desc)
                        .build();
                markers.put("house-" + id, marker);
            } else {
                POIMarker marker = (POIMarker) base;
                marker.setPosition(pos);
                marker.setLabel(label);
                marker.setDetail(desc);
            }
        }
    }

    private void removeBlueMapMarker(int id) {
        if (bluemapSets.isEmpty()) return;
        for (MarkerSet set : bluemapSets) {
            set.getMarkers().remove("house-" + id);
        }
    }

    private void checkRentPayments() {
        checkRentPayments(null);
    }

    private void checkRentPayments(UUID only) {
        long period = getConfig().getLong("rent.period", 2400) * 1000L;
        long now = System.currentTimeMillis();
        if (housesConfig.isConfigurationSection("houses")) {
            for (String idStr : housesConfig.getConfigurationSection("houses").getKeys(false)) {
                String path = "houses." + idStr;
                if (!housesConfig.getBoolean(path + ".rent")) continue;
                String owner = housesConfig.getString(path + ".owner");
                if (owner == null) continue;
                if (only != null && !owner.equals(only.toString())) continue;
                long next = housesConfig.getLong(path + ".nextRent", 0L);
                if (now >= next) {
                    OfflinePlayer op = Bukkit.getOfflinePlayer(UUID.fromString(owner));
                    if (!op.isOnline()) continue;
                    double price = housesConfig.getDouble(path + ".price");
                    if (economy.has(op, price)) {
                        economy.withdrawPlayer(op, price);
                        housesConfig.set(path + ".nextRent", now + period);
                        sendConfiguredMessage(op.getPlayer(), "rent-paid", Integer.parseInt(idStr));
                    } else {
                        housesConfig.set(path + ".owner", null);
                        housesConfig.set(path + ".trusted", new ArrayList<>());
                        housesConfig.set(path + ".nextRent", null);
                        updateHouseSign(Integer.parseInt(idStr), null);
                        sendConfiguredMessage(op.getPlayer(), "rent-stopped", Integer.parseInt(idStr));
                    }
                }
            }
            saveHouses();
        }
    }

    private void checkInactiveOwners() {
        int days = getConfig().getInt("cleanup.inactive-days", 30);
        long limit = System.currentTimeMillis() - days * 86400000L;
        if (housesConfig.isConfigurationSection("houses")) {
            for (String idStr : housesConfig.getConfigurationSection("houses").getKeys(false)) {
                String path = "houses." + idStr;
                String owner = housesConfig.getString(path + ".owner");
                if (owner == null) continue;
                OfflinePlayer op = Bukkit.getOfflinePlayer(UUID.fromString(owner));
                if (op.isOnline()) continue;
                if (op.getLastPlayed() >= limit) continue;
                housesConfig.set(path + ".owner", null);
                housesConfig.set(path + ".trusted", new ArrayList<>());
                housesConfig.set(path + ".nextRent", null);
                updateHouseSign(Integer.parseInt(idStr), null);
                if (op.isOnline()) {
                    sendConfiguredMessage(op.getPlayer(), "inactive-removed", Integer.parseInt(idStr));
                }
            }
            saveHouses();
        }
    }

    private void loadHousesFromDatabase() {
        loadHousesFromConnection(sqlConnection);
    }

    private void loadHousesFromConnection(Connection conn) {
        if (conn == null) return;
        debug("Loading houses from database");
        try (Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT * FROM houses");
            int lastId = 0;
            housesConfig.set("houses", null);
            while (rs.next()) {
                int id = rs.getInt("id");
                lastId = Math.max(lastId, id);
                String path = "houses." + id;
                housesConfig.set(path + ".rent", rs.getBoolean("rent"));
                housesConfig.set(path + ".price", rs.getDouble("price"));
                housesConfig.set(path + ".owner", rs.getString("owner"));
                housesConfig.set(path + ".nextRent", rs.getLong("next_rent"));
                housesConfig.set(path + ".world", rs.getString("world"));
                housesConfig.set(path + ".x", rs.getInt("x"));
                housesConfig.set(path + ".y", rs.getInt("y"));
                housesConfig.set(path + ".z", rs.getInt("z"));
                String doors = rs.getString("doors");
                housesConfig.set(path + ".doors", doors == null || doors.isEmpty() ? new ArrayList<>() : Arrays.asList(doors.split(";")));
                String trusted = rs.getString("trusted");
                housesConfig.set(path + ".trusted", trusted == null || trusted.isEmpty() ? new ArrayList<>() : Arrays.asList(trusted.split(";")));
            }
            housesConfig.set("lastId", lastId);
            updateAllMapMarkers();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void saveHousesToDatabase() {
        saveHousesToConnection(sqlConnection);
    }

    private void saveHousesToConnection(Connection conn) {
        if (conn == null) return;
        debug("Saving houses to database");
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("DELETE FROM houses");
            if (housesConfig.isConfigurationSection("houses")) {
                for (String idStr : housesConfig.getConfigurationSection("houses").getKeys(false)) {
                    String path = "houses." + idStr;
                    PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO houses(id,rent,price,owner,next_rent,world,x,y,z,doors,trusted) VALUES (?,?,?,?,?,?,?,?,?,?,?)");
                    ps.setInt(1, Integer.parseInt(idStr));
                    ps.setBoolean(2, housesConfig.getBoolean(path + ".rent"));
                    ps.setDouble(3, housesConfig.getDouble(path + ".price"));
                    ps.setString(4, housesConfig.getString(path + ".owner"));
                    ps.setLong(5, housesConfig.getLong(path + ".nextRent", 0L));
                    ps.setString(6, housesConfig.getString(path + ".world"));
                    ps.setInt(7, housesConfig.getInt(path + ".x"));
                    ps.setInt(8, housesConfig.getInt(path + ".y"));
                    ps.setInt(9, housesConfig.getInt(path + ".z"));
                    java.util.List<String> doors = housesConfig.getStringList(path + ".doors");
                    ps.setString(10, String.join(";", doors));
                    java.util.List<String> trusted = housesConfig.getStringList(path + ".trusted");
                    ps.setString(11, String.join(";", trusted));
                    ps.executeUpdate();
                    ps.close();
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }


    public void backupToMysql() {
        try (Connection source = connectSqlite(); Connection target = connectMysql()) {
            loadHousesFromConnection(source);
            saveHousesToConnection(target);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void backupToSqlite() {
        try (Connection source = connectMysql(); Connection target = connectSqlite()) {
            loadHousesFromConnection(source);
            saveHousesToConnection(target);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void sendConfiguredMessage(Player player, String key, int id) {
        String msg = getConfig().getString("messages." + key);
        if (msg != null) {
            msg = msg.replace("{id}", String.valueOf(id));
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', msg));
        }
    }

    @EventHandler
    public void onSignChange(SignChangeEvent e) {
        if (!e.getPlayer().hasPermission("houses.admin")) return;
        String line0 = ChatColor.stripColor(e.getLine(0));
        if ("[House]".equalsIgnoreCase(line0)) {
            createHouseSign(e, false);
        } else if ("[Rent]".equalsIgnoreCase(line0)) {
            createHouseSign(e, true);
        }
    }

    private void createHouseSign(SignChangeEvent e, boolean rent) {
        String priceLine = e.getLine(1).replace("$", "");
        double price = 0;
        try {
            price = Double.parseDouble(priceLine);
        } catch (Exception ex) {
            e.getPlayer().sendMessage(ChatColor.YELLOW + "[Houses]" + ChatColor.RED + "Invalid price");
            return;
        }
        if (price <= 0) {
            e.getPlayer().sendMessage(ChatColor.YELLOW + "[Houses]" + ChatColor.RED + "Price must be positive");
            return;
        }
        int id = housesConfig.getInt("lastId", 0) + 1;
        housesConfig.set("lastId", id);
        String path = "houses." + id;
        housesConfig.set(path + ".rent", rent);
        housesConfig.set(path + ".price", price);
        housesConfig.set(path + ".owner", null);
        Block b = e.getBlock();
        housesConfig.set(path + ".world", b.getWorld().getName());
        housesConfig.set(path + ".x", b.getX());
        housesConfig.set(path + ".y", b.getY());
        housesConfig.set(path + ".z", b.getZ());
        saveHouses();

        e.setLine(0, ChatColor.GREEN + (rent ? "[Rent]" : "[House]"));
        e.setLine(1, "$" + price);
        e.setLine(2, "");
        e.setLine(3, "ID: " + id);
        updateMapMarkers(id);
    }

    @EventHandler
    public void onBlockBreak(org.bukkit.event.block.BlockBreakEvent e) {
        Block b = e.getBlock();
        if (!isSign(b.getType())) return;
        if (!(b.getState() instanceof Sign)) return;
        Sign sign = (Sign) b.getState();
        String line0 = ChatColor.stripColor(sign.getLine(0));
        if (!line0.equalsIgnoreCase("[House]") && !line0.equalsIgnoreCase("[Rent]")) return;
        String idPart = ChatColor.stripColor(sign.getLine(3));
        if (!idPart.toLowerCase().startsWith("id:")) return;
        int id;
        try { id = Integer.parseInt(idPart.substring(3).trim()); } catch (Exception ex) { return; }
        if (e.getPlayer().hasPermission("houses.admin")) {
            housesConfig.set("houses." + id, null);
            saveHouses();
            removeMapMarker(id);
            e.getPlayer().sendMessage(ChatColor.YELLOW + "[Houses]" + ChatColor.GREEN + "House " + id + " removed");
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = e.getClickedBlock();
        if (block == null) return;
        Player p = e.getPlayer();
        ItemStack hand = e.getItem();
        if (isWand(hand) && p.hasPermission("houses.admin")) {
            handleWandUse(p, block);
            e.setCancelled(true);
            return;
        }
        Material type = block.getType();

        // handle door additions or removals
        if (isDoor(type)) {
            UUID uid = p.getUniqueId();
            if (awaitingDoorAdd.containsKey(uid)) {
                int id = awaitingDoorAdd.remove(uid);
                addDoorToHouse(id, block);
                p.sendMessage(ChatColor.YELLOW + "[Houses]" + ChatColor.GREEN + "Door added to house " + id);
                e.setCancelled(true);
                return;
            }
            if (awaitingDoorRemove.containsKey(uid)) {
                int id = awaitingDoorRemove.remove(uid);
                removeDoorFromHouse(id, block);
                p.sendMessage(ChatColor.YELLOW + "[Houses]" + ChatColor.GREEN + "Door removed from house " + id);
                e.setCancelled(true);
                return;
            }

            int doorHouse = getHouseIdByDoor(block);
            if (doorHouse != -1) {
                String owner = housesConfig.getString("houses." + doorHouse + ".owner");
                boolean trusted = isTrusted(doorHouse, uid);
                if (owner == null || !(owner.equals(uid.toString()) || trusted)) {
                    sendConfiguredMessage(p, "door-locked", doorHouse);
                    e.setCancelled(true);
                    return;
                }
            }
        }

        // sign interactions
        if (!isSign(type)) {
            return;
        }

        Sign sign = (Sign) block.getState();
        String line0 = ChatColor.stripColor(sign.getLine(0));
        if (!line0.equalsIgnoreCase("[House]") && !line0.equalsIgnoreCase("[Rent]")) return;
        String idPart = ChatColor.stripColor(sign.getLine(3));
        if (!idPart.toLowerCase().startsWith("id:")) return;
        int id;
        try {
            id = Integer.parseInt(idPart.substring(3).trim());
        } catch (Exception ex) {
            return;
        }
        String path = "houses." + id;
        boolean rent = housesConfig.getBoolean(path + ".rent");
        double price = housesConfig.getDouble(path + ".price");
        String owner = housesConfig.getString(path + ".owner");

        if (owner == null) {
            p.sendMessage(ChatColor.YELLOW + (rent ? "Rent" : "Buy") + " price: " + price);
            if (p.isSneaking()) {
                int max = getConfig().getInt("max-houses-per-player", 0);
                if (max > 0) {
                    int owned = 0;
                    if (housesConfig.isConfigurationSection("houses")) {
                        for (String hid : housesConfig.getConfigurationSection("houses").getKeys(false)) {
                            if (p.getUniqueId().toString().equals(housesConfig.getString("houses." + hid + ".owner"))) {
                                owned++;
                            }
                        }
                    }
                    if (owned >= max) {
                        p.sendMessage(ChatColor.YELLOW + "[Houses]" + ChatColor.RED + "You have reached the house limit of " + max);
                        return;
                    }
                }
                if (economy.getBalance(p) >= price) {
                    economy.withdrawPlayer(p, price);
                    housesConfig.set(path + ".owner", p.getUniqueId().toString());
                    saveHouses();
                    updateHouseSign(id, p.getName());
                    sendConfiguredMessage(p, rent ? "rent-success" : "buy-success", id);
                } else {
                    p.sendMessage(ChatColor.YELLOW + "[Houses]" + ChatColor.RED + "Not enough money");
                }
            } else {
                p.sendMessage(ChatColor.YELLOW + "[Houses]" + ChatColor.GRAY + "Sneak and right click to confirm");
            }
        } else if (owner.equals(p.getUniqueId().toString())) {
            double sellPrice = price * 0.75;
            p.sendMessage(ChatColor.YELLOW + "Sell price: " + sellPrice);
            if (p.isSneaking()) {
                economy.depositPlayer(p, sellPrice);
                housesConfig.set(path + ".owner", null);
                saveHouses();
                updateHouseSign(id, null);
                sendConfiguredMessage(p, rent ? "stop-rent-success" : "sell-success", id);
            } else {
                p.sendMessage(ChatColor.YELLOW + "[Houses]" + ChatColor.GRAY + "Sneak and right click to confirm sale");
            }
        } else {
            p.sendMessage(ChatColor.YELLOW + "[Houses]" + ChatColor.RED + "Someone else owns this.");
        }
    }

    private boolean isDoor(Material m) {
        return m.name().endsWith("_DOOR");
    }

    private boolean isSign(Material m) {
        String name = m.name();
        return name.endsWith("_SIGN") || name.endsWith("_WALL_SIGN");
    }

    private String serialize(Block b) {
        return b.getWorld().getName() + "," + b.getX() + "," + b.getY() + "," + b.getZ();
    }

    private void addDoorToHouse(int id, Block door) {
        String path = "houses." + id + ".doors";
        List<String> doors = housesConfig.getStringList(path);
        String ser = serialize(door);
        if (!doors.contains(ser)) {
            doors.add(ser);
            housesConfig.set(path, doors);
            saveHouses();
        }
    }

    private void removeDoorFromHouse(int id, Block door) {
        String path = "houses." + id + ".doors";
        List<String> doors = housesConfig.getStringList(path);
        String ser = serialize(door);
        if (doors.remove(ser)) {
            housesConfig.set(path, doors);
            saveHouses();
        }
    }

    private List<String> getTrusted(int id) {
        return new ArrayList<>(housesConfig.getStringList("houses." + id + ".trusted"));
    }

    private boolean isTrusted(int id, UUID uuid) {
        return getTrusted(id).contains(uuid.toString());
    }

    public void addTrusted(int id, UUID uuid) {
        List<String> list = getTrusted(id);
        String s = uuid.toString();
        if (!list.contains(s)) {
            list.add(s);
            housesConfig.set("houses." + id + ".trusted", list);
            saveHouses();
        }
    }

    public void removeTrusted(int id, UUID uuid) {
        List<String> list = getTrusted(id);
        if (list.remove(uuid.toString())) {
            housesConfig.set("houses." + id + ".trusted", list);
            saveHouses();
        }
    }

    private int getHouseIdByDoor(Block b) {
        if (!housesConfig.isConfigurationSection("houses")) return -1;
        String serClicked = serialize(b);
        // also check the other half of the door as players might click the top or bottom
        String serOther = null;
        BlockData data = b.getBlockData();
        if (data instanceof Bisected) {
            Bisected bisected = (Bisected) data;
            Block other = bisected.getHalf() == Bisected.Half.TOP ? b.getRelative(BlockFace.DOWN)
                    : b.getRelative(BlockFace.UP);
            serOther = serialize(other);
        }

        for (String idStr : housesConfig.getConfigurationSection("houses").getKeys(false)) {
            List<String> doors = housesConfig.getStringList("houses." + idStr + ".doors");
            if (doors.contains(serClicked) || (serOther != null && doors.contains(serOther))) {
                try {
                    return Integer.parseInt(idStr);
                } catch (NumberFormatException ignore) {
                }
            }
        }
        return -1;
    }

    public void updateHouseSign(int id, String ownerName) {
        String path = "houses." + id + ".";
        String world = housesConfig.getString(path + "world");
        if (world == null) return;
        Block b = Bukkit.getWorld(world).getBlockAt(
                housesConfig.getInt(path + "x"),
                housesConfig.getInt(path + "y"),
                housesConfig.getInt(path + "z"));
        if (b.getState() instanceof Sign) {
            Sign sign = (Sign) b.getState();
            double price = housesConfig.getDouble(path + "price");
            sign.setLine(1, "$" + price);
            sign.setLine(2, ownerName == null ? "" : "Owner:" + ownerName);
            sign.setLine(3, "ID: " + id);
            sign.update();
        }
        updateMapMarkers(id);
    }

    private void buyHouse(Player p, int id) {
        String path = "houses." + id;
        boolean rent = housesConfig.getBoolean(path + ".rent");
        double price = housesConfig.getDouble(path + ".price");

        int max = getConfig().getInt("max-houses-per-player", 0);
        if (max > 0) {
            int owned = 0;
            if (housesConfig.isConfigurationSection("houses")) {
                for (String hid : housesConfig.getConfigurationSection("houses").getKeys(false)) {
                    if (p.getUniqueId().toString().equals(housesConfig.getString("houses." + hid + ".owner"))) {
                        owned++;
                    }
                }
            }
            if (owned >= max) {
                p.sendMessage(ChatColor.YELLOW + "[Houses]" + ChatColor.RED + "You have reached the house limit of " + max);
                return;
            }
        }

        if (economy.getBalance(p) >= price) {
            economy.withdrawPlayer(p, price);
            housesConfig.set(path + ".owner", p.getUniqueId().toString());
            housesConfig.set(path + ".trusted", new ArrayList<>());
            if (rent) {
                long period = getConfig().getLong("rent.period", 2400) * 1000L;
                housesConfig.set(path + ".nextRent", System.currentTimeMillis() + period);
            }
            saveHouses();
            updateHouseSign(id, p.getName());
            sendConfiguredMessage(p, rent ? "rent-success" : "buy-success", id);
        } else {
            p.sendMessage(ChatColor.YELLOW + "[Houses]" + ChatColor.RED + "Not enough money");
        }
    }

    private void sellHouse(Player p, int id) {
        String path = "houses." + id;
        boolean rent = housesConfig.getBoolean(path + ".rent");
        double price = housesConfig.getDouble(path + ".price");
        double sellPrice = price * 0.75;
        economy.depositPlayer(p, sellPrice);
        housesConfig.set(path + ".owner", null);
        housesConfig.set(path + ".trusted", new ArrayList<>());
        housesConfig.set(path + ".nextRent", null);
        saveHouses();
        updateHouseSign(id, null);
        sendConfiguredMessage(p, rent ? "stop-rent-success" : "sell-success", id);
    }

    private void teleportToHouse(Player p, int id) {
        String path = "houses." + id + ".";
        String world = housesConfig.getString(path + "world");
        if (world == null) {
            p.sendMessage(ChatColor.YELLOW + "[Houses]" + ChatColor.RED + "Location not found");
            return;
        }
        int x = housesConfig.getInt(path + "x");
        int y = housesConfig.getInt(path + "y");
        int z = housesConfig.getInt(path + "z");
        int wait = getConfig().getInt("teleport-wait", 5);
        p.sendMessage(ChatColor.YELLOW + "[Houses]" + ChatColor.GREEN + "Teleporting in " + wait + " seconds...");
        org.bukkit.Location startLoc = p.getLocation();
        int task = Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) {
                pendingTeleports.remove(p.getUniqueId());
                teleportLocations.remove(p.getUniqueId());
                p.teleport(new org.bukkit.Location(Bukkit.getWorld(world), x, y, z));
            }
        }, 20L * wait).getTaskId();
        pendingTeleports.put(p.getUniqueId(), task);
        teleportLocations.put(p.getUniqueId(), startLoc);
    }

    public void openMarket(Player p, MarketFilter filter) {
        openMarket(p, filter, 0);
    }

    public void openMarket(Player p, MarketFilter filter, int page) {
        marketFilters.put(p.getUniqueId(), filter);

        List<Integer> ids = new ArrayList<>();
        if (housesConfig.isConfigurationSection("houses")) {
            for (String idStr : housesConfig.getConfigurationSection("houses").getKeys(false)) {
                String owner = housesConfig.getString("houses." + idStr + ".owner");
                int id = Integer.parseInt(idStr);
                if (filter == MarketFilter.AVAILABLE && owner != null) continue;
                if (filter == MarketFilter.OWNED && !p.getUniqueId().toString().equals(owner)) continue;
                ids.add(id);
            }
        }

        int pages = Math.max(1, (ids.size() + 26) / 27);
        if (page < 0) page = 0;
        if (page >= pages) page = pages - 1;
        marketPages.put(p.getUniqueId(), page);

        Inventory inv = Bukkit.createInventory(null, 45, "House Market");

        inv.setItem(0, createButton(Material.LIME_DYE, ChatColor.GREEN + "All"));
        inv.setItem(1, createButton(Material.BLUE_DYE, ChatColor.BLUE + "Available"));
        inv.setItem(2, createButton(Material.YELLOW_DYE, ChatColor.YELLOW + "Owned"));

        int start = page * 27;
        for (int i = 0; i < 27 && start + i < ids.size(); i++) {
            int id = ids.get(start + i);
            String path = "houses." + id;
            boolean rent = housesConfig.getBoolean(path + ".rent");
            double price = housesConfig.getDouble(path + ".price");
            ItemStack item = new ItemStack(rent ? Material.OAK_DOOR : Material.BRICKS);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(ChatColor.GOLD + "House #" + id);
            meta.setLore(Arrays.asList(ChatColor.YELLOW + "Price: " + price,
                    ChatColor.GRAY + (rent ? "Rent" : "Buy")));
            item.setItemMeta(meta);
            inv.setItem(9 + i, item);
        }

        if (page > 0) {
            inv.setItem(36, createButton(Material.ARROW, ChatColor.YELLOW + "Prev"));
        }
        inv.setItem(40, createButton(Material.PAPER, ChatColor.YELLOW + "Page " + (page + 1) + "/" + pages));
        if (page < pages - 1) {
            inv.setItem(44, createButton(Material.ARROW, ChatColor.YELLOW + "Next"));
        }

        p.openInventory(inv);
    }

    private ItemStack createButton(Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }

    private void openHouseDetails(Player p, int id) {
        Inventory inv = Bukkit.createInventory(null, 27, "House #" + id);
        String path = "houses." + id;
        boolean rent = housesConfig.getBoolean(path + ".rent");
        double price = housesConfig.getDouble(path + ".price");
        String owner = housesConfig.getString(path + ".owner");

        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta meta = info.getItemMeta();
        meta.setDisplayName(ChatColor.GOLD + "House " + id);
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.YELLOW + "Price: " + price);
        lore.add(ChatColor.GRAY + (rent ? "Rent" : "Buy"));
        lore.add(ChatColor.GRAY + "Owner: " + (owner == null ? "none" : Bukkit.getOfflinePlayer(UUID.fromString(owner)).getName()));
        meta.setLore(lore);
        info.setItemMeta(meta);
        inv.setItem(11, info);

        ItemStack action = new ItemStack(Material.EMERALD_BLOCK);
        ItemMeta aMeta = action.getItemMeta();
        if (owner == null) {
            aMeta.setDisplayName(ChatColor.GREEN + (rent ? "Rent" : "Buy"));
        } else if (owner.equals(p.getUniqueId().toString())) {
            aMeta.setDisplayName(ChatColor.GREEN + (rent ? "Stop Rent" : "Sell"));
        } else {
            aMeta.setDisplayName(ChatColor.RED + "Not yours");
        }
        action.setItemMeta(aMeta);
        inv.setItem(15, action);

        ItemStack tp = new ItemStack(Material.ENDER_PEARL);
        ItemMeta tMeta = tp.getItemMeta();
        tMeta.setDisplayName(ChatColor.AQUA + "Teleport");
        tp.setItemMeta(tMeta);
        inv.setItem(13, tp);

        inv.setItem(22, createButton(Material.ARROW, ChatColor.RED + "Back"));

        p.openInventory(inv);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        String title = e.getView().getTitle();
        if (title.equals("House Market")) {
            e.setCancelled(true);
            int slot = e.getRawSlot();
            if (slot == 0) { openMarket(p, MarketFilter.ALL, 0); return; }
            if (slot == 1) { openMarket(p, MarketFilter.AVAILABLE, 0); return; }
            if (slot == 2) { openMarket(p, MarketFilter.OWNED, 0); return; }
            MarketFilter filter = marketFilters.getOrDefault(p.getUniqueId(), MarketFilter.ALL);
            int page = marketPages.getOrDefault(p.getUniqueId(), 0);
            if (slot == 36) { openMarket(p, filter, page - 1); return; }
            if (slot == 44) { openMarket(p, filter, page + 1); return; }
            if (slot >= 9 && slot < 36) {
                ItemStack item = e.getCurrentItem();
                if (item != null && item.hasItemMeta()) {
                    String name = ChatColor.stripColor(item.getItemMeta().getDisplayName());
                    if (name.startsWith("House #")) {
                        int id = Integer.parseInt(name.substring(7));
                        openHouseDetails(p, id);
                    }
                }
            }
        } else if (title.startsWith("House #")) {
            e.setCancelled(true);
            int id = Integer.parseInt(title.substring(7));
            String path = "houses." + id;
            String owner = housesConfig.getString(path + ".owner");
            ItemStack item = e.getCurrentItem();
            if (item == null || !item.hasItemMeta()) return;
            String name = ChatColor.stripColor(item.getItemMeta().getDisplayName());
            if (name.equalsIgnoreCase("Buy") || name.equalsIgnoreCase("Rent")) {
                if (owner == null) {
                    if (!confirmBuy.containsKey(p.getUniqueId()) || confirmBuy.get(p.getUniqueId()) != id) {
                        confirmBuy.put(p.getUniqueId(), id);
                        p.sendMessage(ChatColor.YELLOW + "[Houses]" + ChatColor.GREEN + "Click again to confirm purchase.");
                    } else {
                        confirmBuy.remove(p.getUniqueId());
                        buyHouse(p, id);
                        p.closeInventory();
                    }
                }
            } else if (name.equalsIgnoreCase("Sell") || name.equalsIgnoreCase("Stop Rent")) {
                if (owner != null && owner.equals(p.getUniqueId().toString())) {
                    if (!confirmSell.containsKey(p.getUniqueId()) || confirmSell.get(p.getUniqueId()) != id) {
                        confirmSell.put(p.getUniqueId(), id);
                        p.sendMessage(ChatColor.YELLOW + "[Houses]" + ChatColor.GREEN + "Click again to confirm sale.");
                    } else {
                        confirmSell.remove(p.getUniqueId());
                        sellHouse(p, id);
                        p.closeInventory();
                    }
                }
            } else if (name.equalsIgnoreCase("Teleport")) {
                p.closeInventory();
                teleportToHouse(p, id);
            } else if (name.equalsIgnoreCase("Back")) {
                MarketFilter filter = marketFilters.getOrDefault(p.getUniqueId(), MarketFilter.ALL);
                int page = marketPages.getOrDefault(p.getUniqueId(), 0);
                openMarket(p, filter, page);
            }
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        UUID uid = e.getPlayer().getUniqueId();
        confirmBuy.remove(uid);
        confirmSell.remove(uid);
    }

    @EventHandler
    public void onPlayerMove(org.bukkit.event.player.PlayerMoveEvent e) {
        UUID uid = e.getPlayer().getUniqueId();
        if (pendingTeleports.containsKey(uid)) {
            org.bukkit.Location start = teleportLocations.get(uid);
            if (start != null && e.getFrom().distanceSquared(start) > 0.1) {
                cancelTeleport(e.getPlayer());
            }
        }
    }

    @EventHandler
    public void onDamage(org.bukkit.event.entity.EntityDamageEvent e) {
        if (e.getEntity() instanceof Player) {
            Player p = (Player) e.getEntity();
            if (pendingTeleports.containsKey(p.getUniqueId())) {
                cancelTeleport(p);
            }
        }
    }

    @EventHandler
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        UUID uid = e.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskLater(this, () -> checkRentPayments(uid), 20L);
    }

    private void cancelTeleport(Player p) {
        Integer task = pendingTeleports.remove(p.getUniqueId());
        teleportLocations.remove(p.getUniqueId());
        if (task != null) {
            Bukkit.getScheduler().cancelTask(task);
            p.sendMessage(ChatColor.YELLOW + "[Houses]" + ChatColor.RED + "Teleport cancelled");
        }
    }

    private boolean isWand(ItemStack item) {
        if (item == null) return false;
        if (item.getType() != Material.BLAZE_ROD) return false;
        if (!item.hasItemMeta()) return false;
        String name = ChatColor.stripColor(item.getItemMeta().getDisplayName());
        return "HOUSE WAND".equalsIgnoreCase(name);
    }

    private void handleWandUse(Player p, Block block) {
        UUID uid = p.getUniqueId();
        Material type = block.getType();
        if (isSign(type) && block.getState() instanceof Sign) {
            Sign sign = (Sign) block.getState();
            String line0 = ChatColor.stripColor(sign.getLine(0));
            if (line0.equalsIgnoreCase("[House]") || line0.equalsIgnoreCase("[Rent]")) {
                String idPart = ChatColor.stripColor(sign.getLine(3));
                if (idPart.toLowerCase().startsWith("id:")) {
                    try {
                        int id = Integer.parseInt(idPart.substring(3).trim());
                        wandSelections.put(uid, id);
                        p.sendMessage(ChatColor.YELLOW + "[Houses]" + ChatColor.GREEN + "Selected house " + id);
                    } catch (Exception ignored) {}
                }
                return;
            }
        }

        Integer sel = wandSelections.get(uid);
        if (sel == null) {
            p.sendMessage(ChatColor.YELLOW + "[Houses]" + ChatColor.RED + "No house selected");
            return;
        }

        if (isDoor(type)) {
            if (isDoorInHouse(sel, block)) {
                removeDoorFromHouse(sel, block);
                p.sendMessage(ChatColor.YELLOW + "[Houses]" + ChatColor.GREEN + "Door removed from house " + sel);
            } else {
                addDoorToHouse(sel, block);
                p.sendMessage(ChatColor.YELLOW + "[Houses]" + ChatColor.GREEN + "Door added to house " + sel);
            }
            return;
        }

        // sign creation removed - wand now only toggles doors
    }

    private boolean isDoorInHouse(int id, Block door) {
        List<String> doors = housesConfig.getStringList("houses." + id + ".doors");
        String ser1 = serialize(door);
        if (doors.contains(ser1)) return true;
        Block other = getOtherHalf(door);
        return doors.contains(serialize(other));
    }

    private Block getOtherHalf(Block door) {
        BlockData data = door.getBlockData();
        if (data instanceof Bisected) {
            Bisected bis = (Bisected) data;
            return bis.getHalf() == Bisected.Half.TOP ? door.getRelative(BlockFace.DOWN) : door.getRelative(BlockFace.UP);
        }
        return door;
    }

    // sign creation methods removed

    private void debug(String msg) {
        if (sqlDebug) {
            getLogger().info("[SQL] " + msg);
        }
    }

}
