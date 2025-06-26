package com.example;

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

import java.io.File;
import java.io.IOException;
import java.util.*;

public class MinecraftHouses extends JavaPlugin implements Listener {
    private Economy economy;
    private File housesFile;
    private FileConfiguration housesConfig;

    public final Map<UUID, Integer> awaitingDoorAdd = new HashMap<>();
    public final Map<UUID, Integer> awaitingDoorRemove = new HashMap<>();

    public enum MarketFilter { ALL, AVAILABLE, OWNED }
    private final Map<UUID, MarketFilter> marketFilters = new HashMap<>();
    private final Map<UUID, Integer> marketPages = new HashMap<>();

    private final Map<UUID, Integer> confirmBuy = new HashMap<>();
    private final Map<UUID, Integer> confirmSell = new HashMap<>();

    @Override
    public void onEnable() {
        if (!setupEconomy()) {
            getLogger().severe("Vault not found. Disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        loadHouses();
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("houses")).setExecutor(new HousesCommand(this));
    }

    @Override
    public void onDisable() {
        saveHouses();
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
    }
    public void saveHouses() {
        try {
            housesConfig.save(housesFile);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public FileConfiguration getHousesConfig() {
        return housesConfig;
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
        String priceLine = e.getLine(1);
        double price = 0;
        try {
            price = Double.parseDouble(priceLine);
        } catch (Exception ex) {
            e.getPlayer().sendMessage(ChatColor.RED + "Invalid price");
            return;
        }
        if (price <= 0) {
            e.getPlayer().sendMessage(ChatColor.RED + "Price must be positive");
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
        e.setLine(1, priceLine);
        e.setLine(2, "");
        e.setLine(3, "id:" + id);
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
                p.sendMessage(ChatColor.GREEN + "Door added to house " + id);
                e.setCancelled(true);
                return;
            }
            if (awaitingDoorRemove.containsKey(uid)) {
                int id = awaitingDoorRemove.remove(uid);
                removeDoorFromHouse(id, block);
                p.sendMessage(ChatColor.GREEN + "Door removed from house " + id);
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
        if (!(type == Material.OAK_SIGN || type == Material.OAK_WALL_SIGN || type == Material.SPRUCE_SIGN || type == Material.SPRUCE_WALL_SIGN ||
                type == Material.BIRCH_SIGN || type == Material.BIRCH_WALL_SIGN || type == Material.JUNGLE_SIGN || type == Material.JUNGLE_WALL_SIGN ||
                type == Material.ACACIA_SIGN || type == Material.ACACIA_WALL_SIGN || type == Material.DARK_OAK_SIGN || type == Material.DARK_OAK_WALL_SIGN)) {
            return;
        }

        Sign sign = (Sign) block.getState();
        String line0 = ChatColor.stripColor(sign.getLine(0));
        if (!line0.equalsIgnoreCase("[House]") && !line0.equalsIgnoreCase("[Rent]")) return;
        String idPart = ChatColor.stripColor(sign.getLine(3));
        if (!idPart.startsWith("id:")) return;
        int id;
        try {
            id = Integer.parseInt(idPart.substring(3));
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
                        p.sendMessage(ChatColor.RED + "You have reached the house limit of " + max);
                        return;
                    }
                }
                if (economy.getBalance(p) >= price) {
                    economy.withdrawPlayer(p, price);
                    housesConfig.set(path + ".owner", p.getUniqueId().toString());
                    saveHouses();
                    sign.setLine(2, p.getName());
                    sign.update();
                    sendConfiguredMessage(p, rent ? "rent-success" : "buy-success", id);
                } else {
                    p.sendMessage(ChatColor.RED + "Not enough money");
                }
            } else {
                p.sendMessage(ChatColor.GRAY + "Sneak and right click to confirm");
            }
        } else if (owner.equals(p.getUniqueId().toString())) {
            double sellPrice = price * 0.75;
            p.sendMessage(ChatColor.YELLOW + "Sell price: " + sellPrice);
            if (p.isSneaking()) {
                economy.depositPlayer(p, sellPrice);
                housesConfig.set(path + ".owner", null);
                saveHouses();
                sign.setLine(2, "");
                sign.update();
                sendConfiguredMessage(p, rent ? "stop-rent-success" : "sell-success", id);
            } else {
                p.sendMessage(ChatColor.GRAY + "Sneak and right click to confirm sale");
            }
        } else {
            p.sendMessage(ChatColor.RED + "Someone else owns this.");
        }
    }

    private boolean isDoor(Material m) {
        return m.name().endsWith("_DOOR");
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

    private void updateHouseSign(int id, String ownerName) {
        String path = "houses." + id + ".";
        String world = housesConfig.getString(path + "world");
        if (world == null) return;
        Block b = Bukkit.getWorld(world).getBlockAt(
                housesConfig.getInt(path + "x"),
                housesConfig.getInt(path + "y"),
                housesConfig.getInt(path + "z"));
        if (b.getState() instanceof Sign) {
            Sign sign = (Sign) b.getState();
            sign.setLine(2, ownerName == null ? "" : ownerName);
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
                p.sendMessage(ChatColor.RED + "You have reached the house limit of " + max);
                return;
            }
        }

        if (economy.getBalance(p) >= price) {
            economy.withdrawPlayer(p, price);
            housesConfig.set(path + ".owner", p.getUniqueId().toString());
            housesConfig.set(path + ".trusted", new ArrayList<>());
            saveHouses();
            updateHouseSign(id, p.getName());
            sendConfiguredMessage(p, rent ? "rent-success" : "buy-success", id);
        } else {
            p.sendMessage(ChatColor.RED + "Not enough money");
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
        saveHouses();
        updateHouseSign(id, null);
        sendConfiguredMessage(p, rent ? "stop-rent-success" : "sell-success", id);
    }

    private void teleportToHouse(Player p, int id) {
        String path = "houses." + id + ".";
        String world = housesConfig.getString(path + "world");
        if (world == null) {
            p.sendMessage(ChatColor.RED + "Location not found");
            return;
        }
        int x = housesConfig.getInt(path + "x");
        int y = housesConfig.getInt(path + "y");
        int z = housesConfig.getInt(path + "z");
        p.sendMessage(ChatColor.YELLOW + "Teleporting in 5 seconds...");
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) {
                p.teleport(new org.bukkit.Location(Bukkit.getWorld(world), x, y, z));
            }
        }, 20L * 5);
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
                        p.sendMessage(ChatColor.YELLOW + "Click again to confirm purchase.");
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
                        p.sendMessage(ChatColor.YELLOW + "Click again to confirm sale.");
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

}
