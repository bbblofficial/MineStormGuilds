package com.minestorm.guilds.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class MineStormGuilds extends JavaPlugin {

    private GuildManager guildManager;
    private TabManager tabManager;
    private GuiManager guiManager;
    private Messages messages;
    private boolean papi;

    private final Set<UUID> chatToggled =
            Collections.synchronizedSet(new HashSet<UUID>());

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messages = new Messages(this);
        messages.load();

        guildManager = new GuildManager(this);
        guildManager.load();

        tabManager = new TabManager(this);
        guiManager = new GuiManager(this);

        GuildCommand cmd = new GuildCommand(this);
        AdminCommand admin = new AdminCommand(this);

        getCommand("guild").setExecutor(cmd);
        getCommand("guild").setTabCompleter(cmd);
        getCommand("gc").setExecutor(cmd);
        getCommand("msga").setExecutor(admin);
        getCommand("msga").setTabCompleter(admin);

        Bukkit.getPluginManager().registerEvents(new GuildListener(this), (Plugin) this);
        Bukkit.getPluginManager().registerEvents(guiManager, (Plugin) this);

        hookPapi();
        tabManager.purgeStale();
        for (Player p : Bukkit.getOnlinePlayers()) tabManager.apply(p);

        getLogger().info("MineStormGuilds enabled" + (papi ? " (PlaceholderAPI hooked)." : "."));
        getLogger().info("Created by Muvixo.");
    }

    @Override
    public void onDisable() {
        if (guildManager != null) guildManager.save();
        if (tabManager != null) tabManager.purgeStale();
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

    public GuildManager getGuildManager() { return guildManager; }
    public TabManager getTabManager() { return tabManager; }
    public GuiManager getGuiManager() { return guiManager; }
    public Messages getMessages() { return messages; }
    public boolean hasPapi() { return papi; }
    public Set<UUID> getChatToggled() { return chatToggled; }

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
        String msg = sender.hasPermission("minestormguilds.chat.color")
                ? Msg.color(message) : message;
        String out = Msg.color(
                fmt.replace("%player%", sender.getName())
                   .replace("%rank%", g.getRank(sender.getUniqueId()))
                   .replace("%guild%", g.getName())
        ).replace("%message%", msg);
        broadcast(g, out);
    }
}
