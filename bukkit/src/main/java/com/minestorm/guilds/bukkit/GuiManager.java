package com.minestorm.guilds.bukkit;

import com.minestorm.guilds.common.TabMode;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;

import java.util.ArrayList;
import java.util.List;

public class GuiManager implements Listener {

    public enum Type { COLOR, TAB }

    public static class GuiHolder implements InventoryHolder {
        private final Type type;
        private Inventory inventory;
        public GuiHolder(Type type) { this.type = type; }
        public Type getType() { return type; }
        void setInventory(Inventory inv) { this.inventory = inv; }
        @Override public Inventory getInventory() { return inventory; }
    }

    private static final char[] CODES = "0123456789abcdef".toCharArray();
    private static final String[] COLOR_NAMES = new String[]{
            "Black","Dark Blue","Dark Green","Dark Aqua","Dark Red","Dark Purple","Gold","Gray",
            "Dark Gray","Blue","Green","Aqua","Red","Light Purple","Yellow","White"};
    private static final int[] RGB = new int[]{
            0, 170, 43520, 43690, 11141120, 11141290, 16755200, 11184810,
            5592405, 5592575, 5635925, 5636095, 16733525, 16733695, 16777045, 16777215};
    private static final int[] TAB_SLOTS = new int[]{10, 12, 14, 16};

    private final MineStormGuilds plugin;

    public GuiManager(MineStormGuilds plugin) { this.plugin = plugin; }

    public static String colorName(char code) {
        for (int i = 0; i < CODES.length; i++)
            if (CODES[i] == Character.toLowerCase(code)) return COLOR_NAMES[i];
        return "Unknown";
    }

    public void openColor(Player p, Guild g) {
        GuiHolder holder = new GuiHolder(Type.COLOR);
        Inventory inv = Bukkit.createInventory(holder, 36, Msg.color("&8MineStormGuilds - Color"));
        holder.setInventory(inv);
        populateColor(inv, g);
        p.openInventory(inv);
    }

    public void openTab(Player p, Guild g) {
        GuiHolder holder = new GuiHolder(Type.TAB);
        Inventory inv = Bukkit.createInventory(holder, 27, Msg.color("&8MineStormGuilds - Tab"));
        holder.setInventory(inv);
        populateTab(inv, g, p);
        p.openInventory(inv);
    }

    private void fill(Inventory inv) {
        ItemStack filler = item(Material.STAINED_GLASS_PANE, 7, " ", null, false);
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
    }

    private void populateColor(Inventory inv, Guild g) {
        fill(inv);
        List<String> cur = new ArrayList<String>();
        cur.add(Msg.color("&" + g.getColor() + g.getName()));
        cur.add(Msg.color("&7Color: &" + g.getColor() + colorName(g.getColor())));
        inv.setItem(4, item(Material.NAME_TAG, 0, "&bCurrent guild color", cur, false));
        for (int i = 0; i < 16; i++) {
            char c = CODES[i];
            boolean selected = Character.toLowerCase(g.getColor()) == c;
            ItemStack it = new ItemStack(Material.LEATHER_CHESTPLATE);
            LeatherArmorMeta meta = (LeatherArmorMeta) it.getItemMeta();
            meta.setColor(Color.fromRGB(RGB[i]));
            meta.setDisplayName(Msg.color("&" + c + COLOR_NAMES[i]));
            List<String> lore = new ArrayList<String>();
            lore.add(ChatColor.GRAY + "Code: " + ChatColor.WHITE + "&" + c);
            lore.add(Msg.color("&" + c + "The quick brown fox"));
            lore.add(" ");
            lore.add(Msg.color(selected ? "&bCurrently selected" : "&fClick to select"));
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            if (selected) {
                meta.addEnchant(Enchantment.DURABILITY, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
            it.setItemMeta(meta);
            inv.setItem(slotForColor(i), it);
        }
        inv.setItem(31, item(Material.BARRIER, 0, "&cClose", null, false));
    }

    private void populateTab(Inventory inv, Guild g, Player viewer) {
        fill(inv);
        List<String> help = new ArrayList<String>();
        help.add(Msg.color("&7Placeholders usable in the formats"));
        help.add(Msg.color("&7(see tab.formats in config.yml):"));
        help.add(ChatColor.WHITE + "%guild_name%");
        help.add(ChatColor.WHITE + "%guild_rank%");
        help.add(ChatColor.WHITE + "%guild_color%");
        help.add(Msg.color("&7+ any PlaceholderAPI placeholder"));
        inv.setItem(4, item(Material.BOOK_AND_QUILL, 0, "&bWhat is shown in the tab list?", help, false));

        TabMode[] modes = TabMode.values();
        for (int i = 0; i < modes.length && i < TAB_SLOTS.length; i++) {
            TabMode m = modes[i];
            boolean selected = g.getTabMode() == m;
            String raw = plugin.getConfig().getString("tab.formats." + m.name(), m.getDefaultFormat());
            String prefix = plugin.getTabManager().format(viewer, g, g.getRank(viewer.getUniqueId()), m, true);
            List<String> lore = new ArrayList<String>();
            lore.add(Msg.color("&7Format:"));
            lore.add(ChatColor.WHITE + (raw.isEmpty() ? "(empty)" : raw));
            lore.add(Msg.color("&7Preview:"));
            lore.add(m == TabMode.NONE ? Msg.color("&8(no guild prefix)") : (prefix + viewer.getName()));
            lore.add(" ");
            lore.add(Msg.color(selected ? "&bCurrently selected" : "&fClick to select"));
            Material icon = m == TabMode.NONE ? Material.BARRIER :
                    m == TabMode.NAME ? Material.NAME_TAG :
                    m == TabMode.RANK ? Material.PAPER : Material.BOOK;
            inv.setItem(TAB_SLOTS[i], item(icon, 0, "&b" + m.getDisplay(), lore, selected));
        }
        inv.setItem(22, item(Material.BARRIER, 0, "&cClose", null, false));
    }

    private int slotForColor(int i) { return i < 8 ? 9 + i : 18 + i - 8; }

    private int colorForSlot(int slot) {
        if (slot >= 9 && slot <= 16) return slot - 9;
        if (slot >= 18 && slot <= 25) return slot - 18 + 8;
        return -1;
    }

    private ItemStack item(Material m, int data, String name, List<String> lore, boolean glow) {
        ItemStack it = new ItemStack(m, 1, (short) data);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(Msg.color(name));
        if (lore != null) meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        if (glow) {
            meta.addEnchant(Enchantment.DURABILITY, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof GuiHolder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        Inventory top = e.getInventory();
        int slot = e.getRawSlot();
        if (slot < 0 || slot >= top.getSize()) return;
        GuiHolder holder = (GuiHolder) top.getHolder();
        Guild g = plugin.getGuildManager().getGuild(p.getUniqueId());
        if (g == null || !g.isMaster(p.getUniqueId())) {
            p.closeInventory();
            plugin.getMessages().send(p, "no-permission");
            return;
        }
        if (holder.getType() == Type.COLOR) {
            if (slot == 31) { p.closeInventory(); return; }
            int idx = colorForSlot(slot);
            if (idx < 0) return;
            g.setColor(CODES[idx]);
            plugin.getGuildManager().save();
            plugin.getTabManager().refreshGuild(g);
            populateColor(top, g);
            plugin.broadcast(g, plugin.getMessages().format("color-set",
                    "code", "&" + CODES[idx], "name", COLOR_NAMES[idx]));
        } else {
            if (slot == 22) { p.closeInventory(); return; }
            TabMode[] modes = TabMode.values();
            for (int i = 0; i < TAB_SLOTS.length && i < modes.length; i++) {
                if (TAB_SLOTS[i] != slot) continue;
                g.setTabMode(modes[i]);
                plugin.getGuildManager().save();
                plugin.getTabManager().refreshGuild(g);
                populateTab(top, g, p);
                plugin.broadcast(g, plugin.getMessages().format("tab-set", "mode", modes[i].getDisplay()));
                return;
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof GuiHolder) e.setCancelled(true);
    }
}
