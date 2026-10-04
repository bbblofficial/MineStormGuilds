package com.minestorm.guilds.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.Messenger;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * Cross-server guild chat through the BungeeCord / Velocity MineStormGuilds plugin.
 * Packet: UTF "chat", UTF guildName(lowercase), UTF already-formatted message.
 * Channel: "minestormguilds:main" (1.13+) with a 1.8-compatible fallback "MSGuilds" (max 16 chars).
 */
public class ProxyBridge implements PluginMessageListener {

    public static final String MODERN = "minestormguilds:main";
    public static final String LEGACY = "MSGuilds";

    private final MineStormGuilds plugin;
    private String channel;

    public ProxyBridge(MineStormGuilds plugin) { this.plugin = plugin; }

    public void enable() {
        if (!plugin.getConfig().getBoolean("bridge.enabled", false)) return;
        Messenger m = Bukkit.getMessenger();
        for (String c : new String[]{ MODERN, LEGACY }) {
            try {
                m.registerOutgoingPluginChannel(plugin, c);
                m.registerIncomingPluginChannel(plugin, c, this);
                channel = c;
                plugin.getLogger().info("Proxy bridge enabled on channel " + c);
                return;
            } catch (RuntimeException ex) {
                // channel name rejected by this server version -> try the next one
                try { m.unregisterOutgoingPluginChannel(plugin, c); } catch (RuntimeException ignored) {}
            }
        }
        plugin.getLogger().warning("Could not register a plugin messaging channel; bridge disabled.");
    }

    public void disable() {
        if (channel == null) return;
        Messenger m = Bukkit.getMessenger();
        m.unregisterOutgoingPluginChannel(plugin, channel);
        m.unregisterIncomingPluginChannel(plugin, channel, this);
        channel = null;
    }

    public void sendChat(String guild, String formatted) {
        if (channel == null) return;
        Player carrier = null;
        for (Player p : Bukkit.getOnlinePlayers()) { carrier = p; break; }
        if (carrier == null) return;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeUTF("chat");
            out.writeUTF(guild.toLowerCase());
            out.writeUTF(formatted);
            carrier.sendPluginMessage(plugin, channel, bos.toByteArray());
        } catch (IOException ignored) {}
    }

    @Override
    public void onPluginMessageReceived(String ch, Player player, byte[] message) {
        if (channel == null || !channel.equalsIgnoreCase(ch)) return;
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(message));
            if (!"chat".equals(in.readUTF())) return;
            String guild = in.readUTF();
            String text = in.readUTF();
            Guild g = plugin.getGuildManager().getGuildByName(guild);
            if (g != null) plugin.broadcast(g, text); // local members only, never re-relayed
        } catch (IOException ignored) {}
    }
}
