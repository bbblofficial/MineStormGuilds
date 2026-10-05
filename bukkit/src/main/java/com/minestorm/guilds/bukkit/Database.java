package com.minestorm.guilds.bukkit;

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
