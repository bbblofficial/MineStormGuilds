package com.minestorm.guilds.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.LegacyChannelIdentifier;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.slf4j.Logger;

/**
 * MineStormGuilds proxy bridge (Velocity).
 * Relays guild-chat packets sent by one backend to every OTHER backend. Packets are never
 * forwarded to players and packets coming from clients are dropped (anti-spoofing).
 *
 * Created by Muvixo.
 */
@Plugin(
        id = "minestormguilds",
        name = "MineStormGuilds",
        version = "1.0.1",
        description = "Advanced Guild System - Proxy Bridge",
        authors = {"Muvixo"}
)
public class MineStormGuildsVelocity {

    public static final MinecraftChannelIdentifier MODERN =
            MinecraftChannelIdentifier.from("minestormguilds:main");
    public static final LegacyChannelIdentifier LEGACY = new LegacyChannelIdentifier("MSGuilds");

    private final ProxyServer server;
    private final Logger logger;

    @Inject
    public MineStormGuildsVelocity(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInit(ProxyInitializeEvent e) {
        server.getChannelRegistrar().register(MODERN, LEGACY);
        logger.info("MineStormGuilds-Velocity enabled. Created by Muvixo.");
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent e) {
        ChannelIdentifier id = e.getIdentifier();
        if (!id.getId().equals(MODERN.getId()) && !id.getId().equals(LEGACY.getId())) return;

        e.setResult(PluginMessageEvent.ForwardResult.handled()); // never forward to client/backend
        if (!(e.getSource() instanceof ServerConnection)) return; // ignore anything a client sends

        String originName = ((ServerConnection) e.getSource()).getServerInfo().getName();
        byte[] data = e.getData();
        for (RegisteredServer rs : server.getAllServers()) {
            if (rs.getServerInfo().getName().equals(originName)) continue;
            if (rs.getPlayersConnected().isEmpty()) continue; // needs a carrier player
            rs.sendPluginMessage(id, data);
        }
    }
}
