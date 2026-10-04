package com.minestorm.guilds.bukkit;

import com.minestorm.guilds.common.TabMode;
import org.bukkit.entity.Player;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class GuildManager {

    private final MineStormGuilds plugin;
    private final Database db;

    private final Map<String, Guild> guilds = new LinkedHashMap<String, Guild>();
    private final Map<UUID, String> playerGuild = new HashMap<UUID, String>();
    private final Map<UUID, Map<String, Long>> invites = new HashMap<UUID, Map<String, Long>>();
    private final Map<String, Map<UUID, Long>> requests = new HashMap<String, Map<UUID, Long>>();

    public GuildManager(MineStormGuilds plugin) {
        this.plugin = plugin;
        this.db = new Database(plugin);
    }

    public Database getDatabase() { return db; }

    private static String key(String name) { return name.toLowerCase(); }

    public Guild getGuild(UUID player) {
        String k = playerGuild.get(player);
        return k == null ? null : guilds.get(k);
    }

    public Guild getGuildByName(String name) { return guilds.get(key(name)); }
    public boolean exists(String name) { return guilds.containsKey(key(name)); }
    public Collection<Guild> all() { return guilds.values(); }

    public Guild createGuild(String name, Player master) {
        Guild g = new Guild(name, master.getUniqueId(), master.getName(), System.currentTimeMillis());
        String def = plugin.getConfig().getString("settings.default-color", "a");
        if (def != null && !def.isEmpty()) g.setColor(def.charAt(0));
        guilds.put(key(name), g);
        playerGuild.put(master.getUniqueId(), key(name));
        return g;
    }

    public void disband(Guild g) {
        String k = key(g.getName());
        for (UUID u : g.getMembers()) playerGuild.remove(u);
        guilds.remove(k);
        requests.remove(k);
        for (Map<String, Long> m : invites.values()) m.remove(k);
    }

    public void addMember(Guild g, UUID u, String name) {
        g.addMember(u, name, Guild.MEMBER);
        playerGuild.put(u, key(g.getName()));
    }

    public void removeMember(Guild g, UUID u) {
        g.removeMember(u);
        playerGuild.remove(u);
    }

    // ------- invites -------
    public void addInvite(UUID target, Guild g, long ttlMs) {
        Map<String, Long> m = invites.get(target);
        if (m == null) { m = new HashMap<String, Long>(); invites.put(target, m); }
        m.put(key(g.getName()), System.currentTimeMillis() + ttlMs);
    }

    public boolean hasInvite(UUID target, Guild g) {
        Map<String, Long> m = invites.get(target);
        if (m == null) return false;
        Long exp = m.get(key(g.getName()));
        if (exp == null) return false;
        if (exp < System.currentTimeMillis()) { m.remove(key(g.getName())); return false; }
        return true;
    }

    public void clearInvites(UUID target) { invites.remove(target); }

    // ------- requests -------
    public void addRequest(Guild g, UUID requester, long ttlMs) {
        Map<UUID, Long> m = requests.get(key(g.getName()));
        if (m == null) { m = new HashMap<UUID, Long>(); requests.put(key(g.getName()), m); }
        m.put(requester, System.currentTimeMillis() + ttlMs);
    }

    public boolean hasRequest(Guild g, UUID requester) {
        Map<UUID, Long> m = requests.get(key(g.getName()));
        if (m == null) return false;
        Long exp = m.get(requester);
        if (exp == null) return false;
        if (exp < System.currentTimeMillis()) { m.remove(requester); return false; }
        return true;
    }

    public void clearRequests(UUID requester) {
        for (Map<UUID, Long> m : requests.values()) m.remove(requester);
    }

    // ------- load / save -------

    public synchronized void load() {
        guilds.clear();
        playerGuild.clear();

        Connection c = null;
        try {
            c = db.connection();
            Map<Long, Guild> byId = new LinkedHashMap<Long, Guild>();

            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT id, name, master_uuid, color, tab_mode, created FROM guilds")) {
                while (rs.next()) {
                    String name = rs.getString("name");
                    UUID master = UUID.fromString(rs.getString("master_uuid"));
                    TabMode mode;
                    try { mode = TabMode.valueOf(rs.getString("tab_mode").toUpperCase()); }
                    catch (Exception ex) { mode = TabMode.NAME; }
                    Guild g = new Guild(name, master, "Unknown", rs.getLong("created"));
                    String col = rs.getString("color");
                    if (col != null && !col.isEmpty()) g.setColor(col.charAt(0));
                    g.setTabMode(mode);
                    byId.put(rs.getLong("id"), g);
                    guilds.put(key(name), g);
                }
            }

            for (Map.Entry<Long, Guild> e : byId.entrySet()) {
                Guild g = e.getValue();

                try (PreparedStatement rk = c.prepareStatement(
                        "SELECT rank FROM ranks WHERE guild_id = ? ORDER BY position ASC")) {
                    rk.setLong(1, e.getKey());
                    try (ResultSet rr = rk.executeQuery()) {
                        List<String> custom = new ArrayList<String>();
                        while (rr.next()) custom.add(rr.getString("rank"));
                        if (!custom.isEmpty()) g.setRanks(custom);
                    }
                }

                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT uuid, name, rank FROM members WHERE guild_id = ?")) {
                    ps.setLong(1, e.getKey());
                    try (ResultSet mr = ps.executeQuery()) {
                        while (mr.next()) {
                            UUID u = UUID.fromString(mr.getString("uuid"));
                            String rank = mr.getString("rank");
                            if (u.equals(g.getMaster())) rank = Guild.MASTER_RANK;
                            g.addMember(u, mr.getString("name"), rank);
                            playerGuild.put(u, key(g.getName()));
                        }
                    }
                }
                playerGuild.put(g.getMaster(), key(g.getName()));
            }
        } catch (Exception ex) {
            plugin.getLogger().severe("Load failed: " + ex.getMessage());
        } finally {
            closeQuiet(c);
        }
    }

    public synchronized void save() {
        Connection c = null;
        try {
            c = db.connection();
            c.setAutoCommit(false);

            boolean mysql = db.getType() == Database.Type.MYSQL;

            String guildSql = mysql
                ? "INSERT INTO guilds(name, master_uuid, color, tab_mode, created) VALUES(?,?,?,?,?) " +
                  "ON DUPLICATE KEY UPDATE master_uuid=VALUES(master_uuid), color=VALUES(color), " +
                  "tab_mode=VALUES(tab_mode)"
                : "INSERT OR REPLACE INTO guilds(name, master_uuid, color, tab_mode, created) VALUES(?,?,?,?,?)";

            String memberSql = mysql
                ? "INSERT INTO members(guild_id, uuid, name, rank, joined) VALUES(?,?,?,?,?) " +
                  "ON DUPLICATE KEY UPDATE name=VALUES(name), rank=VALUES(rank)"
                : "INSERT OR REPLACE INTO members(guild_id, uuid, name, rank, joined) VALUES(?,?,?,?,?)";

            try (PreparedStatement gp = c.prepareStatement(guildSql, Statement.RETURN_GENERATED_KEYS);
                 PreparedStatement mp = c.prepareStatement(memberSql);
                 PreparedStatement rp = c.prepareStatement(
                     "INSERT INTO ranks(guild_id, rank, position) VALUES(?,?,?)")) {

                long now = System.currentTimeMillis();

                for (Guild g : guilds.values()) {
                    gp.setString(1, g.getName());
                    gp.setString(2, g.getMaster().toString());
                    gp.setString(3, String.valueOf(g.getColor()));
                    gp.setString(4, g.getTabMode().name());
                    gp.setLong(5, g.getCreated());
                    gp.executeUpdate();

                    long gid = 0;
                    try (ResultSet keys = gp.getGeneratedKeys()) {
                        if (keys.next()) gid = keys.getLong(1);
                    }
                    if (gid == 0) {
                        try (PreparedStatement q = c.prepareStatement("SELECT id FROM guilds WHERE name=?")) {
                            q.setString(1, g.getName());
                            try (ResultSet r = q.executeQuery()) {
                                if (r.next()) gid = r.getLong(1);
                            }
                        }
                    }
                    if (gid == 0) throw new SQLException("No id for guild " + g.getName());

                    for (UUID u : g.getMembers()) {
                        mp.setLong(1, gid);
                        mp.setString(2, u.toString());
                        mp.setString(3, g.getMemberName(u));
                        mp.setString(4, g.getRank(u));
                        mp.setLong(5, now);
                        mp.addBatch();
                    }
                    mp.executeBatch();

                    try (PreparedStatement del = c.prepareStatement("DELETE FROM ranks WHERE guild_id=?")) {
                        del.setLong(1, gid);
                        del.executeUpdate();
                    }
                    int pos = 0;
                    for (String r : g.getRanks()) {
                        rp.setLong(1, gid);
                        rp.setString(2, r);
                        rp.setInt(3, pos++);
                        rp.addBatch();
                    }
                    rp.executeBatch();
                }
            }

            c.commit();
        } catch (Exception ex) {
            plugin.getLogger().severe("Save failed: " + ex.getMessage());
            try { if (c != null) c.rollback(); } catch (SQLException ignored) {}
        } finally {
            if (c != null) {
                try { c.setAutoCommit(true); } catch (SQLException ignored) {}
                closeQuiet(c);
            }
        }
    }

    private static void closeQuiet(Connection c) {
        try { if (c != null) c.close(); } catch (SQLException ignored) {}
    }

    public void close() { db.close(); }
}
