package com.example;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.OfflinePlayer;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import java.util.UUID;

public class HousesCommand implements CommandExecutor {
    private final MinecraftHouses plugin;

    public HousesCommand(MinecraftHouses plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("Only players");
            return true;
        }
        Player p = (Player) sender;
        FileConfiguration cfg = plugin.getHousesConfig();
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(p);
            return true;
        }

        if (args[0].equalsIgnoreCase("list")) {
            p.sendMessage(ChatColor.YELLOW + "Houses:");
            if (cfg.isConfigurationSection("houses")) {
                for (String id : cfg.getConfigurationSection("houses").getKeys(false)) {
                    String owner = cfg.getString("houses." + id + ".owner");
                    p.sendMessage(ChatColor.GRAY + "#" + id + " owner:" + (owner == null ? "none" : owner));
                }
            } else {
                p.sendMessage(ChatColor.GRAY + "(none)");
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("owned")) {
            p.sendMessage(ChatColor.YELLOW + "Owned houses:");
            if (cfg.isConfigurationSection("houses")) {
                for (String id : cfg.getConfigurationSection("houses").getKeys(false)) {
                    String owner = cfg.getString("houses." + id + ".owner");
                    if (p.getUniqueId().toString().equals(owner)) {
                        p.sendMessage(ChatColor.GREEN + "#" + id);
                    }
                }
            } else {
                p.sendMessage(ChatColor.GRAY + "(none)");
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("info")) {
            if (args.length < 2) {
                p.sendMessage(ChatColor.RED + "/houses info <id>");
                return true;
            }
            int id;
            try { id = Integer.parseInt(args[1]); } catch (NumberFormatException ex) { p.sendMessage(ChatColor.RED + "Invalid id"); return true; }
            if (!cfg.isConfigurationSection("houses." + id)) { p.sendMessage(ChatColor.RED + "Unknown house"); return true; }
            String path = "houses." + id;
            boolean rent = cfg.getBoolean(path + ".rent");
            double price = cfg.getDouble(path + ".price");
            String owner = cfg.getString(path + ".owner");
            p.sendMessage(ChatColor.GOLD + "House " + id);
            p.sendMessage(ChatColor.YELLOW + "Price: " + price);
            p.sendMessage(ChatColor.YELLOW + "Type: " + (rent ? "rent" : "buy"));
            p.sendMessage(ChatColor.YELLOW + "Owner: " + (owner == null ? "none" : Bukkit.getOfflinePlayer(UUID.fromString(owner)).getName()));
            p.sendMessage(ChatColor.YELLOW + "Doors: " + cfg.getStringList(path + ".doors").size());
            p.sendMessage(ChatColor.YELLOW + "Trusted: " + String.join(", ", getTrustedNames(cfg.getStringList(path + ".trusted"))));
            return true;
        }

        if (args[0].equalsIgnoreCase("trust") || args[0].equalsIgnoreCase("untrust")) {
            if (args.length < 3) {
                p.sendMessage(ChatColor.RED + "/houses " + args[0].toLowerCase() + " <id> <player>");
                return true;
            }
            int id;
            try { id = Integer.parseInt(args[1]); } catch (NumberFormatException ex) { p.sendMessage(ChatColor.RED + "Invalid id"); return true; }
            String path = "houses." + id;
            if (!cfg.isConfigurationSection(path)) { p.sendMessage(ChatColor.RED + "Unknown house"); return true; }
            String owner = cfg.getString(path + ".owner");
            if (owner == null || !owner.equals(p.getUniqueId().toString())) { p.sendMessage(ChatColor.RED + "Not owner"); return true; }
            OfflinePlayer target = Bukkit.getOfflinePlayer(args[2]);
            if (args[0].equalsIgnoreCase("trust")) {
                plugin.addTrusted(id, target.getUniqueId());
                p.sendMessage(ChatColor.GREEN + "Trusted " + target.getName());
            } else {
                plugin.removeTrusted(id, target.getUniqueId());
                p.sendMessage(ChatColor.GREEN + "Untrusted " + target.getName());
            }
            return true;
        }

        if (args[0].equalsIgnoreCase("market")) {
            plugin.openMarket(p, MinecraftHouses.MarketFilter.ALL);
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
            }
            plugin.reloadPlugin();
            p.sendMessage(ChatColor.GREEN + "Plugin reloaded");
            return true;
        }

        p.sendMessage(ChatColor.RED + "Unknown subcommand");
        return true;
    }

    private void sendHelp(Player p) {
        p.sendMessage(ChatColor.YELLOW + "Available commands:");
        p.sendMessage(ChatColor.AQUA + "/houses list" + ChatColor.GRAY + " - list all houses");
        p.sendMessage(ChatColor.AQUA + "/houses owned" + ChatColor.GRAY + " - your houses");
        p.sendMessage(ChatColor.AQUA + "/houses info <id>" + ChatColor.GRAY + " - house info");
        p.sendMessage(ChatColor.AQUA + "/houses trust <id> <player>" + ChatColor.GRAY + " - trust player");
        p.sendMessage(ChatColor.AQUA + "/houses untrust <id> <player>" + ChatColor.GRAY + " - untrust player");
        p.sendMessage(ChatColor.AQUA + "/houses market" + ChatColor.GRAY + " - open market GUI");
        if (p.hasPermission("houses.admin")) {
            p.sendMessage(ChatColor.AQUA + "/houses adddoor <id>" + ChatColor.GRAY + " - add a door to a house");
            p.sendMessage(ChatColor.AQUA + "/houses reload" + ChatColor.GRAY + " - reload configs");
        }
    }

    private java.util.List<String> getTrustedNames(java.util.List<String> uuids) {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (String u : uuids) {
            try {
                names.add(Bukkit.getOfflinePlayer(UUID.fromString(u)).getName());
            } catch (Exception ignored) {
            }
        }
        return names;
    }
}
