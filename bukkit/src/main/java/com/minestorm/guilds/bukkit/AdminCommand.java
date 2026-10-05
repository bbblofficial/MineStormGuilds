package com.minestorm.guilds.bukkit;

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
 * OP players (and console) bypass ALL permission checks.
 * Non-OP players need minestormguilds.admin or the granular node of the action.
 * (plugin.yml intentionally has no permission on the command itself, otherwise Bukkit would
 *  block users that only own a granular node.)
 */
public class AdminCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = Arrays.asList("help", "reload", "save", "guild", "player");

    private static final String[] NODES = {
            "minestormguilds.admin.reload",
            "minestormguilds.admin.guild.create",
            "minestormguilds.admin.guild.delete",
            "minestormguilds.admin.guild.color",
            "minestormguilds.admin.guild.member.add",
            "minestormguilds.admin.guild.member.remove",
            "minestormguilds.admin.player.info",
            "minestormguilds.admin.player.remove"
    };

    private final MineStormGuilds plugin;

    public AdminCommand(MineStormGuilds plugin) { this.plugin = plugin; }

    private boolean has(CommandSender s, String node) {
        return s.isOp() || s.hasPermission(node) || s.hasPermission("minestormguilds.admin");
    }

    private boolean hasAny(CommandSender s) {
        if (s.isOp() || s.hasPermission("minestormguilds.admin")) return true;
        for (String n : NODES) if (s.hasPermission(n)) return true;
        return false;
    }

    /**
     * Console, OP, minestormguilds.admin and minestormguilds.bypass.limits
     * ignore every min/max limit (guild name length, members, ranks).
     */
    private boolean bypassLimits(CommandSender s) {
        if (!(s instanceof Player)) return true; // console
        Player p = (Player) s;
        if (plugin.getConfig().getBoolean("settings.bypass-limits-for-op", true) && p.isOp()) return true;
        if (p.hasPermission("minestormguilds.admin")) return true;
        String perm = plugin.getConfig().getString("settings.bypass-limits-permission", "minestormguilds.bypass.limits");
        return perm != null && !perm.isEmpty() && p.hasPermission(perm);
    }

    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] args) {
        Messages m = plugin.getMessages();
        if (!hasAny(s)) { m.send(s, "no-permission"); return true; }
        if (args.length == 0) { help(s); return true; }

        String sub = args[0].toLowerCase();

        if (sub.equals("help")) { help(s); return true; }

        if (sub.equals("reload")) {
            if (!has(s, "minestormguilds.admin.reload")) { m.send(s, "no-permission"); return true; }
            plugin.reloadAll();
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
        if (args.length < 2) {
            m.send(s, "invalid-usage", "usage", "/msga guild <create|delete|color|tab|addmember|removemember> ...");
            return true;
        }
        String op = args[1].toLowerCase();

        if (op.equals("create")) {
            if (!has(s, "minestormguilds.admin.guild.create")) { m.send(s, "no-permission"); return true; }
            if (args.length < 4) { m.send(s, "invalid-usage", "usage", "/msga guild create <name> <owner>"); return true; }
            int min = plugin.getConfig().getInt("settings.name-min", 3);
            int max = plugin.getConfig().getInt("settings.name-max", 16);
            String name = args[2];
            if (!name.matches("[A-Za-z0-9_]+") || name.length() < min || name.length() > max) {
                m.send(s, "name-invalid", "min", min, "max", max);
                return true;
            }
            Player owner = Bukkit.getPlayerExact(args[3]);
            if (owner == null) { m.send(s, "player-offline"); return true; }
            if (gm.getGuild(owner.getUniqueId()) != null) { m.send(s, "player-already-in-guild"); return true; }
            if (gm.exists(name)) { m.send(s, "name-taken"); return true; }
            Guild g = gm.createGuild(name, owner);
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
            String gname = g.getName();
            for (UUID u : new ArrayList<UUID>(g.getMembers())) {
                plugin.getChatToggled().remove(u);
                Player p = Bukkit.getPlayer(u);
                if (p != null) plugin.getTabManager().remove(p);
            }
            gm.disband(g);
            gm.save();
            m.send(s, "admin-guild-deleted", "guild", "&b" + gname);
            return true;
        }

        if (op.equals("color")) {
            if (!has(s, "minestormguilds.admin.guild.color")) { m.send(s, "no-permission"); return true; }
            if (args.length < 4) { m.send(s, "invalid-usage", "usage", "/msga guild color <name> <0-9a-f>"); return true; }
            Guild g = gm.getGuildByName(args[2]);
            if (g == null) { m.send(s, "admin-guild-not-found", "guild", args[2]); return true; }
            if (args[3].length() != 1 || !Msg.isColorCode(args[3].charAt(0))) {
                m.send(s, "invalid-color");
                return true;
            }
            char c = Character.toLowerCase(args[3].charAt(0));
            g.setColor(c);
            gm.save();
            plugin.getTabManager().refreshGuild(g);
            m.send(s, "admin-color-set", "guild", "&b" + g.getName(),
                    "code", "&" + c, "name", GuiManager.colorName(c));
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
            gm.clearInvites(t.getUniqueId());
            gm.clearRequests(t.getUniqueId());
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
            if (g.isMaster(t)) { m.send(s, "admin-cannot-remove-master"); return true; }
            gm.removeMember(g, t);
            gm.save();
            plugin.getChatToggled().remove(t);
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
        if (t != null) {
            uuid = t.getUniqueId();
        } else {
            // fallback: search guilds for an (offline) member by name
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
            if (g.isMaster(uuid)) { m.send(s, "admin-cannot-remove-master"); return true; }
            gm.removeMember(g, uuid);
            gm.save();
            plugin.getChatToggled().remove(uuid);
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
        s.sendMessage(Msg.color("&7OP players bypass all permissions. Others need &bminestormguilds.admin &7or a granular &bminestormguilds.admin.* &7node."));
        s.sendMessage(Msg.color("&b&m------------------------------------------"));
    }


    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (!hasAny(s)) return Collections.emptyList();
        if (a.length == 1) return filter(SUBS, a[0]);
        if (a.length == 2 && a[0].equalsIgnoreCase("guild"))
            return filter(Arrays.asList("create", "delete", "color", "tab", "addmember", "removemember"), a[1]);
        if (a.length == 2 && a[0].equalsIgnoreCase("player"))
            return filter(Arrays.asList("info", "remove"), a[1]);
        if (a.length == 3 && a[0].equalsIgnoreCase("guild") && !a[1].equalsIgnoreCase("create")) {
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
                return filter(Arrays.asList("NAME", "RANK", "NAME_RANK", "NONE"), a[3]);
            if (a[1].equalsIgnoreCase("color"))
                return filter(Arrays.asList("0", "1", "2", "3", "4", "5", "6", "7", "8", "9",
                        "a", "b", "c", "d", "e", "f"), a[3]);
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
