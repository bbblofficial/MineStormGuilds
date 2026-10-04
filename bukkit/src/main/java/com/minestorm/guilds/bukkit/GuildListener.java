package com.minestorm.guilds.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class GuildListener implements Listener {

    private final MineStormGuilds plugin;
    public GuildListener(MineStormGuilds plugin) { this.plugin = plugin; }

    private boolean suppress() {
        return plugin.getConfig().getBoolean("settings.suppress-public-join-quit", true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        Guild g = plugin.getGuildManager().getGuild(p.getUniqueId());
        if (g == null) return;
        if (!p.getName().equals(g.getMemberName(p.getUniqueId()))) {
            g.setMemberName(p.getUniqueId(), p.getName());
            plugin.getGuildManager().save();
        }
        plugin.getTabManager().apply(p);
        if (suppress()) e.setJoinMessage(null);
        String fmt = plugin.getConfig().getString("formats.member-join",
                "&2Guild > &a%player% &ejoined the Server!");
        plugin.broadcastExcept(g, Msg.color(fmt.replace("%player%", p.getName())), p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent e) {
        Player p = e.getPlayer();
        plugin.getChatToggled().remove(p.getUniqueId());
        Guild g = plugin.getGuildManager().getGuild(p.getUniqueId());
        plugin.getTabManager().remove(p);
        if (g == null) return;
        if (suppress()) e.setQuitMessage(null);
        String fmt = plugin.getConfig().getString("formats.member-quit",
                "&2Guild > &a%player% &eleft the Server!");
        plugin.broadcastExcept(g, Msg.color(fmt.replace("%player%", p.getName())), p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onKick(PlayerKickEvent e) {
        if (suppress() && plugin.getGuildManager().getGuild(e.getPlayer().getUniqueId()) != null)
            e.setLeaveMessage(null);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent e) {
        final Player p = e.getPlayer();
        if (!plugin.getChatToggled().contains(p.getUniqueId())) return;
        e.setCancelled(true);
        final String msg = e.getMessage();
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override public void run() {
                if (plugin.getGuildManager().getGuild(p.getUniqueId()) == null) {
                    plugin.getChatToggled().remove(p.getUniqueId());
                    plugin.getMessages().send(p, "chat-no-guild");
                } else {
                    plugin.sendGuildChat(p, msg);
                }
            }
        });
    }
}
