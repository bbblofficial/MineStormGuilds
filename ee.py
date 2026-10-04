#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
fixer.py  -  MineStormGuilds patcher
=====================================

Run it from the project root (the folder that contains pom.xml, bukkit/ and bungee/):

    python fixer.py                 apply every change (backups are made automatically)
    python fixer.py --dry-run       show what WOULD change, write nothing
    python fixer.py --revert        undo the last run (restores backups, removes created files)
    python fixer.py "C:\\path\\to\\MineStormGuilds"      use a different project folder

It is safe to run more than once: a change that is already applied is detected and skipped.


WHAT THIS VERSION CHANGES  (all in the bukkit module - the Bungee plugin only relays chat)
------------------------------------------------------------------------------------------
BUG FIXES  ("/g create test" works, then "/g color" says you have no guild)
  1. Database.java        - "rank" is a RESERVED WORD in MySQL 8 -> the members/ranks tables could never
                            be created, so nothing was ever saved. Column is now rank_name.
                          - SQLite does not allow INDEX(...) inside CREATE TABLE -> created separately.
                          - createSchema() leaked a connection (SQLite pool = 1 -> everything froze).
                          - a half-built schema stayed behind a live pool and was never retried.
                          - clear errors ("Could not connect to MySQL host:port/db -> reason").
  2. GuildManager.java    - load() cleared all guilds in memory BEFORE reading the database, so when the
                            database had nothing (failed save) the guild vanished within 5 seconds.
                          - save() never deleted anything: /g disband, /g kick, /g leave came back after
                            the next refresh. save() now writes only what really changed and deletes
                            removed guilds/members. save() returns true/false.
  3. GuildCacheRefresher  - reads the DB async into a detached copy, applies it on the main thread and
                            never while there are unsaved changes (also fixes the thread-unsafe HashMaps).
  4. GuildCommand.create  - if the database refuses the guild the player now gets an error message and the
                            guild is rolled back, instead of a fake "guild created".

NEW: AUTO-MERGE
  5. ConfigMerger.java    - auto-merge for config.yml and messages.yml: keys that exist in the new jar but
                            not in the server's file are added (with their comments, next to their
                            neighbours). Existing values/comments are never touched; a .bak is written;
                            the result is validated first.
  6. Database.migrate()   - auto-merge for the database: missing tables / columns / indexes are created,
                            legacy "rank" columns are renamed, no data is touched.
  7. config.yml           - new section  auto-merge: {config, messages, database}  (all default true).
  8. messages.yml         - new key  database-error.


HOW TO ADD YOUR OWN CHANGES LATER
---------------------------------
Scroll down to  "YOUR CHANGES"  and use the three helpers:

    write_file(rel_path, full_text)                    create / replace a whole file
    edit(rel_path, regex, replacement, skip_if=regex)  regex replace (must match exactly once)
    append(rel_path, text, marker)                     add text at the end unless marker is already there

Paths are relative to the project root. Line endings (CRLF/LF) of the target file are preserved.
"""

import argparse
import datetime
import difflib
import json
import os
import re
import shutil
import sys

JAVA = "bukkit/src/main/java/com/minestorm/guilds/bukkit"
RES = "bukkit/src/main/resources"

OPS = []


def write_file(rel, content):
    """Create or replace a whole file."""
    OPS.append({"op": "write", "rel": rel, "content": content})


def edit(rel, pattern, repl, skip_if=None, flags=0, name=""):
    """Regex replace. `pattern` must match exactly once. Use \\n for newlines (CRLF files are handled).
    `skip_if` = regex that proves the change is already there."""
    OPS.append({"op": "edit", "rel": rel, "pattern": pattern, "repl": repl,
                "skip_if": skip_if, "flags": flags, "name": name})


def append(rel, text, marker):
    """Append `text` to the end of the file unless `marker` (plain string) is already in it."""
    OPS.append({"op": "append", "rel": rel, "text": text, "marker": marker})


# =====================================================================================
#  BUILT-IN CHANGES
# =====================================================================================

# ---- Database.java --------------------------------------------------------------
write_file(JAVA + "/Database.java", r'''package com.minestorm.guilds.bukkit;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Storage wrapper.
 *
 * With type: mysql the plugin connects to the given MySQL server using only
 * host / database / username / password from config.yml. If the target
 * database does not exist yet it is created automatically, along with all
 * required tables and indexes. Nothing manual is needed.
 *
 * With type: sqlite the plugin uses a local guilds.db file.
 *
 * AUTO-MERGE (config: auto-merge.database, default true):
 * every time the pool is created the schema is brought up to date -
 * missing tables, columns and indexes are added and legacy columns are
 * renamed - without touching any existing data.
 */
public class Database {

    public enum Type { SQLITE, MYSQL }

    private final MineStormGuilds plugin;
    private final Type type;
    private final File sqliteFile;
    private final String jdbcUrl;       // full DB url (after DB is guaranteed to exist)
    private final String serverUrl;     // url without db name, used to CREATE DATABASE
    private final String dbName;
    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private volatile HikariDataSource pool;
    private volatile boolean closed;

    public Database(MineStormGuilds plugin) {
        this.plugin = plugin;
        String t = plugin.getConfig().getString("database.type", "sqlite");
        this.type = t.equalsIgnoreCase("mysql") ? Type.MYSQL : Type.SQLITE;

        if (type == Type.SQLITE) {
            String name = plugin.getConfig().getString("database.file", "guilds.db");
            this.sqliteFile = new File(plugin.getDataFolder(), name);
            this.jdbcUrl = "jdbc:sqlite:" + sqliteFile.getAbsolutePath();
            this.serverUrl = null;
            this.dbName = null;
            this.host = null;
            this.port = 0;
            this.username = null;
            this.password = null;
        } else {
            this.sqliteFile = null;
            this.host = plugin.getConfig().getString("database.host", "127.0.0.1");
            this.port = plugin.getConfig().getInt("database.port", 3306);
            this.dbName = plugin.getConfig().getString("database.database", "minestormguilds");
            this.username = plugin.getConfig().getString("database.username", "root");
            this.password = plugin.getConfig().getString("database.password", "");
            boolean useSSL = plugin.getConfig().getBoolean("database.useSSL", false);
            String tz = plugin.getConfig().getString("database.serverTimezone", "UTC");

            String base = "jdbc:mysql://" + host + ":" + port;
            String opts = "?useSSL=" + useSSL
                    + "&serverTimezone=" + tz
                    + "&characterEncoding=utf8"
                    + "&useUnicode=true"
                    + "&allowPublicKeyRetrieval=true"
                    + "&autoReconnect=true"
                    + "&createDatabaseIfNotExist=true";
            this.serverUrl = base + "/" + opts;
            this.jdbcUrl = base + "/" + dbName + opts;
        }
    }

    public Type getType() { return type; }

    /** Borrow a connection. ALWAYS close it (try-with-resources) so it goes back to the pool. */
    public Connection connection() throws SQLException {
        if (closed) throw new SQLException("Database is closed");
        HikariDataSource ds = pool;
        if (ds == null) ds = ensurePool();
        Connection c = ds.getConnection();
        if (type == Type.SQLITE) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("PRAGMA foreign_keys = ON");
            } catch (SQLException ex) {
                try { c.close(); } catch (SQLException ignored) {}
                throw ex;
            }
        }
        return c;
    }

    private synchronized HikariDataSource ensurePool() throws SQLException {
        if (closed) throw new SQLException("Database is closed");
        if (pool == null) init();
        return pool;
    }

    private void init() throws SQLException {
        if (type == Type.MYSQL) ensureDatabaseExists();

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl);
        if (username != null) cfg.setUsername(username);
        if (password != null) cfg.setPassword(password);
        cfg.setPoolName("MineStormGuilds-" + type.name());
        cfg.setLeakDetectionThreshold(60000L);

        if (type == Type.SQLITE) {
            cfg.setDriverClassName("org.sqlite.JDBC");
            cfg.setMaximumPoolSize(1);
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        } else {
            cfg.setDriverClassName("com.mysql.cj.jdbc.Driver");
            cfg.setMaximumPoolSize(plugin.getConfig().getInt("database.poolSize", 10));
            cfg.setMinimumIdle(1);
            cfg.setConnectionTimeout(10000L);
            cfg.setIdleTimeout(600000L);
            cfg.setMaxLifetime(1800000L);
        }

        HikariDataSource ds;
        try {
            ds = new HikariDataSource(cfg);
        } catch (RuntimeException ex) {
            String where = (type == Type.MYSQL)
                    ? "MySQL " + host + ":" + port + "/" + dbName + " as '" + username + "'"
                    : "SQLite file " + sqliteFile;
            throw new SQLException("Could not connect to " + where + " -> " + rootMessage(ex), ex);
        }

        // Bring the schema up to date. If this fails the pool is thrown away so the
        // NEXT call retries from scratch (a half-built schema is never left behind a live pool).
        try (Connection c = ds.getConnection()) {
            migrate(c);
        } catch (SQLException ex) {
            ds.close();
            throw ex;
        } catch (RuntimeException ex) {
            ds.close();
            throw ex;
        }
        pool = ds;
    }

    private static String rootMessage(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        String m = r.getMessage();
        return r.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    /** Connect to the server (no db) and CREATE DATABASE if needed. */
    private void ensureDatabaseExists() throws SQLException {
        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException ex) {
            throw new SQLException("MySQL driver not found", ex);
        }
        try (Connection c = DriverManager.getConnection(serverUrl, username, password);
             Statement st = c.createStatement()) {
            st.executeUpdate(
                    "CREATE DATABASE IF NOT EXISTS `" + dbName + "` " +
                    "CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
            );
        } catch (SQLException ex) {
            plugin.getLogger().warning(
                    "Could not auto-create database '" + dbName + "': " + ex.getMessage()
            );
            // continue; the DB might already exist and the user simply lacks CREATE
        }
    }

    // ------------------------------------------------------------------
    //  Schema creation + auto-merge (migration)
    // ------------------------------------------------------------------

    private void migrate(Connection c) throws SQLException {
        boolean mysql = (type == Type.MYSQL);
        String autoInc = mysql ? "BIGINT AUTO_INCREMENT PRIMARY KEY" : "INTEGER PRIMARY KEY AUTOINCREMENT";
        String suffix = mysql ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci" : "";

        // NOTE: "rank" is a RESERVED WORD in MySQL 8 -> the column is called rank_name.
        // NOTE: SQLite has no inline INDEX(...) in CREATE TABLE -> indexes are created separately below.
        try (Statement st = c.createStatement()) {
            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS guilds (" +
                    " id " + autoInc + "," +
                    " name VARCHAR(32) UNIQUE NOT NULL," +
                    " master_uuid VARCHAR(36) NOT NULL," +
                    " color VARCHAR(1) NOT NULL DEFAULT 'a'," +
                    " tab_mode VARCHAR(16) NOT NULL DEFAULT 'NAME'," +
                    " created BIGINT NOT NULL" +
                    ")" + suffix
            );
            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS members (" +
                    " guild_id BIGINT NOT NULL," +
                    " uuid VARCHAR(36) NOT NULL," +
                    " name VARCHAR(16) NOT NULL," +
                    " rank_name VARCHAR(32) NOT NULL," +
                    " joined BIGINT NOT NULL DEFAULT 0," +
                    " PRIMARY KEY (guild_id, uuid)," +
                    " FOREIGN KEY (guild_id) REFERENCES guilds(id) ON DELETE CASCADE" +
                    ")" + suffix
            );
            st.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS ranks (" +
                    " guild_id BIGINT NOT NULL," +
                    " rank_name VARCHAR(32) NOT NULL," +
                    " position INT NOT NULL," +
                    " PRIMARY KEY (guild_id, rank_name)," +
                    " FOREIGN KEY (guild_id) REFERENCES guilds(id) ON DELETE CASCADE" +
                    ")" + suffix
            );
        }

        if (!plugin.getConfig().getBoolean("auto-merge.database", true)) return;

        // legacy column name -> new column name
        renameColumn(c, "members", "rank", "rank_name", "VARCHAR(32) NOT NULL");
        renameColumn(c, "ranks", "rank", "rank_name", "VARCHAR(32) NOT NULL");

        // columns that older versions of the plugin did not have
        addColumnIfMissing(c, "guilds", "color", "VARCHAR(1) NOT NULL DEFAULT 'a'");
        addColumnIfMissing(c, "guilds", "tab_mode", "VARCHAR(16) NOT NULL DEFAULT 'NAME'");
        addColumnIfMissing(c, "guilds", "created", "BIGINT NOT NULL DEFAULT 0");
        addColumnIfMissing(c, "members", "name", "VARCHAR(16) NOT NULL DEFAULT 'Unknown'");
        addColumnIfMissing(c, "members", "joined", "BIGINT NOT NULL DEFAULT 0");
        addColumnIfMissing(c, "ranks", "position", "INT NOT NULL DEFAULT 0");

        ensureIndex(c, "idx_members_uuid", "members", "uuid");
    }

    private String catalog(Connection c) throws SQLException {
        return type == Type.MYSQL ? c.getCatalog() : null;
    }

    private boolean columnExists(Connection c, String table, String column) throws SQLException {
        DatabaseMetaData md = c.getMetaData();
        try (ResultSet rs = md.getColumns(catalog(c), null, table, "%")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("COLUMN_NAME"))) return true;
            }
        }
        return false;
    }

    private void renameColumn(Connection c, String table, String oldCol, String newCol, String def)
            throws SQLException {
        if (!columnExists(c, table, oldCol) || columnExists(c, table, newCol)) return;
        try (Statement st = c.createStatement()) {
            if (type == Type.MYSQL) {
                st.executeUpdate("ALTER TABLE `" + table + "` CHANGE `" + oldCol + "` `" + newCol + "` " + def);
            } else {
                st.executeUpdate("ALTER TABLE " + table + " RENAME COLUMN `" + oldCol + "` TO `" + newCol + "`");
            }
        }
        plugin.getLogger().info("[DB auto-merge] renamed " + table + "." + oldCol + " -> " + newCol);
    }

    private void addColumnIfMissing(Connection c, String table, String column, String def) throws SQLException {
        if (columnExists(c, table, column)) return;
        try (Statement st = c.createStatement()) {
            st.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column + " " + def);
        }
        plugin.getLogger().info("[DB auto-merge] added missing column " + table + "." + column);
    }

    private void ensureIndex(Connection c, String index, String table, String column) throws SQLException {
        if (type == Type.SQLITE) {
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE INDEX IF NOT EXISTS " + index + " ON " + table + "(" + column + ")");
            }
            return;
        }
        int found = 0;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM information_schema.statistics " +
                "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?")) {
            ps.setString(1, table);
            ps.setString(2, index);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) found = rs.getInt(1);
            }
        }
        if (found > 0) return;
        try (Statement st = c.createStatement()) {
            st.executeUpdate("CREATE INDEX " + index + " ON " + table + "(" + column + ")");
        }
        plugin.getLogger().info("[DB auto-merge] created index " + index);
    }

    public synchronized void close() {
        closed = true;
        HikariDataSource ds = pool;
        if (ds != null && !ds.isClosed()) ds.close();
        pool = null;
    }
}
''')

# ---- GuildManager.java ----------------------------------------------------------
write_file(JAVA + "/GuildManager.java", r'''package com.minestorm.guilds.bukkit;

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
''')

# ---- GuildCacheRefresher.java ---------------------------------------------------
write_file(JAVA + "/GuildCacheRefresher.java", r'''package com.minestorm.guilds.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Re-reads guild data from the shared MySQL database on a timer so that
 * changes made on OTHER servers become visible here without a restart.
 *
 * The database is read on an async thread into a detached copy; the copy is only swapped in
 * on the main thread and only when this server has no unsaved changes. Live guild data is
 * never cleared, so a database error can no longer make guilds "disappear".
 *
 * Only active when database.type is mysql and refresh-interval-seconds > 0.
 */
public class GuildCacheRefresher implements Runnable {

    private static final long WARN_EVERY_MS = 5L * 60L * 1000L;

    private final MineStormGuilds plugin;
    private int taskId = -1;
    private volatile long lastWarn = 0L;

    public GuildCacheRefresher(MineStormGuilds plugin) { this.plugin = plugin; }

    public void start() {
        if (plugin.getGuildManager().getDatabase().getType() != Database.Type.MYSQL) return;
        int sec = plugin.getConfig().getInt("database.refresh-interval-seconds", 5);
        if (sec <= 0) return;
        long ticks = sec * 20L;
        taskId = Bukkit.getScheduler()
                .runTaskTimerAsynchronously(plugin, this, ticks, ticks)
                .getTaskId();
        plugin.getLogger().info("Guild cache refresher started (every " + sec + "s).");
    }

    public void stop() {
        if (taskId != -1) Bukkit.getScheduler().cancelTask(taskId);
        taskId = -1;
    }

    private void warnOnce(String msg) {
        long now = System.currentTimeMillis();
        if (now - lastWarn < WARN_EVERY_MS) return;
        lastWarn = now;
        plugin.getLogger().warning(msg);
    }

    @Override
    public void run() {
        final GuildManager gm = plugin.getGuildManager();
        final GuildManager.Loaded data;
        try {
            data = gm.fetch();                      // async: database only, no live data touched
        } catch (Throwable t) {
            warnOnce("Cache refresh failed (guilds in memory are kept): " + t.getMessage());
            return;
        }
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override public void run() {
                int result = gm.applyIfClean(data); // main thread
                if (result == GuildManager.REFRESH_SKIPPED) {
                    warnOnce("Cache refresh skipped: this server has guild changes that could not be "
                            + "saved to the database yet. Check the console for 'Save failed'.");
                    return;
                }
                if (result != GuildManager.REFRESH_APPLIED) return;
                for (Player p : Bukkit.getOnlinePlayers()) {
                    plugin.getTabManager().apply(p);
                }
            }
        });
    }
}
''')

# ---- ConfigMerger.java ----------------------------------------------------------
write_file(JAVA + "/ConfigMerger.java", r'''package com.minestorm.guilds.bukkit;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AUTO-MERGE for yml files.
 *
 * After a plugin update the jar may contain keys that the server's config.yml / messages.yml
 * does not have yet. This class adds ONLY those missing keys (together with their comments)
 * to the server's file:
 *
 *  - existing values, comments, ordering and formatting are never changed or removed
 *  - a backup (config.yml.bak / messages.yml.bak) is written before the file is touched
 *  - the merged text is validated; if anything looks wrong the file is left untouched
 *
 * Toggle in config.yml:  auto-merge.config / auto-merge.messages  (default true)
 */
public final class ConfigMerger {

    private ConfigMerger() {}

    /** key line:  <indent>key: [value]   (list items "- x" are not keys) */
    private static final Pattern KEY =
            Pattern.compile("^(\\s*)(\"[^\"]+\"|'[^']+'|[A-Za-z0-9_.\\-]+)\\s*:(\\s.*)?$");

    // ------------------------------------------------------------------ plugin entry points

    public static void mergeAll(JavaPlugin plugin) {
        boolean cfg = plugin.getConfig().getBoolean("auto-merge.config", true);
        boolean msg = plugin.getConfig().getBoolean("auto-merge.messages", true);
        if (cfg) merge(plugin, "config.yml");
        if (msg) merge(plugin, "messages.yml");
    }

    /** @return number of keys that were added */
    public static int merge(JavaPlugin plugin, String resource) {
        File target = new File(plugin.getDataFolder(), resource);
        try {
            if (!target.exists()) {
                plugin.saveResource(resource, false);
                return 0;
            }
            String defText = readResource(plugin, resource);
            if (defText == null) return 0;
            String userText = new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8);
            if (userText.length() > 0 && userText.charAt(0) == '\uFEFF') userText = userText.substring(1);

            String nl = userText.contains("\r\n") ? "\r\n" : "\n";
            List<String> added = new ArrayList<String>();
            List<String> merged = mergeLines(splitLines(defText), splitLines(userText), added);
            if (added.isEmpty()) return 0;

            String out = joinLines(merged, nl);

            // ---- safety net: result must be valid YAML and must not change any existing value
            YamlConfiguration before = new YamlConfiguration();
            before.loadFromString(userText);
            YamlConfiguration after = new YamlConfiguration();
            after.loadFromString(out);
            for (String k : before.getKeys(true)) {
                if (before.isConfigurationSection(k)) continue;
                Object a = before.get(k);
                Object b = after.get(k);
                if (a == null ? b != null : !a.equals(b)) {
                    plugin.getLogger().warning("[auto-merge] " + resource
                            + ": merge would change '" + k + "' - file left untouched.");
                    return 0;
                }
            }

            File bak = new File(plugin.getDataFolder(), resource + ".bak");
            Files.write(bak.toPath(), userText.getBytes(StandardCharsets.UTF_8));
            Files.write(target.toPath(), out.getBytes(StandardCharsets.UTF_8));
            plugin.getLogger().info("[auto-merge] " + resource + ": added " + added.size()
                    + " missing key(s): " + summarize(added) + "  (backup: " + bak.getName() + ")");
            return added.size();
        } catch (InvalidConfigurationException ex) {
            plugin.getLogger().warning("[auto-merge] " + resource
                    + ": merged result is not valid YAML, file left untouched. " + ex.getMessage());
        } catch (IOException ex) {
            plugin.getLogger().warning("[auto-merge] " + resource + ": " + ex.getMessage());
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("[auto-merge] " + resource + ": " + ex);
        }
        return 0;
    }

    private static String summarize(List<String> keys) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i == 8) { sb.append(", ... +").append(keys.size() - 8).append(" more"); break; }
            if (i > 0) sb.append(", ");
            sb.append(keys.get(i));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ pure text algorithm

    /** One "key:" line of a yml file and the block that belongs to it. */
    static final class Entry {
        String path;
        int indent;
        int line;    // the key line
        int start;   // first line incl. directly attached comment lines above
        int end;     // exclusive end of the block
    }

    /**
     * Returns a copy of {@code user} with every key of {@code def} that is missing added.
     * Missing keys are inserted at the end of their parent section (or at the end of the file for
     * top-level keys). {@code added} receives the key paths that were inserted.
     */
    static List<String> mergeLines(List<String> def, List<String> user, List<String> added) {
        Map<String, Entry> di = index(def);
        List<String> out = new ArrayList<String>(user);

        // top-most missing paths, in the order of the default file
        List<String> missing = new ArrayList<String>();
        Map<String, Entry> ui = index(out);
        for (String p : di.keySet()) {
            if (ui.containsKey(p)) continue;
            boolean covered = false;
            for (String m : missing) {
                if (p.startsWith(m + ".")) { covered = true; break; }
            }
            if (!covered) missing.add(p);
        }

        for (String path : missing) {
            Entry de = di.get(path);
            int dot = path.lastIndexOf('.');
            String parent = dot < 0 ? null : path.substring(0, dot);

            Map<String, Entry> cur = index(out);
            Entry pe = null;
            if (parent != null) {
                pe = cur.get(parent);
                if (pe == null) continue; // parent is not a plain section in the user's file -> skip safely
            }

            // siblings (same parent) in the order of the default file
            List<String> sibs = new ArrayList<String>();
            for (String p : di.keySet()) {
                boolean same = parent == null
                        ? p.indexOf('.') < 0
                        : p.startsWith(parent + ".") && p.indexOf('.', parent.length() + 1) < 0;
                if (same) sibs.add(p);
            }
            int me = sibs.indexOf(path);

            // place it right after the closest previous sibling that exists (else right before the next one)
            int insertAt = -1;
            for (int k = me - 1; k >= 0 && insertAt < 0; k--) {
                Entry u = cur.get(sibs.get(k));
                if (u != null) insertAt = u.end;
            }
            for (int k = me + 1; k < sibs.size() && insertAt < 0; k++) {
                Entry u = cur.get(sibs.get(k));
                if (u != null) insertAt = u.start;
            }
            if (insertAt < 0) {
                if (parent == null) {
                    while (!out.isEmpty() && out.get(out.size() - 1).trim().isEmpty()) out.remove(out.size() - 1);
                    insertAt = out.size();
                } else {
                    insertAt = pe.end;
                }
            }

            int indent = 0;
            if (parent != null) {
                indent = pe.indent + 2;
                for (Entry x : cur.values()) {
                    if (x.path.startsWith(parent + ".") && x.path.indexOf('.', parent.length() + 1) < 0) {
                        indent = x.indent;
                        break;
                    }
                }
            }

            List<String> ins = reindent(new ArrayList<String>(def.subList(de.start, de.end)), indent - de.indent);
            boolean blankBefore = de.start > 0 && def.get(de.start - 1).trim().isEmpty();
            boolean blankAfter = de.end < def.size() && def.get(de.end).trim().isEmpty();
            if (blankBefore && insertAt > 0 && !out.get(insertAt - 1).trim().isEmpty()) ins.add(0, "");
            if (blankAfter && insertAt < out.size() && !out.get(insertAt).trim().isEmpty()) ins.add("");

            out.addAll(insertAt, ins);
            added.add(path);
        }
        return out;
    }

    /** Builds path -> block for every "key:" line. */
    static Map<String, Entry> index(List<String> lines) {
        Map<String, Entry> map = new LinkedHashMap<String, Entry>();
        List<String> pathStack = new ArrayList<String>();
        List<Integer> indentStack = new ArrayList<Integer>();

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("-")) continue;
            Matcher m = KEY.matcher(line);
            if (!m.matches()) continue;

            int indent = m.group(1).length();
            String key = m.group(2);
            if (key.length() >= 2 && (key.charAt(0) == '"' || key.charAt(0) == '\'')) {
                key = key.substring(1, key.length() - 1);
            }
            while (!indentStack.isEmpty() && indentStack.get(indentStack.size() - 1) >= indent) {
                indentStack.remove(indentStack.size() - 1);
                pathStack.remove(pathStack.size() - 1);
            }
            String path = pathStack.isEmpty() ? key : pathStack.get(pathStack.size() - 1) + "." + key;
            pathStack.add(path);
            indentStack.add(indent);

            Entry e = new Entry();
            e.path = path;
            e.indent = indent;
            e.line = i;
            if (!map.containsKey(path)) map.put(path, e);
        }

        for (Entry e : map.values()) {
            // block end = last non-blank, non-comment line that is deeper (or a list item of this key)
            int last = e.line;
            for (int j = e.line + 1; j < lines.size(); j++) {
                String l = lines.get(j);
                String t = l.trim();
                if (t.isEmpty() || t.startsWith("#")) continue;
                int ind = leadingSpaces(l);
                if (ind > e.indent || (ind == e.indent && t.startsWith("-"))) last = j;
                else break;
            }
            e.end = last + 1;

            // comment lines directly above the key belong to it
            int s = e.line;
            while (s - 1 >= 0 && lines.get(s - 1).trim().startsWith("#")) s--;
            e.start = s;
        }
        return map;
    }

    private static int leadingSpaces(String s) {
        int n = 0;
        while (n < s.length() && s.charAt(n) == ' ') n++;
        return n;
    }

    private static List<String> reindent(List<String> block, int shift) {
        List<String> out = new ArrayList<String>(block.size());
        for (String l : block) {
            if (l.trim().isEmpty() || shift == 0) { out.add(l); continue; }
            if (shift > 0) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < shift; i++) sb.append(' ');
                out.add(sb.append(l).toString());
            } else {
                int remove = Math.min(-shift, leadingSpaces(l));
                out.add(l.substring(remove));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ io helpers

    private static List<String> splitLines(String text) {
        List<String> out = new ArrayList<String>();
        if (text.isEmpty()) return out;
        String[] parts = text.split("\r\n|\n|\r", -1);
        int n = parts.length;
        if (n > 0 && parts[n - 1].isEmpty()) n--; // trailing newline is not a line
        for (int i = 0; i < n; i++) out.add(parts[i]);
        return out;
    }

    private static String joinLines(List<String> lines, String nl) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append(nl);
        return sb.toString();
    }

    private static String readResource(JavaPlugin plugin, String name) throws IOException {
        InputStream in = plugin.getResource(name);
        if (in == null) return null;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int r;
            while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
            String s = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            if (s.length() > 0 && s.charAt(0) == '\uFEFF') s = s.substring(1);
            return s;
        } finally {
            try { in.close(); } catch (IOException ignored) {}
        }
    }
}
''')


# ---- MineStormGuilds.java: run the auto-merge before config/messages are loaded --------------------
edit(JAVA + "/MineStormGuilds.java",
     r"(?P<ind>[ \t]*)saveDefaultConfig\(\);\n",
     "\\g<ind>saveDefaultConfig();\n"
     "\\g<ind>ConfigMerger.mergeAll(this);   // auto-merge: add keys missing from config.yml / messages.yml\n"
     "\\g<ind>reloadConfig();\n",
     skip_if=r"ConfigMerger\.mergeAll",
     name="onEnable(): run ConfigMerger")

# ---- MineStormGuilds.java: also on /msga reload ------------------------------------------------------
edit(JAVA + "/MineStormGuilds.java",
     r"(?P<head>public void reloadAll\(\)\s*\{\n)(?P<ind>[ \t]*)reloadConfig\(\);",
     "\\g<head>\\g<ind>ConfigMerger.mergeAll(this);\n\\g<ind>reloadConfig();",
     skip_if=r"reloadAll\(\)\s*\{\s*ConfigMerger",
     name="reloadAll(): run ConfigMerger")

# ---- GuildCommand.java: do not announce a guild that could not be saved ----------------------------
edit(JAVA + "/GuildCommand.java",
     r"(?P<a>[ \t]*)Guild g = gm\.createGuild\(name, p\);\n[ \t]*gm\.save\(\);\n",
     "\\g<a>Guild g = gm.createGuild(name, p);\n"
     "\\g<a>if (!gm.save()) {   // the database refused it -> undo and tell the player\n"
     "\\g<a>    gm.disband(g);\n"
     "\\g<a>    m.send(p, \"database-error\");\n"
     "\\g<a>    return;\n"
     "\\g<a>}\n",
     skip_if=r"if\s*\(\s*!\s*gm\.save\(\)\s*\)",
     name="create(): roll back when save fails")

# ---- resources ------------------------------------------------------------------------------------
append(RES + "/config.yml",
       "\n"
       "# ============================================================\n"
       "#  Auto-merge: after a plugin update, keys that are missing from your files are added\n"
       "#  automatically. Your values and comments are never changed or removed\n"
       "#  (a .bak copy is written first).\n"
       "#    config:   config.yml          messages: messages.yml\n"
       "#    database: create/upgrade missing tables, columns and indexes\n"
       "# ============================================================\n"
       "auto-merge:\n"
       "  config: true\n"
       "  messages: true\n"
       "  database: true\n",
       marker="auto-merge:")

append(RES + "/messages.yml",
       "\n"
       "# Database\n"
       "database-error: \"%prefix%&cCould not save to the database. Please tell an administrator.\"\n",
       marker="database-error:")


# =====================================================================================
#  YOUR CHANGES  -  add new write_file(...) / edit(...) / append(...) calls below this line
# =====================================================================================
#
# Examples (remove the leading # to use them):
#
# append(RES + "/config.yml", "\nmy-new-option: true\n", marker="my-new-option:")
#
# edit(JAVA + "/GuildCommand.java",
#      r'"minestormguilds\.create"',
#      '"minestormguilds.create.guild"',
#      skip_if=r'"minestormguilds\.create\.guild"')
#
# write_file(JAVA + "/MyNewClass.java", """package com.minestorm.guilds.bukkit;
# public class MyNewClass { }
# """)
#
# =====================================================================================
#  END OF CHANGES
# =====================================================================================


# ----------------------------------------------------------------------------- engine

def read_text(path):
    with open(path, "rb") as f:
        data = f.read()
    bom = data.startswith(b"\xef\xbb\xbf")
    if bom:
        data = data[3:]
    text = data.decode("utf-8")
    nl = "\r\n" if "\r\n" in text else "\n"
    return text.replace("\r\n", "\n"), nl, bom


def encode(text, nl, bom):
    out = text.replace("\n", nl).encode("utf-8")
    return (b"\xef\xbb\xbf" + out) if bom else out


class Runner(object):
    def __init__(self, root, dry):
        self.root = root
        self.dry = dry
        self.stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
        self.backup_dir = os.path.join(root, ".fixer_backup", self.stamp)
        self.modified = []
        self.created = []
        self.results = []
        self.default_nl = self._project_newline()

    def _project_newline(self):
        probe = os.path.join(self.root, JAVA, "MineStormGuilds.java")
        if os.path.isfile(probe):
            return read_text(probe)[1]
        return "\n"

    def path(self, rel):
        return os.path.join(self.root, *rel.split("/"))

    def _backup(self, rel):
        if self.dry or rel in self.modified:
            return
        dst = os.path.join(self.backup_dir, *rel.split("/"))
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        shutil.copy2(self.path(rel), dst)
        self.modified.append(rel)

    def _store(self, rel, text, nl, bom, existed):
        if self.dry:
            return
        if existed:
            self._backup(rel)
        else:
            self.created.append(rel)
            os.makedirs(os.path.dirname(self.path(rel)), exist_ok=True)
        with open(self.path(rel), "wb") as f:
            f.write(encode(text, nl, bom))

    def _show_diff(self, rel, old, new, limit=60):
        if not self.dry:
            return
        diff = list(difflib.unified_diff(old.splitlines(), new.splitlines(),
                                         "a/" + rel, "b/" + rel, lineterm="", n=1))
        for line in diff[:limit]:
            print("        " + line)
        if len(diff) > limit:
            print("        ... (%d more diff lines)" % (len(diff) - limit))

    # ---- operations ----

    def do_write(self, op):
        rel, new = op["rel"], op["content"].replace("\r\n", "\n")
        p = self.path(rel)
        if os.path.isfile(p):
            old, nl, bom = read_text(p)
            if old == new:
                return "unchanged", "already up to date"
            self._show_diff(rel, old, new, 30)
            self._store(rel, new, nl, bom, True)
            return "updated", "replaced (%d lines)" % (new.count("\n") + 1)
        self._store(rel, new, self.default_nl, False, False)
        return "created", "new file (%d lines)" % (new.count("\n") + 1)

    def do_edit(self, op):
        rel = op["rel"]
        p = self.path(rel)
        if not os.path.isfile(p):
            return "FAILED", "file not found"
        old, nl, bom = read_text(p)
        if op["skip_if"] and re.search(op["skip_if"], old, op["flags"]):
            return "unchanged", "already applied"
        matches = list(re.finditer(op["pattern"], old, op["flags"]))
        if len(matches) != 1:
            return "FAILED", ("pattern matched %d times (expected 1) - the file differs from what this "
                              "script expects; apply this change by hand" % len(matches))
        new = old[:matches[0].start()] + matches[0].expand(op["repl"]) + old[matches[0].end():]
        self._show_diff(rel, old, new)
        self._store(rel, new, nl, bom, True)
        return "updated", "patched"

    def do_append(self, op):
        rel = op["rel"]
        p = self.path(rel)
        if not os.path.isfile(p):
            return "FAILED", "file not found"
        old, nl, bom = read_text(p)
        if op["marker"] in old:
            return "unchanged", "already there"
        new = old.rstrip("\n") + "\n" + op["text"].replace("\r\n", "\n")
        if not new.endswith("\n"):
            new += "\n"
        self._show_diff(rel, old, new)
        self._store(rel, new, nl, bom, True)
        return "updated", "appended"

    def run(self, ops):
        for op in ops:
            label = op.get("name") or op["rel"]
            try:
                status, msg = {"write": self.do_write, "edit": self.do_edit,
                               "append": self.do_append}[op["op"]](op)
            except Exception as ex:  # keep going, report at the end
                status, msg = "FAILED", "%s: %s" % (type(ex).__name__, ex)
            self.results.append((status, op["op"], op["rel"], label, msg))
            tag = {"created": "NEW    ", "updated": "UPDATED", "unchanged": "ok     ", "FAILED": "FAILED "}[status]
            extra = "" if op["op"] == "write" or not op.get("name") else "  [%s]" % op["name"]
            print("  %s %s%s - %s" % (tag, op["rel"], extra, msg))

    def finish(self):
        if (self.modified or self.created) and not self.dry:
            os.makedirs(self.backup_dir, exist_ok=True)
            with open(os.path.join(self.backup_dir, "manifest.json"), "w") as f:
                json.dump({"modified": self.modified, "created": self.created}, f, indent=2)


def check_project(root):
    need = [os.path.join(root, "pom.xml"), os.path.join(root, "bukkit", "pom.xml"),
            os.path.join(root, *(JAVA.split("/") + ["MineStormGuilds.java"]))]
    return [p for p in need if not os.path.exists(p)]


def revert(root):
    base = os.path.join(root, ".fixer_backup")
    runs = sorted(d for d in os.listdir(base)
                  if os.path.isfile(os.path.join(base, d, "manifest.json"))) if os.path.isdir(base) else []
    if not runs:
        print("Nothing to revert (no .fixer_backup/<run>/manifest.json found).")
        return 1
    run = runs[-1]
    rdir = os.path.join(base, run)
    with open(os.path.join(rdir, "manifest.json")) as f:
        man = json.load(f)
    for rel in man["modified"]:
        shutil.copy2(os.path.join(rdir, *rel.split("/")), os.path.join(root, *rel.split("/")))
        print("  restored  " + rel)
    for rel in man["created"]:
        p = os.path.join(root, *rel.split("/"))
        if os.path.isfile(p):
            os.remove(p)
            print("  removed   " + rel)
    os.rename(os.path.join(rdir, "manifest.json"), os.path.join(rdir, "manifest.reverted.json"))
    print("Reverted run %s." % run)
    return 0


def main(argv=None):
    ap = argparse.ArgumentParser(description="MineStormGuilds fixer / patcher")
    ap.add_argument("root", nargs="?", default=os.path.dirname(os.path.abspath(__file__)),
                    help="project root (default: the folder this script is in)")
    ap.add_argument("--dry-run", action="store_true", help="show changes, write nothing")
    ap.add_argument("--revert", action="store_true", help="undo the last run")
    args = ap.parse_args(argv)
    root = os.path.abspath(args.root)

    print("MineStormGuilds fixer")
    print("project: " + root)
    missing = check_project(root)
    if missing:
        print("\nThis does not look like the MineStormGuilds project root. Missing:")
        for m in missing:
            print("  " + m)
        print("\nPut fixer.py in the folder that contains pom.xml / bukkit / bungee, or pass that folder as argument.")
        return 2

    if args.revert:
        return revert(root)

    print("mode:    " + ("DRY RUN (nothing is written)" if args.dry_run else "apply") + "\n")
    r = Runner(root, args.dry_run)
    r.run(OPS)
    r.finish()

    failed = [x for x in r.results if x[0] == "FAILED"]
    changed = [x for x in r.results if x[0] in ("created", "updated")]
    print("\n%d change(s) %s, %d already applied, %d failed." % (
        len(changed), "would be made" if args.dry_run else "made",
        len([x for x in r.results if x[0] == "unchanged"]), len(failed)))
    if failed:
        print("\nFAILED items (everything else was still applied):")
        for st, op, rel, label, msg in failed:
            print("  - %s: %s" % (label, msg))
    if changed and not args.dry_run:
        print("\nBackups: " + os.path.relpath(r.backup_dir, root) + "   (undo with: python fixer.py --revert)")
        print("\nNEXT STEPS")
        print("  1. mvn clean package")
        print("  2. copy bukkit/target/MineStormGuilds-Bukkit-*.jar to EVERY backend server (replace the old jar)")
        print("     (the Bungee jar does not need to change)")
        print("  3. restart the servers and look in the console for '[auto-merge]' lines;")
        print("     there must be no 'Load failed' / 'Save failed' / 'Could not connect' errors.")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())