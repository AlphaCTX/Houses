package com.example;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.configuration.file.FileConfiguration;

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
        if (args[0].equalsIgnoreCase("door")) {
            if (args.length < 3) {
                p.sendMessage(ChatColor.RED + "/houses door <add|remove> <id>");
                return true;
            }
            String action = args[1].toLowerCase();
            int id;
            try {
                id = Integer.parseInt(args[2]);
            } catch (NumberFormatException ex) {
                p.sendMessage(ChatColor.RED + "Invalid id");
                return true;
            }
            String owner = cfg.getString("houses." + id + ".owner");
            if (owner == null || !owner.equals(p.getUniqueId().toString())) {
                p.sendMessage(ChatColor.RED + "You don't own that house");
                return true;
            }
            if (action.equals("add")) {
                plugin.awaitingDoorAdd.put(p.getUniqueId(), id);
                plugin.awaitingDoorRemove.remove(p.getUniqueId());
                p.sendMessage(ChatColor.GREEN + "Right click a door to add to house " + id);
                return true;
            } else if (action.equals("remove")) {
                plugin.awaitingDoorRemove.put(p.getUniqueId(), id);
                plugin.awaitingDoorAdd.remove(p.getUniqueId());
                p.sendMessage(ChatColor.GREEN + "Right click a door to remove from house " + id);
                return true;
            } else {
                p.sendMessage(ChatColor.RED + "Unknown action");
                return true;
            }
        }

        if (args[0].equalsIgnoreCase("reload")) {
            if (!p.hasPermission("mchouses.admin")) {
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
        p.sendMessage(ChatColor.AQUA + "/houses door <add|remove> <id>" + ChatColor.GRAY + " - manage doors");
        if (p.hasPermission("mchouses.admin")) {
            p.sendMessage(ChatColor.AQUA + "/houses reload" + ChatColor.GRAY + " - reload configs");
        }
    }
}
