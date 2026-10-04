package com.minestorm.guilds.bukkit;

import com.minestorm.guilds.common.TabMode;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TabManager {

    private final MineStormGuilds plugin;
    private final Map<UUID, String> teams = new HashMap<UUID, String>();

    public TabManager(MineStormGuilds plugin) { this.plugin = plugin; }

    private Scoreboard board() {
        ScoreboardManager m = Bukkit.getScoreboardManager();
        return m == null ? null : m.getMainScoreboard();
    }

    private String teamName(Player p, Guild g) {
        int hash = g.getName().toLowerCase().hashCode() & 0xFFFFFF;
        int idx = Math.min(g.rankIndex(p.getUniqueId()) + 1, 99);
        String u = p.getUniqueId().toString().replace("-", "").substring(0, 5);
        return "gld" + String.format("%06x", hash) + String.format("%02d", idx) + u;
    }

    public String format(Player p, Guild g, String rank, TabMode mode, boolean usePapi) {
        if (mode == TabMode.NONE) return "";
        String raw = plugin.getConfig().getString("tab.formats." + mode.name(), mode.getDefaultFormat());
        String n = g.getName();
        String r = rank;
        String out = build(p, raw, g, n, r, usePapi);
        while (out.length() > 16 && (n.length() > 1 || r.length() > 1)) {
            if (n.length() >= r.length()) n = n.substring(0, n.length() - 1);
            else r = r.substring(0, r.length() - 1);
            out = build(p, raw, g, n, r, usePapi);
        }
        if (out.length() > 16) out = out.substring(0, 16);
        return out;
    }

    private String build(Player p, String raw, Guild g, String name, String rank, boolean usePapi) {
        String s = raw.replace("%guild_name%", name)
                .replace("%guild_rank%", rank)
                .replace("%guild_color%", "&" + g.getColor());
        if (usePapi && plugin.hasPapi() && p != null) {
            try { s = PapiHook.apply(p, s); } catch (Throwable ignored) {}
        }
        return Msg.color(s);
    }

    public void apply(Player p) {
        Scoreboard sb = board();
        if (sb == null) return;
        Guild g = plugin.getGuildManager().getGuild(p.getUniqueId());
        if (g == null || !plugin.getConfig().getBoolean("tab.enabled", true)) { remove(p); return; }
        String prefix = format(p, g, g.getRank(p.getUniqueId()), g.getTabMode(), true);
        if (prefix.isEmpty()) { remove(p); return; }
        String name = teamName(p, g);
        String old = teams.get(p.getUniqueId());
        if (old != null && !old.equals(name)) unregister(sb, old);
        Team t = sb.getTeam(name);
        if (t == null) t = sb.registerNewTeam(name);
        t.setPrefix(prefix);
        t.addPlayer((OfflinePlayer) p);
        teams.put(p.getUniqueId(), name);
    }

    public void refreshGuild(Guild g) {
        for (UUID u : g.getMembers()) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) apply(p);
        }
    }

    public void remove(Player p) {
        Scoreboard sb = board();
        if (sb == null) return;
        String old = teams.remove(p.getUniqueId());
        if (old != null) unregister(sb, old);
    }

    private void unregister(Scoreboard sb, String name) {
        try { Team t = sb.getTeam(name); if (t != null) t.unregister(); }
        catch (IllegalStateException ignored) {}
    }

    public void purgeStale() {
        Scoreboard sb = board();
        if (sb == null) return;
        for (Team t : new ArrayList<Team>(sb.getTeams())) {
            if (t.getName().length() == 16 && t.getName().startsWith("gld")) {
                try { t.unregister(); } catch (IllegalStateException ignored) {}
            }
        }
        teams.clear();
    }
}
