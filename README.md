# MineStormGuilds
**Advanced Guild System** — Spigot / Paper / Bukkit 1.8+ · BungeeCord · Velocity

> Created by **Muvixo**

## Build
```bash
mvn clean package
```
Artifacts land in:
- `bukkit/target/MineStormGuilds-Bukkit-<version>.jar`  (Spigot/Paper/Bukkit)
- `bungee/target/MineStormGuilds-Bungee-<version>.jar`  (BungeeCord proxy)
- `velocity/target/MineStormGuilds-Velocity-<version>.jar` (Velocity proxy, JDK 17+)

## Install
1. Drop the **Bukkit** jar in each backend `plugins/` folder.
2. Drop the **Bungee** or **Velocity** jar in the proxy `plugins/` folder.
3. Restart. Data is stored in `plugins/MineStormGuilds/guilds.db` (SQLite).

## Commands
### Player
`/guild` (alias `/g`, `/minestormguilds`) — create, invite, join, chat, ranks, GUI…
### Admin (`/msga`)
- OP players bypass permission. Non‑OPs need `minestormguilds.admin`.
- Also granular perms: `minestormguilds.admin.reload`, `.guild.create`, `.guild.delete`,
  `.guild.color`, `.guild.tab`, `.guild.member.add`, `.guild.member.remove`, `.player.info`.

## Creator
```
/minestormguilds creator
```
Outputs: *Created by Muvixo*
