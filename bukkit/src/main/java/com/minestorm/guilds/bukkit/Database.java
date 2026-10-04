package com.minestorm.guilds.bukkit;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * SQLite wrapper. The DB file lives in plugins/MineStormGuilds/guilds.db
 */
public class Database {

    private final MineStormGuilds plugin;
    private final File file;
    private Connection conn;

    public Database(MineStormGuilds plugin) {
        this.plugin = plugin;
        String name = plugin.getConfig().getString("database.file", "guilds.db");
        this.file = new File(plugin.getDataFolder(), name);
    }

    public synchronized Connection connection() throws SQLException {
        if (conn == null || conn.isClosed()) {
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException ex) {
                try {
                    Class.forName("com.minestorm.guilds.libs.sqlite.JDBC");
                } catch (ClassNotFoundException ignored) {}
            }
            conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("PRAGMA foreign_keys = ON");
            }
            createSchema();
        }
        return conn;
    }

    private void createSchema() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS guilds (" +
                " id INTEGER PRIMARY KEY AUTOINCREMENT," +
                " name TEXT UNIQUE NOT NULL," +
                " master_uuid TEXT NOT NULL," +
                " color TEXT NOT NULL DEFAULT 'a'," +
                " tab_mode TEXT NOT NULL DEFAULT 'NAME'," +
                " created INTEGER NOT NULL" +
                ");"
            );
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS members (" +
                " guild_id INTEGER NOT NULL," +
                " uuid TEXT NOT NULL," +
                " name TEXT NOT NULL," +
                " rank TEXT NOT NULL," +
                " joined INTEGER NOT NULL DEFAULT 0," +
                " PRIMARY KEY (guild_id, uuid)," +
                " FOREIGN KEY (guild_id) REFERENCES guilds(id) ON DELETE CASCADE" +
                ");"
            );
            st.executeUpdate(
                "CREATE TABLE IF NOT EXISTS ranks (" +
                " guild_id INTEGER NOT NULL," +
                " rank TEXT NOT NULL," +
                " position INTEGER NOT NULL," +
                " PRIMARY KEY (guild_id, rank)," +
                " FOREIGN KEY (guild_id) REFERENCES guilds(id) ON DELETE CASCADE" +
                ");"
            );
            st.executeUpdate("CREATE INDEX IF NOT EXISTS idx_members_uuid ON members(uuid);");
        }
    }

    public PreparedStatement prep(String sql) throws SQLException {
        return connection().prepareStatement(sql);
    }

    public synchronized void close() {
        try { if (conn != null && !conn.isClosed()) conn.close(); }
        catch (SQLException ignored) {}
    }
}
