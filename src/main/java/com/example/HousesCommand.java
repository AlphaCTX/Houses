@@ -4,50 +4,53 @@ import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.SignChangeEvent;
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
@@ -67,124 +70,220 @@ public class MinecraftHouses extends JavaPlugin implements Listener {
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

    @EventHandler
    public void onSignChange(SignChangeEvent e) {
        if (!e.getPlayer().hasPermission("mchouses.admin")) return;
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

    @EventHandler
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
                if (owner != null && !owner.equals(uid.toString())) {
                    p.sendMessage(ChatColor.RED + "You don't own this house.");
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
        Player p = e.getPlayer();

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
                    p.sendMessage(ChatColor.GREEN + (rent ? "Rented" : "Purchased") + " house " + id);
                } else {
                    p.sendMessage(ChatColor.RED + "Not enough money");
                }
            } else {
                p.sendMessage(ChatColor.GRAY + "Sneak and right click to confirm");
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("adddoor")) {
            if (!p.hasPermission("houses.admin")) {
                p.sendMessage(ChatColor.RED + "No permission");
                return true;
            }
            if (args.length < 2) {
                p.sendMessage(ChatColor.RED + "/houses adddoor <id>");
                return true;
            }
            int id;
            try {
                id = Integer.parseInt(args[1]);
            } catch (NumberFormatException ex) {
                p.sendMessage(ChatColor.RED + "Invalid id");
                return true;
            }
            plugin.awaitingDoorAdd.put(p.getUniqueId(), id);
            plugin.awaitingDoorRemove.remove(p.getUniqueId());
            p.sendMessage(ChatColor.GREEN + "Right click a door to add to house " + id);
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!p.hasPermission("houses.admin")) {
                p.sendMessage(ChatColor.RED + "No permission");
                return true;
        } else if (owner.equals(p.getUniqueId().toString())) {
            double sellPrice = price * 0.75;
            p.sendMessage(ChatColor.YELLOW + "Sell price: " + sellPrice);
            if (p.isSneaking()) {
                economy.depositPlayer(p, sellPrice);
                housesConfig.set(path + ".owner", null);
                saveHouses();
                sign.setLine(2, "");
                sign.update();
                p.sendMessage(ChatColor.GREEN + "Sold house " + id);
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

    private void sendHelp(Player p) {
        p.sendMessage(ChatColor.YELLOW + "Available commands:");
        p.sendMessage(ChatColor.AQUA + "/houses list" + ChatColor.GRAY + " - list all houses");
        p.sendMessage(ChatColor.AQUA + "/houses owned" + ChatColor.GRAY + " - your houses");
        if (p.hasPermission("houses.admin")) {
            p.sendMessage(ChatColor.AQUA + "/houses adddoor <id>" + ChatColor.GRAY + " - add a door to a house");
            p.sendMessage(ChatColor.AQUA + "/houses reload" + ChatColor.GRAY + " - reload configs");
        }
    }
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

    private int getHouseIdByDoor(Block b) {
        if (!housesConfig.isConfigurationSection("houses")) return -1;
        String ser = serialize(b);
        for (String idStr : housesConfig.getConfigurationSection("houses").getKeys(false)) {
            List<String> doors = housesConfig.getStringList("houses." + idStr + ".doors");
            if (doors.contains(ser)) {
                try {
                    return Integer.parseInt(idStr);
                } catch (NumberFormatException ignore) {
                }
            }
        }
        return -1;
    }

}
