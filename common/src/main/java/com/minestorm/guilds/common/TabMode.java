package com.minestorm.guilds.common;

/**
 * Kept as a plain enum so the remaining callers compile after "/g tab" was removed.
 */
public enum TabMode {
    NAME("Guild Name", "%guild_color%[%guild_name%] &f"),
    RANK("Guild Rank", "%guild_color%[%guild_rank%] &f"),
    NAME_RANK("Guild Name + Rank", "%guild_color%[%guild_name%|%guild_rank%] &f"),
    NONE("Hidden", "");

    private final String display;
    private final String defaultFormat;

    TabMode(String display, String defaultFormat) {
        this.display = display;
        this.defaultFormat = defaultFormat;
    }

    public String getDisplay() { return display; }
    public String getDefaultFormat() { return defaultFormat; }
}
