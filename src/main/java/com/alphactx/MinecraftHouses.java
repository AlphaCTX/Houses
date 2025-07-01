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

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.sql.*;

public class MinecraftHouses extends JavaPlugin implements Listener {
    private Economy economy;
    private File housesFile;
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
    private int rentTask = -1;

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
        if (useMysql()) {
            connectDatabase();
            loadHousesFromDatabase();
            startAutoSave();
        }
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("houses")).setExecutor(new HousesCommand(this));
        startRentTask();
        new Metrics(this, 26286);
                getLogger().info("  ╭───────────────────────╮");
                getLogger().info("  │      AlphaCTX's       │");
                getLogger().info("  │        Houses         │");
		getLogger().info("  │         Plugin        │");
		getLogger().info("  ╰───────────────────────╯");
		getLogger().info("        Houses Enabled!");
    }

    @Override
    public void onDisable() {
        saveHouses();
        stopAutoSave();
        stopRentTask();
        closeDatabase();
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
        housesFile = new File(getDataFolder(), "houses.yml");
        if (!housesFile.exists()) {
            try {
                housesFile.getParentFile().mkdirs();
                housesFile.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        housesConfig = YamlConfiguration.loadConfiguration(housesFile);
    }

    public void reloadPlugin() {
        reloadConfig();
        loadHouses();
        sqlDebug = getConfig().getBoolean("database.debug", false);
        if (useMysql()) {
            stopAutoSave();
            closeDatabase();
            connectDatabase();
            loadHousesFromDatabase();
            startAutoSave();
        }
    }
    public void saveHouses() {
        try {
            housesConfig.save(housesFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (useMysql()) {
            saveHousesToDatabase();
        }
    }

    public FileConfiguration getHousesConfig() {
        return housesConfig;
    }

    private void connectDatabase() {
        String host = getConfig().getString("database.host");
        int port = getConfig().getInt("database.port", 3306);
        String user = getConfig().getString("database.user");
        String pass = getConfig().getString("database.pass");
        String db = getConfig().getString("database.database");
        String url = "jdbc:mysql://" + host + ":" + port + "/" + db + "?useSSL=false";
        debug("Connecting to " + url);
        try {
            sqlConnection = DriverManager.getConnection(url, user, pass);
            try (PreparedStatement ps = sqlConnection.prepareStatement(
                    "CREATE TABLE IF NOT EXISTS houses (id INT PRIMARY KEY, rent TINYINT(1), price DOUBLE, owner VARCHAR(36), next_rent BIGINT, world VARCHAR(64), x INT, y INT, z INT, doors TEXT, trusted TEXT)")) {
                ps.executeUpdate();
            }
            debug("Connected to database");
        } catch (SQLException e) {
            e.printStackTrace();
        }
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
        autoSaveTask = getServer().getScheduler().runTaskTimer(this, () -> {
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
        rentTask = getServer().getScheduler().runTaskTimer(this, this::checkRentPayments, interval * 20L, interval * 20L).getTaskId();
    }

    private void stopRentTask() {
        if (rentTask != -1) {
            getServer().getScheduler().cancelTask(rentTask);
            rentTask = -1;
        }
    }

    private void checkRentPayments() {
        long period = getConfig().getLong("rent.period", 86400) * 1000L;
        long now = System.currentTimeMillis();
        if (housesConfig.isConfigurationSection("houses")) {
            for (String idStr : housesConfig.getConfigurationSection("houses").getKeys(false)) {
                String path = "houses." + idStr;
                if (!housesConfig.getBoolean(path + ".rent")) continue;
                String owner = housesConfig.getString(path + ".owner");
                if (owner == null) continue;
                long next = housesConfig.getLong(path + ".nextRent", 0L);
                if (now >= next) {
                    OfflinePlayer op = Bukkit.getOfflinePlayer(UUID.fromString(owner));
                    double price = housesConfig.getDouble(path + ".price");
                    if (economy.has(op, price)) {
                        economy.withdrawPlayer(op, price);
                        housesConfig.set(path + ".nextRent", now + period);
                        if (op.isOnline()) op.getPlayer().sendMessage(ChatColor.YELLOW + "[Houses]" + ChatColor.GREEN + "Rent for house " + idStr + " paid");
                    } else {
                        housesConfig.set(path + ".owner", null);
                        housesConfig.set(path + ".trusted", new ArrayList<>());
                        housesConfig.set(path + ".nextRent", null);
                        updateHouseSign(Integer.parseInt(idStr), null);
                        if (op.isOnline()) op.getPlayer().sendMessage(ChatColor.YELLOW + "[Houses]" + ChatColor.RED + "Rent unpaid, house " + idStr + " lost");
                    }
                }
            }
            saveHouses();
        }
    }

    private void loadHousesFromDatabase() {
        if (sqlConnection == null) return;
        debug("Loading houses from database");
        try (Statement st = sqlConnection.createStatement()) {
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
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void saveHousesToDatabase() {
        if (sqlConnection == null) return;
        debug("Saving houses to database");
        try (Statement st = sqlConnection.createStatement()) {
            st.executeUpdate("DELETE FROM houses");
            if (housesConfig.isConfigurationSection("houses")) {
                for (String idStr : housesConfig.getConfigurationSection("houses").getKeys(false)) {
                    String path = "houses." + idStr;
                    PreparedStatement ps = sqlConnection.prepareStatement(
                            "INSERT INTO houses(id,rent,price,owner,next_rent,world,x,y,z,doors,trusted) VALUES (?,?,?,?,?,?,?,?,?,?)");
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
            e.getPlayer().sendMessage(ChatColor.YELLOW + "[Houses]" + ChatColor.GREEN + "House " + id + " removed");
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        Block block = e.getClickedBlock();
        if (block == null) return;
        Player p = e.getPlayer();
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
                long period = getConfig().getLong("rent.period", 86400) * 1000L;
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

    private void cancelTeleport(Player p) {
        Integer task = pendingTeleports.remove(p.getUniqueId());
        teleportLocations.remove(p.getUniqueId());
        if (task != null) {
            Bukkit.getScheduler().cancelTask(task);
            p.sendMessage(ChatColor.YELLOW + "[Houses]" + ChatColor.RED + "Teleport cancelled");
        }
    }

    private void debug(String msg) {
        if (sqlDebug) {
            getLogger().info("[SQL] " + msg);
        }
    }

}
