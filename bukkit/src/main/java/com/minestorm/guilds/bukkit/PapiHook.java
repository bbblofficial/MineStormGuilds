package com.minestorm.guilds.bukkit;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.entity.Player;

final class PapiHook {
    private PapiHook() {}
    static String apply(Player p, String text) {
        return PlaceholderAPI.setPlaceholders(p, text);
    }
}
