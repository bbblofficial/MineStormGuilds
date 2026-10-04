package com.minestorm.guilds.bukkit;

import org.bukkit.ChatColor;

public final class Msg {

    private Msg() {}

    public static String color(String s) {
        if (s == null) return "";
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    public static String strip(String s) {
        return ChatColor.stripColor(color(s));
    }

    public static String join(String[] a, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < a.length; i++) {
            if (i > from) sb.append(' ');
            sb.append(a[i]);
        }
        return sb.toString();
    }

    public static boolean isColorCode(char c) {
        return "0123456789abcdef".indexOf(Character.toLowerCase(c)) >= 0;
    }
}
