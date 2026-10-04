package com.minestorm.guilds.bukkit;

import com.minestorm.guilds.common.TabMode;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Admin command /msga
 * OP players bypass ALL permission checks.
 * Non-OP players need the granular permission under minestormguilds.admin.*
 */
public class AdminCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = Arrays.asList(
            "help","reload","save","guild","player");

    private final MineStormGuilds plugin;

    public AdminCommand(MineStormGuilds plugin) { this.plugin = plugin; }

    /** OP bypass — this is the key requirement. */
    private boolean has(CommandSender s, String node) {
        return s.isOp() || s.hasPermission(node) || s.hasPermission("minestormguilds.admin");
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        Messages m = plugin.getMessages();

        if (args.length == 0) { help(s); return true; }

        String sub = args[0].toLowerCase();

        if (sub.equals("help")) { help(s); return true; }

        if (sub.equals("reload")) {
            if (!has(s, "minestormguilds.admin.reload")) { m.send(s, "no-permission"); return true; }
            plugin.reloadConfig();
            m.load();
            m.send(s, "admin-reload");
            return true;
        }

        if (sub.equals("save")) {
            if (!has(s, "minestormguilds.admin.reload")) { m.send(s, "no-permission"); return true; }
            plugin.getGuildManager().save();
            m.send(s, "data-saved");
            return true;
        }

        if (sub.equals("guild")) return guildSub(s, args);
        if (sub.equals("player")) return playerSub(s, args);

        m.send(s, "unknown-command");
        return true;
    }

    private boolean guildSub(CommandSender s, String[] args) {
        Messages m = plugin.getMessages();
        GuildManager gm = plugin.getGuildManager();
        if (args.length < 2) { m.send(s, "invalid-usage", "usage", "/msga guild <create|delete|color|tab|addmember|removemember> ..."); return true; }
        String op = args[1].toLowerCase();

        if (op.equals("create")) {
            if (!has(s, "minestormguilds.admin.guild.create")) { m.send(s, "no-permission"); return true; }
            if (args.length < 4) { m.send(s, "invalid-usage", "usage", "/msga guild create <name> <owner>"); return true; }
            Player owner = Bukkit.getPlayerExact(args[3]);
            if (owner == null) { m.send(s, "player-offline"); return true; }
            if (gm.exists(args[2])) { m.send(s, "name-taken"); return true; }
            Guild g = gm.createGuild(args[2], owner);
            gm.save();
            plugin.getTabManager().apply(owner);
            m.send(s, "admin-guild-created", "guild", "&b" + g.getName(), "target", "&b" + owner.getName());
            return true;
        }

        if (op.equals("delete")) {
            if (!has(s, "minestormguilds.admin.guild.delete")) { m.send(s, "no-permission"); return true; }
            if (args.length < 3) { m.send(s, "invalid-usage", "usage", "/msga guild delete <name>"); return true; }
            Guild g = gm.getGuildByName(args[2]);
            if (g == null) { m.send(s, "admin-guild-not-found", "guild", args[2]); return true; }
            for (UUID u : new ArrayList<UUID>(g.getMembers())) {
                Player p = Bukkit.getPlayer(u);
                if (p != null) plugin.getTabManager().remove(p);
            }
            gm.disband(g);
            gm.save();
            m.send(s, "admin-guild-deleted", "guild", "&b" + args[2]);
            return true;
        }

        if (op.equals("color")) {
            if (!has(s, "minestormguilds.admin.guild.color")) { m.send(s, "no-permission"); return true; }
            if (args.length < 4) { m.send(s, "invalid-usage", "usage", "/msga guild color <name> <0-9a-f>"); return true; }
            Guild g = gm.getGuildByName(args[2]);
            if (g == null) { m.send(s, "admin-guild-not-found", "guild", args[2]); return true; }
            char c = args[3].toLowerCase().charAt(0);
            g.setColor(c);
            gm.save();
            plugin.getTabManager().refreshGuild(g);
            m.send(s, "admin-color-set", "guild", "&b" + g.getName(),
                    "code", "&" + c, "name", GuiManager.colorName(c));
            return true;
        }

        if (op.equals("tab")) {
            if (!has(s, "minestormguilds.admin.guild.tab")) { m.send(s, "no-permission"); return true; }
            if (args.length < 4) { m.send(s, "invalid-usage", "usage", "/msga guild tab <name> <" + join(TabMode.values()) + ">"); return true; }
            Guild g = gm.getGuildByName(args[2]);
            if (g == null) { m.send(s, "admin-guild-not-found", "guild", args[2]); return true; }
            try {
                TabMode mode = TabMode.valueOf(args[3].toUpperCase());
                g.setTabMode(mode);
                gm.save();
                plugin.getTabManager().refreshGuild(g);
                m.send(s, "admin-tab-set", "guild", "&b" + g.getName(), "mode", mode.getDisplay());
            } catch (IllegalArgumentException ex) {
                m.send(s, "invalid-usage", "usage", "TabMode: " + join(TabMode.values()));
            }
            return true;
        }

        if (op.equals("addmember")) {
            if (!has(s, "minestormguilds.admin.guild.member.add")) { m.send(s, "no-permission"); return true; }
            if (args.length < 4) { m.send(s, "invalid-usage", "usage", "/msga guild addmember <guild> <player>"); return true; }
            Guild g = gm.getGuildByName(args[2]);
            if (g == null) { m.send(s, "admin-guild-not-found", "guild", args[2]); return true; }
            Player t = Bukkit.getPlayerExact(args[3]);
            if (t == null) { m.send(s, "player-offline"); return true; }
            if (gm.getGuild(t.getUniqueId()) != null) { m.send(s, "player-already-in-guild"); return true; }
            gm.addMember(g, t.getUniqueId(), t.getName());
            gm.save();
            plugin.getTabManager().apply(t);
            m.send(s, "admin-member-added", "target", "&b" + t.getName(), "guild", "&b" + g.getName());
            return true;
        }

        if (op.equals("removemember")) {
            if (!has(s, "minestormguilds.admin.guild.member.remove")) { m.send(s, "no-permission"); return true; }
            if (args.length < 4) { m.send(s, "invalid-usage", "usage", "/msga guild removemember <guild> <player>"); return true; }
            Guild g = gm.getGuildByName(args[2]);
            if (g == null) { m.send(s, "admin-guild-not-found", "guild", args[2]); return true; }
            UUID t = g.findMember(args[3]);
            if (t == null) { m.send(s, "target-not-member"); return true; }
            gm.removeMember(g, t);
            gm.save();
            Player online = Bukkit.getPlayer(t);
            if (online != null) plugin.getTabManager().remove(online);
            m.send(s, "admin-member-removed", "target", "&b" + args[3], "guild", "&b" + g.getName());
            return true;
        }

        m.send(s, "unknown-command");
        return true;
    }

    private boolean playerSub(CommandSender s, String[] args) {
        Messages m = plugin.getMessages();
        GuildManager gm = plugin.getGuildManager();
        if (args.length < 3) { m.send(s, "invalid-usage", "usage", "/msga player <info|remove> <player>"); return true; }
        String op = args[1].toLowerCase();
        Player t = Bukkit.getPlayerExact(args[2]);
        UUID uuid = null;
        String name = args[2];
        if (t != null) uuid = t.getUniqueId();
        else {
            // fallback: search guilds for member name
            for (Guild g : gm.all()) {
                UUID u = g.findMember(name);
                if (u != null) { uuid = u; break; }
            }
        }
        if (uuid == null) { m.send(s, "player-offline"); return true; }

        if (op.equals("info")) {
            if (!has(s, "minestormguilds.admin.player.info")) { m.send(s, "no-permission"); return true; }
            Guild g = gm.getGuild(uuid);
            if (g == null) { m.send(s, "admin-player-noguild", "player", name); return true; }
            m.send(s, "admin-player-info", "player", name, "guild", "&b" + g.getName(), "rank", g.getRank(uuid));
            return true;
        }

        if (op.equals("remove")) {
            if (!has(s, "minestormguilds.admin.player.remove")) { m.send(s, "no-permission"); return true; }
            Guild g = gm.getGuild(uuid);
            if (g == null) { m.send(s, "admin-player-noguild", "player", name); return true; }
            gm.removeMember(g, uuid);
            gm.save();
            if (t != null) plugin.getTabManager().remove(t);
            m.send(s, "admin-player-removed", "player", name);
            return true;
        }

        m.send(s, "unknown-command");
        return true;
    }

    private void help(CommandSender s) {
        s.sendMessage(Msg.color(plugin.getMessages().raw("admin-header")));
        String[][] lines = {
            { "reload", "Reload config + messages" },
            { "save", "Force save database" },
            { "guild create <name> <owner>", "Create a guild for a player" },
            { "guild delete <name>", "Delete a guild" },
            { "guild color <name> <0-9a-f>", "Set guild color" },
            { "guild tab <name> <NAME|RANK|NAME_RANK|NONE>", "Set tab display" },
            { "guild addmember <guild> <player>", "Add a member" },
            { "guild removemember <guild> <player>", "Remove a member" },
            { "player info <player>", "Show a player's guild" },
            { "player remove <player>", "Remove a player from their guild" }
        };
        for (String[] l : lines)
            s.sendMessage(Msg.color("&b/msga " + l[0] + " &7- &f" + l[1]));
        s.sendMessage(Msg.color("&7OP players bypass all permissions. Others need &bminestormguilds.admin &7or granular &bminestormguilds.admin.*"));
        s.sendMessage(Msg.color("&b&m------------------------------------------"));
    }

    private String join(TabMode[] modes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < modes.length; i++) {
            if (i > 0) sb.append("|");
            sb.append(modes[i].name());
        }
        return sb.toString();
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (a.length == 1) return filter(SUBS, a[0]);
        if (a.length == 2 && a[0].equalsIgnoreCase("guild"))
            return filter(Arrays.asList("create","delete","color","tab","addmember","removemember"), a[1]);
        if (a.length == 2 && a[0].equalsIgnoreCase("player"))
            return filter(Arrays.asList("info","remove"), a[1]);
        if (a.length == 3 && a[0].equalsIgnoreCase("guild")) {
            List<String> names = new ArrayList<String>();
            for (Guild g : plugin.getGuildManager().all()) names.add(g.getName());
            return filter(names, a[2]);
        }
        if (a.length == 3 && a[0].equalsIgnoreCase("player")) {
            List<String> ps = new ArrayList<String>();
            for (Player p : Bukkit.getOnlinePlayers()) ps.add(p.getName());
            return filter(ps, a[2]);
        }
        if (a.length == 4 && a[0].equalsIgnoreCase("guild")) {
            if (a[1].equalsIgnoreCase("tab"))
                return filter(Arrays.asList("NAME","RANK","NAME_RANK","NONE"), a[3]);
            if (a[1].equalsIgnoreCase("color"))
                return filter(Arrays.asList("0","1","2","3","4","5","6","7","8","9","a","b","c","d","e","f"), a[3]);
            List<String> ps = new ArrayList<String>();
            for (Player p : Bukkit.getOnlinePlayers()) ps.add(p.getName());
            return filter(ps, a[3]);
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> src, String start) {
        List<String> out = new ArrayList<String>();
        for (String x : src) if (x.toLowerCase().startsWith(start.toLowerCase())) out.add(x);
        return out;
    }
}
