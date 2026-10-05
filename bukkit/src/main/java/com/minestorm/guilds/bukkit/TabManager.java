package com.minestorm.guilds.bukkit;

import com.minestorm.guilds.common.TabMode;
import org.bukkit.entity.Player;

/**
 * NO-OP stub.
 *
 * The "/g tab" feature was removed, but some code paths still call into this
 * class. Every method does nothing, so no scoreboard team / tab prefix is
 * created anywhere. Remove this class (and every getTabManager() caller) in a
 * follow-up cleanup if desired.
 */
public class TabManager {

    public TabManager(MineStormGuilds plugin) { /* nothing */ }

    public String format(Player p, Guild g, String rank, TabMode mode, boolean usePapi) {
        return "";
    }

    public void apply(Player p) { /* nothing */ }
    public void refreshGuild(Guild g) { /* nothing */ }
    public void remove(Player p) { /* nothing */ }
    public void purgeStale() { /* nothing */ }
}
