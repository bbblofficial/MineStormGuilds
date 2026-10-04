package com.minestorm.guilds.bukkit;

import com.minestorm.guilds.common.TabMode;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.entity.Player;

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
        g.addMember(u, name, "Member");
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

    // ------- SQLite load/save -------
    public void load() {
        guilds.clear();
        playerGuild.clear();
        try {
            Statement st = db.connection().createStatement();
            ResultSet rs = st.executeQuery("SELECT id, name, master_uuid, color, tab_mode, created FROM guilds");
            Map<Integer, Guild> byId = new HashMap<Integer, Guild>();
            while (rs.next()) {
                int id = rs.getInt("id");
                String name = rs.getString("name");
                UUID master = UUID.fromString(rs.getString("master_uuid"));
                String col = rs.getString("color");
                TabMode mode;
                try { mode = TabMode.valueOf(rs.getString("tab_mode").toUpperCase()); }
                catch (Exception ex) { mode = TabMode.NAME; }
                Guild g = new Guild(name, master, "Unknown", rs.getLong("created"));
                if (col != null && !col.isEmpty()) g.setColor(col.charAt(0));
                g.setTabMode(mode);
                byId.put(id, g);
                guilds.put(key(name), g);
            }
            rs.close();
            for (Map.Entry<Integer, Guild> e : byId.entrySet()) {
                Guild g = e.getValue();
                PreparedStatement ps = db.prep("SELECT uuid, name, rank FROM members WHERE guild_id = ?");
                ps.setInt(1, e.getKey());
                ResultSet mr = ps.executeQuery();
                while (mr.next()) {
                    UUID u = UUID.fromString(mr.getString("uuid"));
                    g.addMember(u, mr.getString("name"), mr.getString("rank"));
                    playerGuild.put(u, key(g.getName()));
                }
                mr.close(); ps.close();

                PreparedStatement rk = db.prep("SELECT rank FROM ranks WHERE guild_id = ? ORDER BY position ASC");
                rk.setInt(1, e.getKey());
                ResultSet rr = rk.executeQuery();
                List<String> customRanks = new ArrayList<String>();
                while (rr.next()) customRanks.add(rr.getString("rank"));
                rr.close(); rk.close();
                if (!customRanks.isEmpty()) g.setRanks(customRanks);
            }
            st.close();
        } catch (Exception ex) {
            plugin.getLogger().severe("Load failed: " + ex.getMessage());
        }
        plugin.getLogger().info("Loaded " + guilds.size() + " guild(s) from SQLite.");
    }

    public void save() {
        try {
            db.connection().setAutoCommit(false);
            Statement st = db.connection().createStatement();
            st.executeUpdate("DELETE FROM members");
            st.executeUpdate("DELETE FROM ranks");
            st.executeUpdate("DELETE FROM guilds");
            st.close();

            for (Guild g : guilds.values()) {
                PreparedStatement ps = db.prep("INSERT INTO guilds(name, master_uuid, color, tab_mode, created) VALUES(?,?,?,?,?)");
                ps.setString(1, g.getName());
                ps.setString(2, g.getMaster().toString());
                ps.setString(3, String.valueOf(g.getColor()));
                ps.setString(4, g.getTabMode().name());
                ps.setLong(5, g.getCreated());
                ps.executeUpdate();
                ResultSet keys = ps.getGeneratedKeys();
                int gid = keys.next() ? keys.getInt(1) : 0;
                keys.close(); ps.close();
                if (gid == 0) continue;

                PreparedStatement mp = db.prep("INSERT INTO members(guild_id, uuid, name, rank, joined) VALUES(?,?,?,?,?)");
                for (UUID u : g.getMembers()) {
                    mp.setInt(1, gid);
                    mp.setString(2, u.toString());
                    mp.setString(3, g.getMemberName(u));
                    mp.setString(4, g.getRank(u));
                    mp.setLong(5, System.currentTimeMillis());
                    mp.addBatch();
                }
                mp.executeBatch(); mp.close();

                PreparedStatement rp = db.prep("INSERT INTO ranks(guild_id, rank, position) VALUES(?,?,?)");
                int pos = 0;
                for (String r : g.getRanks()) {
                    rp.setInt(1, gid);
                    rp.setString(2, r);
                    rp.setInt(3, pos++);
                    rp.addBatch();
                }
                rp.executeBatch(); rp.close();
            }
            db.connection().commit();
            db.connection().setAutoCommit(true);
        } catch (Exception ex) {
            plugin.getLogger().severe("Save failed: " + ex.getMessage());
        }
    }
}
