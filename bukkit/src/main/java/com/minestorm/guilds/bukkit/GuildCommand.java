package com.minestorm.guilds.bukkit;

import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class GuildCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBS = Arrays.asList(
            "create","disband","invite","join","accept","chat","createrank","deleterank",
            "ranks","promote","demote","leave","kick","list","info","transfer","color","tab",
            "creator","help");

    private final MineStormGuilds plugin;
    private final GuildManager gm;
    private final Map<UUID, Long> disbandConfirm = new HashMap<UUID, Long>();

    public GuildCommand(MineStormGuilds plugin) {
        this.plugin = plugin;
        this.gm = plugin.getGuildManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) { sender.sendMessage(plugin.getMessages().raw("player-only")); return true; }
        Player p = (Player) sender;

        if (cmd.getName().equalsIgnoreCase("gc")) { chat(p, args, 0); return true; }

        if (args.length == 0) { help(p); return true; }

        String sub = args[0].toLowerCase();
        if (sub.equals("create")) create(p, args);
        else if (sub.equals("disband")) disband(p);
        else if (sub.equals("invite")) invite(p, args);
        else if (sub.equals("join")) join(p, args);
        else if (sub.equals("accept")) accept(p, args);
        else if (sub.equals("chat") || sub.equals("c")) chat(p, args, 1);
        else if (sub.equals("createrank")) createRank(p, args);
        else if (sub.equals("deleterank")) deleteRank(p, args);
        else if (sub.equals("ranks")) ranks(p);
        else if (sub.equals("promote")) moveRank(p, args, true);
        else if (sub.equals("demote")) moveRank(p, args, false);
        else if (sub.equals("leave")) leave(p);
        else if (sub.equals("kick")) kick(p, args);
        else if (sub.equals("list") || sub.equals("members")) list(p);
        else if (sub.equals("info")) info(p);
        else if (sub.equals("transfer")) transfer(p, args);
        else if (sub.equals("color") || sub.equals("colour")) openGui(p, true);
        else if (sub.equals("tab")) openGui(p, false);
        else if (sub.equals("creator")) p.sendMessage(plugin.getMessages().raw("creator"));
        else if (sub.equals("help")) help(p);
        else plugin.getMessages().send(p, "unknown-command");
        return true;
    }

    private Guild need(Player p) {
        Guild g = gm.getGuild(p.getUniqueId());
        if (g == null) plugin.getMessages().send(p, "not-in-guild");
        return g;
    }

    private boolean canManage(Guild g, Player p) {
        UUID u = p.getUniqueId();
        return g.isMaster(u) || Guild.OFFICER.equals(g.getRank(u));
    }

    private long ttl() {
        return plugin.getConfig().getInt("settings.invite-expire-seconds", 60) * 1000L;
    }

    private void addToGuild(Guild g, Player p) {
        gm.clearInvites(p.getUniqueId());
        gm.clearRequests(p.getUniqueId());
        gm.addMember(g, p.getUniqueId(), p.getName());
        gm.save();
        plugin.getTabManager().apply(p);
        plugin.broadcast(g, plugin.getMessages().format("join-success", "player", p.getName()));
    }

    private void clickable(Player to, String text, String command, String hover) {
        TextComponent c = new TextComponent(Msg.color(text));
        c.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command));
        c.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new BaseComponent[]{ new TextComponent(Msg.color(hover)) }));
        to.spigot().sendMessage((BaseComponent) c);
    }

    private void help(Player p) {
        Messages m = plugin.getMessages();
        m.send(p, "admin-help-line", "args", "&f---");
        p.sendMessage(Msg.color("&b&m--------------- &f&lMineStorm &b&lGuilds &b&m---------------"));
        String[][] h = {
                { "create <name>", "Create a guild" },
                { "invite <player>", "Invite a player" },
                { "join <guild>", "Accept an invite / request" },
                { "accept <player>", "Accept a join request" },
                { "chat <message>", "Guild chat (no message = toggle)" },
                { "list", "Members and online status" },
                { "info", "Guild information" },
                { "ranks", "Show the rank hierarchy" },
                { "promote/demote <player>", "Change a member's rank (Master)" },
                { "createrank/deleterank <name>", "Manage custom ranks (Master)" },
                { "kick <player>", "Kick a member" },
                { "transfer <player>", "Give the guild to a member (Master)" },
                { "color", "Open guild color GUI (Master)" },
                { "tab", "Open tab settings GUI (Master)" },
                { "leave", "Leave your guild" },
                { "disband", "Disband your guild (Master)" },
                { "creator", "Show plugin author" }
        };
        for (String[] x : h) p.sendMessage(Msg.color("&b/g " + x[0] + " &7- &f" + x[1]));
        p.sendMessage(Msg.color("&b/gc <message> &7- &fSend a guild chat message"));
        p.sendMessage(Msg.color("&b&m------------------------------------------"));
    }

    private void create(Player p, String[] a) {
        Messages m = plugin.getMessages();
        if (!p.hasPermission("minestormguilds.create")) { m.send(p, "no-permission"); return; }
        if (a.length < 2) { m.send(p, "invalid-usage", "usage", "/g create <name>"); return; }
        if (gm.getGuild(p.getUniqueId()) != null) { m.send(p, "already-in-guild"); return; }
        int min = plugin.getConfig().getInt("settings.name-min", 3);
        int max = plugin.getConfig().getInt("settings.name-max", 16);
        String name = a[1];
        if (!name.matches("[A-Za-z0-9_]+") || name.length() < min || name.length() > max) {
            m.send(p, "name-invalid", "min", min, "max", max); return;
        }
        if (gm.exists(name)) { m.send(p, "name-taken"); return; }
        Guild g = gm.createGuild(name, p);
        gm.save();
        plugin.getTabManager().apply(p);
        m.send(p, "guild-created", "guild", "&b" + g.getName());
        m.send(p, "guild-created-hint");
    }

    private void disband(Player p) {
        Messages m = plugin.getMessages();
        Guild g = need(p); if (g == null) return;
        if (!g.isMaster(p.getUniqueId())) { m.send(p, "only-master"); return; }
        long now = System.currentTimeMillis();
        Long t = disbandConfirm.get(p.getUniqueId());
        if (t == null || now - t > 15000L) {
            disbandConfirm.put(p.getUniqueId(), now);
            m.send(p, "guild-disband-confirm");
            return;
        }
        disbandConfirm.remove(p.getUniqueId());
        plugin.broadcast(g, m.format("guild-disbanded", "guild", g.getName(), "player", p.getName()));
        List<UUID> members = new ArrayList<UUID>(g.getMembers());
        gm.disband(g);
        gm.save();
        for (UUID u : members) {
            plugin.getChatToggled().remove(u);
            Player pl = Bukkit.getPlayer(u);
            if (pl != null) plugin.getTabManager().remove(pl);
        }
    }

    private void invite(Player p, String[] a) {
        Messages m = plugin.getMessages();
        Guild g = need(p); if (g == null) return;
        if (!canManage(g, p)) { m.send(p, "only-manager"); return; }
        if (a.length < 2) { m.send(p, "invalid-usage", "usage", "/g invite <player>"); return; }
        Player t = Bukkit.getPlayerExact(a[1]);
        if (t == null) { m.send(p, "player-offline"); return; }
        if (t.equals(p)) { m.send(p, "cannot-invite-self"); return; }
        if (gm.getGuild(t.getUniqueId()) != null) { m.send(p, "player-already-in-guild"); return; }
        if (gm.hasInvite(t.getUniqueId(), g)) { m.send(p, "invite-already-pending"); return; }
        gm.addInvite(t.getUniqueId(), g, ttl());
        plugin.broadcast(g, m.format("invite-sent", "target", "&b" + t.getName()));
        m.send(t, "invite-received", "player", "&b" + p.getName(), "guild", "&b" + g.getName(),
                "seconds", ttl() / 1000L);
        clickable(t, m.raw("invite-click").replace("%guild%", g.getName()),
                "/g join " + g.getName(),
                m.raw("invite-hover").replace("%guild%", g.getName()));
    }

    private void join(Player p, String[] a) {
        Messages m = plugin.getMessages();
        if (a.length < 2) { m.send(p, "invalid-usage", "usage", "/g join <guild>"); return; }
        if (gm.getGuild(p.getUniqueId()) != null) { m.send(p, "already-in-guild"); return; }
        Guild g = gm.getGuildByName(a[1]);
        if (g == null) { m.send(p, "guild-not-found"); return; }
        if (gm.hasInvite(p.getUniqueId(), g)) { addToGuild(g, p); return; }
        if (gm.hasRequest(g, p.getUniqueId())) { m.send(p, "request-already"); return; }
        gm.addRequest(g, p.getUniqueId(), ttl());
        m.send(p, "request-sent", "guild", "&b" + g.getName());
        for (UUID u : g.getMembers()) {
            Player mm = Bukkit.getPlayer(u);
            if (mm == null || !canManage(g, mm)) continue;
            m.send(mm, "request-received", "player", "&b" + p.getName());
            clickable(mm, m.raw("request-click").replace("%player%", p.getName()),
                    "/g accept " + p.getName(),
                    m.raw("request-hover").replace("%player%", p.getName()));
        }
    }

    private void accept(Player p, String[] a) {
        Messages m = plugin.getMessages();
        Guild g = need(p); if (g == null) return;
        if (!canManage(g, p)) { m.send(p, "only-manager"); return; }
        if (a.length < 2) { m.send(p, "invalid-usage", "usage", "/g accept <player>"); return; }
        Player t = Bukkit.getPlayerExact(a[1]);
        if (t == null) { m.send(p, "player-offline"); return; }
        if (!gm.hasRequest(g, t.getUniqueId())) { m.send(p, "request-none"); return; }
        if (gm.getGuild(t.getUniqueId()) != null) { gm.clearRequests(t.getUniqueId()); m.send(p, "player-already-in-guild"); return; }
        addToGuild(g, t);
    }

    private void chat(Player p, String[] a, int from) {
        Messages m = plugin.getMessages();
        Guild g = need(p); if (g == null) return;
        if (a.length <= from) {
            Set<UUID> t = plugin.getChatToggled();
            if (t.remove(p.getUniqueId())) m.send(p, "chat-mode-off");
            else { t.add(p.getUniqueId()); m.send(p, "chat-mode-on"); }
            return;
        }
        plugin.sendGuildChat(p, Msg.join(a, from));
    }

    private void createRank(Player p, String[] a) {
        Messages m = plugin.getMessages();
        Guild g = need(p); if (g == null) return;
        if (!g.isMaster(p.getUniqueId())) { m.send(p, "only-master"); return; }
        if (a.length < 2) { m.send(p, "invalid-usage", "usage", "/g createrank <name>"); return; }
        String n = a[1];
        if (!n.matches("[A-Za-z0-9_]{1,16}")) { m.send(p, "rank-name-invalid"); return; }
        if (g.findRank(n) != null) { m.send(p, "rank-exists"); return; }
        if (g.getRanks().size() >= plugin.getConfig().getInt("settings.max-ranks", 10)) { m.send(p, "rank-max"); return; }
        g.addRank(n); gm.save();
        m.send(p, "rank-created", "rank", "&b" + n);
    }

    private void deleteRank(Player p, String[] a) {
        Messages m = plugin.getMessages();
        Guild g = need(p); if (g == null) return;
        if (!g.isMaster(p.getUniqueId())) { m.send(p, "only-master"); return; }
        if (a.length < 2) { m.send(p, "invalid-usage", "usage", "/g deleterank <name>"); return; }
        String r = g.findRank(a[1]);
        if (r == null) { m.send(p, "rank-not-found"); return; }
        if (Guild.isDefaultRank(r)) { m.send(p, "rank-default-cannot-delete"); return; }
        g.removeRank(r); gm.save();
        plugin.getTabManager().refreshGuild(g);
        plugin.broadcast(g, m.format("rank-deleted", "rank", "&b" + r));
    }

    private void ranks(Player p) {
        Messages m = plugin.getMessages();
        Guild g = need(p); if (g == null) return;
        p.sendMessage(Msg.color("&b&m------------------------------------------"));
        p.sendMessage(Msg.color("&bGuild ranks &7(highest -> lowest)"));
        p.sendMessage(Msg.color("&fGuild Master &7- &f" + countRank(g, "Guild Master") + " member(s)"));
        for (String r : g.getRanks())
            p.sendMessage(Msg.color("&b" + r + " &7- &f" + countRank(g, r) + " member(s)"));
        p.sendMessage(Msg.color("&b&m------------------------------------------"));
    }

    private int countRank(Guild g, String rank) {
        int n = 0;
        for (UUID u : g.getMembers()) if (g.getRank(u).equals(rank)) n++;
        return n;
    }

    private void moveRank(Player p, String[] a, boolean up) {
        Messages m = plugin.getMessages();
        Guild g = need(p); if (g == null) return;
        if (!g.isMaster(p.getUniqueId())) { m.send(p, "only-master"); return; }
        if (a.length < 2) { m.send(p, "invalid-usage", "usage", "/g " + (up ? "promote" : "demote") + " <player>"); return; }
        UUID t = g.findMember(a[1]);
        if (t == null) { m.send(p, "target-not-member"); return; }
        if (t.equals(p.getUniqueId())) { m.send(p, "cannot-change-own-rank"); return; }
        List<String> ranks = g.getRanks();
        int idx = g.rankIndex(t);
        int ni = up ? idx - 1 : idx + 1;
        String name = g.getMemberName(t);
        if (up && ni < 0) { m.send(p, "promote-highest", "player", name); return; }
        if (!up && ni >= ranks.size()) { m.send(p, "demote-lowest", "player", name); return; }
        String old = g.getRank(t);
        String now = ranks.get(ni);
        g.setRank(t, now);
        gm.save();
        Player online = Bukkit.getPlayer(t);
        if (online != null) plugin.getTabManager().apply(online);
        plugin.broadcast(g, m.format(up ? "promote-success" : "demote-success",
                "player", name, "old", "&b" + old, "new", "&b" + now));
    }

    private void leave(Player p) {
        Messages m = plugin.getMessages();
        Guild g = need(p); if (g == null) return;
        if (g.isMaster(p.getUniqueId())) { m.send(p, "master-cannot-leave"); return; }
        gm.removeMember(g, p.getUniqueId());
        gm.save();
        plugin.getTabManager().remove(p);
        plugin.getChatToggled().remove(p.getUniqueId());
        m.send(p, "leave-success", "guild", "&b" + g.getName());
        plugin.broadcast(g, m.format("leave-broadcast", "player", p.getName()));
    }

    private void kick(Player p, String[] a) {
        Messages m = plugin.getMessages();
        Guild g = need(p); if (g == null) return;
        if (!canManage(g, p)) { m.send(p, "only-manager"); return; }
        if (a.length < 2) { m.send(p, "invalid-usage", "usage", "/g kick <player>"); return; }
        UUID t = g.findMember(a[1]);
        if (t == null) { m.send(p, "target-not-member"); return; }
        if (t.equals(p.getUniqueId())) { m.send(p, "kick-self"); return; }
        if (g.isMaster(t)) { m.send(p, "kick-master"); return; }
        if (!g.isMaster(p.getUniqueId()) && g.rankIndex(t) <= g.rankIndex(p.getUniqueId())) {
            m.send(p, "kick-lower-only"); return;
        }
        String tn = g.getMemberName(t);
        plugin.broadcast(g, m.format("kick-success", "target", "&b" + tn, "player", "&b" + p.getName()));
        gm.removeMember(g, t);
        gm.save();
        plugin.getChatToggled().remove(t);
        Player online = Bukkit.getPlayer(t);
        if (online != null) plugin.getTabManager().remove(online);
    }

    private void list(Player p) {
        Guild g = need(p); if (g == null) return;
        p.sendMessage(Msg.color("&b&m------------------------------------------"));
        p.sendMessage(Msg.color("&" + g.getColor() + g.getName() + " &b- Members (&f" + g.size() + "&b)"));
        List<String> order = new ArrayList<String>();
        order.add("Guild Master");
        order.addAll(g.getRanks());
        for (String rank : order) {
            StringBuilder sb = new StringBuilder();
            for (UUID u : g.getMembers()) {
                if (!g.getRank(u).equals(rank)) continue;
                boolean on = Bukkit.getPlayer(u) != null;
                sb.append(on ? "&f" : "&7").append(g.getMemberName(u)).append(on ? " &b " : " &c ");
            }
            if (sb.length() == 0) continue;
            p.sendMessage("");
            p.sendMessage(Msg.color("&b-- " + rank + " --"));
            p.sendMessage(Msg.color(sb.toString()));
        }
        p.sendMessage(Msg.color("&b&m------------------------------------------"));
    }

    private void info(Player p) {
        Messages m = plugin.getMessages();
        Guild g = need(p); if (g == null) return;
        int online = 0;
        for (UUID u : g.getMembers()) if (Bukkit.getPlayer(u) != null) online++;
        p.sendMessage(Msg.color(m.raw("info-header").replace("%guild%", g.getColor() + g.getName())));
        p.sendMessage(Msg.color(m.raw("info-master").replace("%master%", g.getMemberName(g.getMaster()))));
        p.sendMessage(Msg.color(m.raw("info-members").replace("%count%", String.valueOf(g.size())).replace("%online%", String.valueOf(online))));
        p.sendMessage(Msg.color(m.raw("info-your-rank").replace("%rank%", g.getRank(p.getUniqueId()))));
        p.sendMessage(Msg.color(m.raw("info-color").replace("%code%", "&" + g.getColor()).replace("%name%", GuiManager.colorName(g.getColor()))));
        p.sendMessage(Msg.color(m.raw("info-tab").replace("%mode%", g.getTabMode().getDisplay())));
        p.sendMessage(Msg.color(m.raw("info-ranks").replace("%ranks%", "Guild Master, " + joinList(g.getRanks()))));
        p.sendMessage(Msg.color(m.raw("info-created").replace("%date",
                new SimpleDateFormat("yyyy-MM-dd").format(new Date(g.getCreated())))));
        p.sendMessage(Msg.color("&b&m------------------------------------------"));
    }

    private String joinList(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(l.get(i));
        }
        return sb.toString();
    }

    private void transfer(Player p, String[] a) {
        Messages m = plugin.getMessages();
        Guild g = need(p); if (g == null) return;
        if (!g.isMaster(p.getUniqueId())) { m.send(p, "only-master"); return; }
        if (a.length < 2) { m.send(p, "invalid-usage", "usage", "/g transfer <player>"); return; }
        UUID t = g.findMember(a[1]);
        if (t == null) { m.send(p, "target-not-member"); return; }
        if (t.equals(p.getUniqueId())) { m.send(p, "transfer-self"); return; }
        g.transferMaster(t);
        gm.save();
        plugin.getTabManager().refreshGuild(g);
        plugin.broadcast(g, m.format("transfer-success", "player", "&b" + p.getName(),
                "target", "&b" + g.getMemberName(t)));
    }

    private void openGui(Player p, boolean color) {
        Messages m = plugin.getMessages();
        Guild g = need(p); if (g == null) return;
        String perm = color ? "minestormguilds.color" : "minestormguilds.tab";
        if (!p.hasPermission(perm)) { m.send(p, "no-permission"); return; }
        if (!g.isMaster(p.getUniqueId())) {
            m.send(p, color ? "only-master-color" : "only-master-tab");
            return;
        }
        if (color) plugin.getGuiManager().openColor(p, g);
        else plugin.getGuiManager().openTab(p, g);
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        if (!(s instanceof Player) || c.getName().equalsIgnoreCase("gc")) return Collections.emptyList();
        Player p = (Player) s;
        Guild g = gm.getGuild(p.getUniqueId());
        if (a.length == 1) return filter(SUBS, a[0]);
        if (a.length == 2) {
            List<String> pool = new ArrayList<String>();
            String sub = a[0].toLowerCase();
            if (sub.equals("invite") || sub.equals("accept")) {
                for (Player o : Bukkit.getOnlinePlayers()) pool.add(o.getName());
            } else if (sub.equals("join")) {
                for (Guild x : gm.all()) pool.add(x.getName());
            } else if (sub.equals("promote") || sub.equals("demote") || sub.equals("kick") || sub.equals("transfer")) {
                if (g != null) for (UUID u : g.getMembers()) pool.add(g.getMemberName(u));
            } else if (sub.equals("deleterank")) {
                if (g != null) for (String r : g.getRanks()) if (!Guild.isDefaultRank(r)) pool.add(r);
            }
            return filter(pool, a[1]);
        }
        return Collections.emptyList();
    }

    private List<String> filter(List<String> src, String start) {
        List<String> out = new ArrayList<String>();
        for (String x : src) if (x.toLowerCase().startsWith(start.toLowerCase())) out.add(x);
        return out;
    }
}
