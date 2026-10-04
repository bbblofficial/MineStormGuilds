package com.minestorm.guilds.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Reloads guild data from the shared MySQL database on a timer so that
 * changes made on OTHER servers become visible here without a restart.
 *
 * Only active when database.type is mysql and refresh-interval-seconds > 0.
 */
public class GuildCacheRefresher implements Runnable {

    private final MineStormGuilds plugin;
    private int taskId = -1;

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

    @Override
    public void run() {
        try {
            plugin.getGuildManager().load();
            Bukkit.getScheduler().runTask(plugin, new Runnable() {
                @Override public void run() {
                    for (Player p : Bukkit.getOnlinePlayers()) {
                        plugin.getTabManager().apply(p);
                    }
                }
            });
        } catch (Throwable t) {
            plugin.getLogger().warning("Cache refresh failed: " + t.getMessage());
        }
    }
}
