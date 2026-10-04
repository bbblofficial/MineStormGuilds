package com.minestorm.guilds.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import org.slf4j.Logger;

import java.nio.file.Path;

/**
 * MineStormGuilds proxy bridge (Velocity).
 * Relays guild-chat packets between backend servers.
 *
 * Created by Muvixo.
 */
@Plugin(
        id = "minestormguilds",
        name = "MineStormGuilds",
        version = "1.0.0",
        description = "Advanced Guild System — Proxy Bridge",
        authors = {"Muvixo"}
)
public class MineStormGuildsVelocity {

    public static final MinecraftChannelIdentifier CHANNEL =
            MinecraftChannelIdentifier.from("minestormguilds:main");

    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;

    @Inject
    public MineStormGuildsVelocity(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onProxyInit(ProxyInitializeEvent e) {
        server.getChannelRegistrar().register(CHANNEL);
        logger.info("MineStormGuilds-Velocity enabled. Created by Muvixo.");
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent e) {
        server.getChannelRegistrar().unregister(CHANNEL);
    }
}
