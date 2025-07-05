package com.alphactx;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

/**
 * PlaceholderAPI expansion for the Houses plugin.
 */
public class HousesPlaceholder extends PlaceholderExpansion {

    private final MinecraftHouses plugin;

    public HousesPlaceholder(MinecraftHouses plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public String getIdentifier() {
        return "houses";
    }

    @Override
    public String getAuthor() {
        return String.join(",", plugin.getDescription().getAuthors());
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public String onPlaceholderRequest(Player player, String identifier) {
        if (player == null) return "";
        if (identifier.equalsIgnoreCase("owned")) {
            return String.valueOf(countOwned(player));
        }
        if (identifier.equalsIgnoreCase("limit")) {
            return String.valueOf(plugin.getConfig().getInt("max-houses-per-player", 0));
        }
        if (identifier.equalsIgnoreCase("renting")) {
            return String.valueOf(countRenting(player));
        }
        return null;
    }

    private int countOwned(Player p) {
        int owned = 0;
        FileConfiguration cfg = plugin.getHousesConfig();
        if (cfg.isConfigurationSection("houses")) {
            for (String id : cfg.getConfigurationSection("houses").getKeys(false)) {
                String owner = cfg.getString("houses." + id + ".owner");
                if (owner != null && owner.equals(p.getUniqueId().toString())) {
                    owned++;
                }
            }
        }
        return owned;
    }

    private int countRenting(Player p) {
        int renting = 0;
        FileConfiguration cfg = plugin.getHousesConfig();
        if (cfg.isConfigurationSection("houses")) {
            for (String id : cfg.getConfigurationSection("houses").getKeys(false)) {
                String path = "houses." + id;
                String owner = cfg.getString(path + ".owner");
                if (owner != null && owner.equals(p.getUniqueId().toString()) && cfg.getBoolean(path + ".rent")) {
                    renting++;
                }
            }
        }
        return renting;
    }
}
