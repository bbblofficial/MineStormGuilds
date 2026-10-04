package com.minestorm.guilds.bukkit;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
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
 */
public class Database {

    public enum Type { SQLITE, MYSQL }

    private final MineStormGuilds plugin;
    private final Type type;
    private final File sqliteFile;
    private final String jdbcUrl;       // full DB url (after DB is guaranteed to exist)
    private final String serverUrl;     // url without db name, used to CREATE DATABASE
    private final String dbName;
    private final String username;
    private final String password;
    private HikariDataSource pool;

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
            this.username = null;
            this.password = null;
        } else {
            this.sqliteFile = null;
            String host = plugin.getConfig().getString("database.host", "127.0.0.1");
            int port = plugin.getConfig().getInt("database.port", 3306);
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

    public synchronized Connection connection() throws SQLException {
        if (pool == null) init();
        Connection c = pool.getConnection();
        if (type == Type.SQLITE) {
            Statement st = c.createStatement();
            try { st.executeUpdate("PRAGMA foreign_keys = ON"); }
            finally { st.close(); }
        }
        return c;
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

        pool = new HikariDataSource(cfg);
        createSchema();
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

    private void createSchema() throws SQLException {
        String autoInc;
        String engineSuffix;
        if (type == Type.MYSQL) {
            autoInc = "BIGINT AUTO_INCREMENT PRIMARY KEY";
            engineSuffix = " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci";
        } else {
            autoInc = "INTEGER PRIMARY KEY AUTOINCREMENT";
            engineSuffix = "";
        }

        try (Statement st = connection().createStatement()) {
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS guilds (" +
                " id " + autoInc + "," +
                " name VARCHAR(32) UNIQUE NOT NULL," +
                " master_uuid VARCHAR(36) NOT NULL," +
                " color VARCHAR(1) NOT NULL DEFAULT 'a'," +
                " tab_mode VARCHAR(16) NOT NULL DEFAULT 'NAME'," +
                " created BIGINT NOT NULL" +
                ")" + engineSuffix
            );
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS members (" +
                " guild_id BIGINT NOT NULL," +
                " uuid VARCHAR(36) NOT NULL," +
                " name VARCHAR(16) NOT NULL," +
                " rank VARCHAR(32) NOT NULL," +
                " joined BIGINT NOT NULL DEFAULT 0," +
                " PRIMARY KEY (guild_id, uuid)," +
                " INDEX idx_members_uuid (uuid)," +
                " FOREIGN KEY (guild_id) REFERENCES guilds(id) ON DELETE CASCADE" +
                ")" + engineSuffix
            );
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS ranks (" +
                " guild_id BIGINT NOT NULL," +
                " rank VARCHAR(32) NOT NULL," +
                " position INT NOT NULL," +
                " PRIMARY KEY (guild_id, rank)," +
                " FOREIGN KEY (guild_id) REFERENCES guilds(id) ON DELETE CASCADE" +
                ")" + engineSuffix
            );
        }
    }

    public synchronized void close() {
        if (pool != null && !pool.isClosed()) pool.close();
        pool = null;
    }
}
