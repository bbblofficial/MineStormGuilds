package com.minestorm.guilds.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class MineStormGuilds extends JavaPlugin {

    private GuildManager guildManager;
    private GuiManager guiManager;
    private Messages messages;
    private ProxyBridge bridge;
    private GuildCacheRefresher refresher;
    private boolean papi;

    private final Set<UUID> chatToggled =
            Collections.synchronizedSet(new HashSet<UUID>());

    @Override
    public void onEnable() {
        saveDefaultConfig();
        ConfigMerger.mergeAll(this);   // auto-merge: add keys missing from config.yml / messages.yml
        reloadConfig();
        messages = new Messages(this);
        messages.load();

        guildManager = new GuildManager(this);
        guildManager.load();

        guiManager = new GuiManager(this);
        bridge = new ProxyBridge(this);
        bridge.enable();

        GuildCommand cmd = new GuildCommand(this);
        AdminCommand admin = new AdminCommand(this);

        getCommand("guild").setExecutor(cmd);
        getCommand("guild").setTabCompleter(cmd);
        getCommand("gc").setExecutor(cmd);
        getCommand("msga").setExecutor(admin);
        getCommand("msga").setTabCompleter(admin);

        Bukkit.getPluginManager().registerEvents(new GuildListener(this), this);
        Bukkit.getPluginManager().registerEvents(guiManager, this);

        hookPapi();

        refresher = new GuildCacheRefresher(this);
        refresher.start();

        getLogger().info("MineStormGuilds enabled"
                + (papi ? " (PlaceholderAPI hooked)." : ".")
                + " Storage: " + guildManager.getDatabase().getType());
        getLogger().info("Created by Muvixo.");
    }

    @Override
    public void onDisable() {
        if (refresher != null) refresher.stop();
        if (bridge != null) bridge.disable();
        if (guildManager != null) {
            guildManager.save();
            guildManager.close();
        }
    }

    private void hookPapi() {
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") == null) {
            getLogger().info("PlaceholderAPI not found - placeholders disabled.");
            return;
        }
        try {
            papi = new GuildPlaceholders(this).register();
        } catch (Throwable t) {
            papi = false;
            getLogger().warning("Could not hook PlaceholderAPI: " + t.getMessage());
        }
    }

    public void reloadAll() {
        ConfigMerger.mergeAll(this);
        reloadConfig();
        messages.load();
    }

    public GuildManager getGuildManager() { return guildManager; }
    public GuiManager getGuiManager() { return guiManager; }
    public Messages getMessages() { return messages; }
    public ProxyBridge getBridge() { return bridge; }
    public boolean hasPapi() { return papi; }
    public Set<UUID> getChatToggled() { return chatToggled; }

    /** Fire-and-forget save so the main thread never blocks on MySQL. */
    public void saveAsync() {
        Bukkit.getScheduler().runTaskAsynchronously(this, new Runnable() {
            @Override public void run() {
                guildManager.save();
            }
        });
    }

    public void broadcast(Guild g, String colored) {
        for (UUID u : g.getMembers()) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.sendMessage(colored);
        }
    }

    public void broadcastExcept(Guild g, String colored, UUID except) {
        for (UUID u : g.getMembers()) {
            if (u.equals(except)) continue;
            Player p = Bukkit.getPlayer(u);
            if (p != null) p.sendMessage(colored);
        }
    }

    public void sendGuildChat(Player sender, String message) {
        Guild g = guildManager.getGuild(sender.getUniqueId());
        if (g == null) return;
        String fmt = getConfig().getString("formats.guild-chat",
                "&2Guild > &f%player% &b[%rank%]&f: %message%");
        String clean = message.replace('\u00A7', ' ');
        String msg = sender.hasPermission("minestormguilds.chat.color") ? Msg.color(clean) : clean;
        String out = Msg.color(
                fmt.replace("%player%", sender.getName())
                   .replace("%rank%", g.getRank(sender.getUniqueId()))
                   .replace("%guild%", g.getName())
        ).replace("%message%", msg);
        broadcast(g, out);
        if (bridge != null) bridge.sendChat(g.getName(), out);
    }
}
