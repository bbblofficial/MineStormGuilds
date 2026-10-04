package com.minestorm.guilds.bungee;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

/**
 * MineStormGuilds proxy bridge (BungeeCord).
 * Relays guild-chat packets sent by one backend to every OTHER backend. Each backend decides
 * locally which of its players belong to the guild. Packets are never forwarded to players and
 * packets coming from clients are dropped (anti-spoofing).
 *
 * Created by Muvixo.
 */
public class MineStormGuildsBungee extends Plugin implements Listener {

    public static final String TAG_MODERN = "minestormguilds:main";
    public static final String TAG_LEGACY = "MSGuilds";

    @Override
    public void onEnable() {
        ProxyServer.getInstance().registerChannel(TAG_MODERN);
        ProxyServer.getInstance().registerChannel(TAG_LEGACY);
        ProxyServer.getInstance().getPluginManager().registerListener(this, this);
        getLogger().info("MineStormGuilds-Bungee enabled. Created by Muvixo.");
    }

    @Override
    public void onDisable() {
        ProxyServer.getInstance().unregisterChannel(TAG_MODERN);
        ProxyServer.getInstance().unregisterChannel(TAG_LEGACY);
    }

    @EventHandler
    public void onPluginMessage(PluginMessageEvent e) {
        String tag = e.getTag();
        if (!TAG_MODERN.equals(tag) && !TAG_LEGACY.equals(tag)) return;

        e.setCancelled(true); // never leak our packets to clients or the backend they came from
        if (!(e.getSender() instanceof Server)) return; // ignore anything a client tries to send

        ServerInfo origin = ((Server) e.getSender()).getInfo();
        byte[] data = e.getData();
        for (ServerInfo info : getProxy().getServers().values()) {
            if (info.equals(origin)) continue;
            if (info.getPlayers().isEmpty()) continue; // plugin messages need a carrier player
            info.sendData(tag, data);
        }
    }
}
