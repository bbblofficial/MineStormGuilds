package com.minestorm.guilds.bukkit;

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
            }
        });
    }
}
