package com.minestorm.guilds.bukkit;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.UUID;

public class GuildPlaceholders extends PlaceholderExpansion {

    private final MineStormGuilds plugin;
    public GuildPlaceholders(MineStormGuilds plugin) { this.plugin = plugin; }

    @Override public String getIdentifier() { return "minestormguilds"; }
    @Override public String getAuthor() { return "Muvixo"; }
    @Override public String getVersion() { return plugin.getDescription().getVersion(); }
    @Override public boolean persist() { return true; }
    @Override public boolean canRegister() { return true; }

    @Override
    public String onPlaceholderRequest(Player p, String id) {
        if (p == null) return "";
        Guild g = plugin.getGuildManager().getGuild(p.getUniqueId());
        String none = plugin.getConfig().getString("placeholders.no-guild", "");
        String key = id.toLowerCase();
        if (g == null) {
            if (key.equals("has")) return "false";
            if (key.equals("members") || key.equals("online")) return "0";
            return none;
        }
        String col = "&" + g.getColor();
        if (key.equals("name")) return g.getName();
        if (key.equals("name_colored")) return Msg.color(col + g.getName());
        if (key.equals("rank")) return g.getRank(p.getUniqueId());
        if (key.equals("color")) return Msg.color(col);
        if (key.equals("color_code")) return String.valueOf(g.getColor());
        if (key.equals("prefix")) return Msg.color(col + "[" + g.getName() + "]");
        if (key.equals("tab")) return plugin.getTabManager().format(p, g, g.getRank(p.getUniqueId()), g.getTabMode(), false);
        if (key.equals("master")) return g.getMemberName(g.getMaster());
        if (key.equals("members")) return String.valueOf(g.size());
        if (key.equals("has")) return "true";
        if (key.equals("online")) {
            int n = 0;
            for (UUID u : g.getMembers()) if (Bukkit.getPlayer(u) != null) n++;
            return String.valueOf(n);
        }
        return null;
    }
}
