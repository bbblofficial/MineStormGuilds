# MineStormGuilds
**Advanced Guild System** — Spigot / Paper / Bukkit 1.8+ · BungeeCord · Velocity

> Created by **Muvixo**

## Build
```bash
mvn clean package
```
Artifacts:
- `bukkit/target/MineStormGuilds-Bukkit-<version>.jar`   (Spigot/Paper/Bukkit)
- `bungee/target/MineStormGuilds-Bungee-<version>.jar`   (BungeeCord proxy)
- `velocity/target/MineStormGuilds-Velocity-<version>.jar` (Velocity proxy, built only on JDK 17+)

## Install
1. Put the **Bukkit** jar in each backend `plugins/` folder.
2. (Optional, only for cross-server guild chat) put the **Bungee** or **Velocity** jar in the proxy
   `plugins/` folder and set `bridge.enabled: true` in the Bukkit `config.yml`.
3. Restart. Data is stored in `plugins/MineStormGuilds/guilds.db` (SQLite).

## Commands
### Player
`/guild` (aliases `/g`, `/minestormguilds`) — create, invite, join, chat, ranks, GUI...
`/gc <message>` — guild chat.
### Admin (`/msga`)
- OP players bypass permission checks. Non-OPs need `minestormguilds.admin`
  or the granular nodes: `minestormguilds.admin.reload`, `.guild.create`, `.guild.delete`,
  `.guild.color`, `.guild.tab`, `.guild.member.add`, `.guild.member.remove`,
  `.player.info`, `.player.remove`.

## Placeholders (PlaceholderAPI)
`%minestormguilds_name%`, `_name_colored`, `_rank`, `_color`, `_color_code`, `_prefix`,
`_tab`, `_master`, `_members`, `_online`, `_has`

## Notes
- Guild data is local to each backend (SQLite). The proxy bridge only relays guild chat.

## Creator
```
/minestormguilds creator
```
Outputs: *Created by Muvixo*
