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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Guild cache + persistence.
 *
 * How saving/loading works (this replaces the old "clear everything and re-read" logic):
 *
 *  - Every guild has a "saved state" = what we last wrote to / read from the database.
 *  - save() compares the live guilds with the saved state and only writes what really
 *    changed (new/edited guilds, removed members, disbanded guilds). It therefore also
 *    deletes disbanded guilds and kicked/left members from the database, and it never
 *    overwrites guilds that were not touched on this server.
 *  - The periodic refresh reads the database into a DETACHED copy (fetch()) on an async
 *    thread and the copy is swapped in on the main thread (applyIfClean()). The live data is
 *    never cleared, so a failing database can no longer wipe the guilds out of memory.
 */
public class GuildManager {

    public static final int REFRESH_SKIPPED = 0;   // unsaved local changes -> not applied
    public static final int REFRESH_UNCHANGED = 1; // database == memory
    public static final int REFRESH_APPLIED = 2;   // memory replaced by database contents

    private final MineStormGuilds plugin;
    private final Database db;

    private volatile Map<String, Guild> guilds = new LinkedHashMap<String, Guild>();
    private volatile Map<UUID, String> playerGuild = new HashMap<UUID, String>();
    private volatile Map<String, Saved> saved = new HashMap<String, Saved>();

    private final Map<UUID, Map<String, Long>> invites = new HashMap<UUID, Map<String, Long>>();
    private final Map<String, Map<UUID, Long>> requests = new HashMap<String, Map<UUID, Long>>();

    // ------------------------------------------------------------------ helper types

    /** What we last knew to be in the database for one guild. */
    static final class Saved {
        final String name;
        final String fp;
        final Set<UUID> members;
        Saved(String name, String fp, Set<UUID> members) {
            this.name = name;
            this.fp = fp;
            this.members = members;
        }
    }

    /** One guild that has to be written. */
    static final class Change {
        final Guild guild;
        final Set<UUID> removedMembers;
        Change(Guild guild, Set<UUID> removedMembers) {
            this.guild = guild;
            this.removedMembers = removedMembers;
        }
    }

    /** A complete, detached copy of the database contents. */
    static final class Loaded {
        final Map<String, Guild> guilds = new LinkedHashMap<String, Guild>();
        final Map<UUID, String> playerGuild = new HashMap<UUID, String>();
        final Map<String, Saved> saved = new HashMap<String, Saved>();
    }

    // ------------------------------------------------------------------ basics

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

    public synchronized Guild createGuild(String name, Player master) {
        Guild g = new Guild(name, master.getUniqueId(), master.getName(), System.currentTimeMillis());
        String def = plugin.getConfig().getString("settings.default-color", "a");
        if (def != null && !def.isEmpty()) g.setColor(def.charAt(0));
        guilds.put(key(name), g);
        playerGuild.put(master.getUniqueId(), key(name));
        return g;
    }

    public synchronized void disband(Guild g) {
        String k = key(g.getName());
        for (UUID u : g.getMembers()) playerGuild.remove(u);
        guilds.remove(k);
        requests.remove(k);
        for (Map<String, Long> m : invites.values()) m.remove(k);
    }

    public synchronized void addMember(Guild g, UUID u, String name) {
        g.addMember(u, name, Guild.MEMBER);
        playerGuild.put(u, key(g.getName()));
    }

    public synchronized void removeMember(Guild g, UUID u) {
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

    // ------------------------------------------------------------------ fingerprints

    /** A string that changes whenever anything persisted about the guild changes. */
    static String fingerprint(Guild g) {
        StringBuilder sb = new StringBuilder();
        sb.append(g.getName()).append('|')
          .append(g.getMaster()).append('|')
          .append(g.getColor()).append('|')
          .append(g.getTabMode() == null ? "" : g.getTabMode().name()).append('|');
        for (String r : g.getRanks()) sb.append(r).append(',');
        sb.append('|');
        TreeMap<String, String> members = new TreeMap<String, String>();
        for (UUID u : g.getMembers()) members.put(u.toString(), g.getMemberName(u) + ":" + g.getRank(u));
        for (Map.Entry<String, String> e : members.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append(';');
        }
        return sb.toString();
    }

    private static Saved snapshotOf(Guild g) {
        return new Saved(g.getName(), fingerprint(g), new HashSet<UUID>(g.getMembers()));
    }

    // ------------------------------------------------------------------ load (database -> memory)

    /** Startup load. Never clears memory when the database fails. */
    public synchronized void load() {
        try {
            swap(fetch());
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "Load failed: " + ex.getMessage(), ex);
        }
    }

    /**
     * Reads the whole database into a detached copy. Touches NO live data, so it is safe to call
     * from any thread. Package-private + non-final so tests can replace it.
     */
    Loaded fetch() throws SQLException {
        Loaded out = new Loaded();
        try (Connection c = db.connection()) {
            Map<Long, Guild> byId = new LinkedHashMap<Long, Guild>();

            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT id, name, master_uuid, color, tab_mode, created FROM guilds")) {
                while (rs.next()) {
                    String name = rs.getString("name");
                    UUID master;
                    try {
                        master = UUID.fromString(rs.getString("master_uuid"));
                    } catch (RuntimeException ex) {
                        plugin.getLogger().warning("Skipping guild '" + name + "': bad master_uuid");
                        continue;
                    }
                    TabMode mode;
                    try { mode = TabMode.valueOf(rs.getString("tab_mode").toUpperCase()); }
                    catch (Exception ex) { mode = TabMode.NAME; }
                    Guild g = new Guild(name, master, "Unknown", rs.getLong("created"));
                    String col = rs.getString("color");
                    if (col != null && !col.isEmpty()) g.setColor(col.charAt(0));
                    g.setTabMode(mode);
                    byId.put(rs.getLong("id"), g);
                }
            }

            Map<Long, List<String>> ranks = new HashMap<Long, List<String>>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT guild_id, rank_name FROM ranks ORDER BY guild_id, position ASC")) {
                while (rs.next()) {
                    long gid = rs.getLong("guild_id");
                    List<String> l = ranks.get(gid);
                    if (l == null) { l = new ArrayList<String>(); ranks.put(gid, l); }
                    l.add(rs.getString("rank_name"));
                }
            }

            Map<Long, List<String[]>> members = new HashMap<Long, List<String[]>>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT guild_id, uuid, name, rank_name FROM members")) {
                while (rs.next()) {
                    long gid = rs.getLong("guild_id");
                    List<String[]> l = members.get(gid);
                    if (l == null) { l = new ArrayList<String[]>(); members.put(gid, l); }
                    l.add(new String[]{ rs.getString("uuid"), rs.getString("name"), rs.getString("rank_name") });
                }
            }

            for (Map.Entry<Long, Guild> e : byId.entrySet()) {
                Guild g = e.getValue();

                List<String> custom = ranks.get(e.getKey());
                if (custom != null && !custom.isEmpty()) g.setRanks(custom);

                List<String[]> ml = members.get(e.getKey());
                if (ml != null) {
                    for (String[] m : ml) {
                        UUID u;
                        try { u = UUID.fromString(m[0]); }
                        catch (RuntimeException ex) { continue; }
                        String rank = m[2];
                        if (u.equals(g.getMaster())) rank = Guild.MASTER_RANK;
                        g.addMember(u, m[1], rank);
                        out.playerGuild.put(u, key(g.getName()));
                    }
                }
                out.playerGuild.put(g.getMaster(), key(g.getName()));
                out.guilds.put(key(g.getName()), g);
                out.saved.put(key(g.getName()), snapshotOf(g));
            }
        }
        return out;
    }

    private void swap(Loaded l) {
        this.guilds = l.guilds;
        this.playerGuild = l.playerGuild;
        this.saved = l.saved;
    }

    /**
     * Applies a fetched copy. Call on the MAIN thread. If there are local changes that are not in
     * the database yet the copy is NOT applied (those changes would be lost).
     */
    public synchronized int applyIfClean(Loaded l) {
        if (hasLocalChanges()) return REFRESH_SKIPPED;
        if (sameState(saved, l.saved)) return REFRESH_UNCHANGED;
        swap(l);
        return REFRESH_APPLIED;
    }

    private static boolean sameState(Map<String, Saved> a, Map<String, Saved> b) {
        if (a.size() != b.size()) return false;
        for (Map.Entry<String, Saved> e : a.entrySet()) {
            Saved o = b.get(e.getKey());
            if (o == null || !o.fp.equals(e.getValue().fp)) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ save (memory -> database)

    public synchronized boolean hasLocalChanges() {
        return !diff(new ArrayList<Change>(), new ArrayList<String>());
    }

    /** Fills the two lists and returns true when there is NOTHING to write. */
    private boolean diff(List<Change> changes, List<String> deletedGuilds) {
        Map<String, Guild> live = guilds;
        Map<String, Saved> base = saved;
        for (Map.Entry<String, Guild> e : live.entrySet()) {
            Guild g = e.getValue();
            Saved s = base.get(e.getKey());
            if (s != null && s.fp.equals(fingerprint(g))) continue;
            Set<UUID> removed = new HashSet<UUID>();
            if (s != null) {
                removed.addAll(s.members);
                removed.removeAll(g.getMembers());
            }
            changes.add(new Change(g, removed));
        }
        for (Map.Entry<String, Saved> e : base.entrySet()) {
            if (!live.containsKey(e.getKey())) deletedGuilds.add(e.getKey());
        }
        return changes.isEmpty() && deletedGuilds.isEmpty();
    }

    /**
     * Writes everything that changed since the last save/load in ONE transaction.
     * @return true when the database is up to date afterwards, false when the write failed.
     */
    public synchronized boolean save() {
        List<Change> changes = new ArrayList<Change>();
        List<String> deleted = new ArrayList<String>();
        if (diff(changes, deleted)) return true;

        List<String> deletedNames = new ArrayList<String>();
        for (String k : deleted) deletedNames.add(saved.get(k).name);

        if (!writeChanges(changes, deletedNames)) return false;

        Map<String, Saved> next = new HashMap<String, Saved>(saved);
        for (String k : deleted) next.remove(k);
        for (Change ch : changes) next.put(key(ch.guild.getName()), snapshotOf(ch.guild));
        saved = next;
        return true;
    }

    /** The actual SQL. Package-private + non-final so tests can replace it. */
    boolean writeChanges(List<Change> changes, List<String> deletedGuildNames) {
        boolean mysql = db.getType() == Database.Type.MYSQL;
        Connection c = null;
        try {
            c = db.connection();
            c.setAutoCommit(false);

            for (String name : deletedGuildNames) {
                try (PreparedStatement p1 = c.prepareStatement(
                        "DELETE FROM members WHERE guild_id IN (SELECT id FROM guilds WHERE name = ?)");
                     PreparedStatement p2 = c.prepareStatement(
                        "DELETE FROM ranks WHERE guild_id IN (SELECT id FROM guilds WHERE name = ?)");
                     PreparedStatement p3 = c.prepareStatement("DELETE FROM guilds WHERE name = ?")) {
                    p1.setString(1, name); p1.executeUpdate();
                    p2.setString(1, name); p2.executeUpdate();
                    p3.setString(1, name); p3.executeUpdate();
                }
            }

            String guildSql = mysql
                    ? "INSERT INTO guilds(name, master_uuid, color, tab_mode, created) VALUES(?,?,?,?,?) " +
                      "ON DUPLICATE KEY UPDATE master_uuid=VALUES(master_uuid), color=VALUES(color), " +
                      "tab_mode=VALUES(tab_mode)"
                    : "INSERT INTO guilds(name, master_uuid, color, tab_mode, created) VALUES(?,?,?,?,?) " +
                      "ON CONFLICT(name) DO UPDATE SET master_uuid=excluded.master_uuid, " +
                      "color=excluded.color, tab_mode=excluded.tab_mode";

            String memberSql = mysql
                    ? "INSERT INTO members(guild_id, uuid, name, rank_name, joined) VALUES(?,?,?,?,?) " +
                      "ON DUPLICATE KEY UPDATE name=VALUES(name), rank_name=VALUES(rank_name)"
                    : "INSERT INTO members(guild_id, uuid, name, rank_name, joined) VALUES(?,?,?,?,?) " +
                      "ON CONFLICT(guild_id, uuid) DO UPDATE SET name=excluded.name, rank_name=excluded.rank_name";

            long now = System.currentTimeMillis();

            for (Change ch : changes) {
                Guild g = ch.guild;

                try (PreparedStatement gp = c.prepareStatement(guildSql)) {
                    gp.setString(1, g.getName());
                    gp.setString(2, g.getMaster().toString());
                    gp.setString(3, String.valueOf(g.getColor()));
                    gp.setString(4, g.getTabMode().name());
                    gp.setLong(5, g.getCreated());
                    gp.executeUpdate();
                }

                long gid = 0;
                try (PreparedStatement q = c.prepareStatement("SELECT id FROM guilds WHERE name = ?")) {
                    q.setString(1, g.getName());
                    try (ResultSet r = q.executeQuery()) {
                        if (r.next()) gid = r.getLong(1);
                    }
                }
                if (gid == 0) throw new SQLException("No id for guild " + g.getName());

                // members that were removed locally (kick / leave)
                if (!ch.removedMembers.isEmpty()) {
                    try (PreparedStatement del = c.prepareStatement(
                            "DELETE FROM members WHERE guild_id = ? AND uuid = ?")) {
                        for (UUID u : ch.removedMembers) {
                            del.setLong(1, gid);
                            del.setString(2, u.toString());
                            del.addBatch();
                        }
                        del.executeBatch();
                    }
                }

                // current members (insert new, update name/rank of existing)
                try (PreparedStatement mp = c.prepareStatement(memberSql)) {
                    for (UUID u : g.getMembers()) {
                        mp.setLong(1, gid);
                        mp.setString(2, u.toString());
                        mp.setString(3, g.getMemberName(u));
                        mp.setString(4, g.getRank(u));
                        mp.setLong(5, now);
                        mp.addBatch();
                    }
                    mp.executeBatch();
                }

                // ranks: replace the list
                try (PreparedStatement del = c.prepareStatement("DELETE FROM ranks WHERE guild_id = ?")) {
                    del.setLong(1, gid);
                    del.executeUpdate();
                }
                try (PreparedStatement rp = c.prepareStatement(
                        "INSERT INTO ranks(guild_id, rank_name, position) VALUES(?,?,?)")) {
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
            return true;
        } catch (Exception ex) {
            plugin.getLogger().log(Level.SEVERE, "Save failed: " + ex.getMessage(), ex);
            try { if (c != null) c.rollback(); } catch (SQLException ignored) {}
            return false;
        } finally {
            if (c != null) {
                try { c.setAutoCommit(true); } catch (SQLException ignored) {}
                try { c.close(); } catch (SQLException ignored) {}
            }
        }
    }

    public void close() { db.close(); }
}
