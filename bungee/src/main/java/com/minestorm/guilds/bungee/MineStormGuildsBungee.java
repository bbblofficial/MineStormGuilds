package com.minestorm.guilds.bungee;

import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

/**
 * MineStormGuilds proxy bridge (BungeeCord).
 * Relays guild-chat packets between backends over the "minestormguilds" channel.
 *
 * Created by Muvixo.
 */
public class MineStormGuildsBungee extends Plugin implements Listener {

    public static final String CHANNEL = "minestormguilds";

    @Override
    public void onEnable() {
        ProxyServer.getInstance().registerChannel(CHANNEL);
        ProxyServer.getInstance().getPluginManager().registerListener(this, this);
        getLogger().info("MineStormGuilds-Bungee enabled. Created by Muvixo.");
    }

    @Override
    public void onDisable() {
        ProxyServer.getInstance().unregisterChannel(CHANNEL);
    }

    @EventHandler
    public void onPluginMessage(PluginMessageEvent e) {
        if (!e.getTag().equalsIgnoreCase(CHANNEL)) return;
        if (e.getSender() instanceof net.md_5.bungee.api.connection.Server) {
            // Backend -> forward to all other servers
            try {
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(e.getData()));
                String sub = in.readUTF();
                String payload = in.readUTF();
                if ("chat".equals(sub)) {
                    getProxy().broadcast(ChatColor.translateAlternateColorCodes('&', payload));
                }
            } catch (IOException ignored) {}
        }
    }
}
