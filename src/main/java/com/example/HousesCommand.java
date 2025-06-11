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
        if (args.length == 0 || args[0].equalsIgnoreCase("list")) {
            p.sendMessage(ChatColor.YELLOW + "Houses:");
            for (String id : cfg.getConfigurationSection("houses").getKeys(false)) {
                String owner = cfg.getString("houses." + id + ".owner");
                p.sendMessage(ChatColor.GRAY + "#" + id + " owner:" + (owner == null ? "none" : owner));
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("owned")) {
            p.sendMessage(ChatColor.YELLOW + "Owned houses:");
            for (String id : cfg.getConfigurationSection("houses").getKeys(false)) {
                String owner = cfg.getString("houses." + id + ".owner");
                if (p.getUniqueId().toString().equals(owner)) {
                    p.sendMessage(ChatColor.GREEN + "#" + id);
                }
            }
            return true;
        }
        p.sendMessage(ChatColor.RED + "Unknown subcommand");
        return true;
    }
}
