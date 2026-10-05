package com.minestorm.guilds.bukkit;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.Material;
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

    public enum Type { COLOR }

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
            "Black", "Dark Blue", "Dark Green", "Dark Aqua", "Dark Red", "Dark Purple", "Gold", "Gray",
            "Dark Gray", "Blue", "Green", "Aqua", "Red", "Light Purple", "Yellow", "White"};
    private static final int[] RGB = new int[]{
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

    // Materials resolved by name so the same jar works on 1.8 (legacy names) and newer servers.
    private static final Material NAME_TAG = mat("NAME_TAG");
    private static final Material BARRIER = mat("BARRIER");
    private static final Material LEATHER_CHEST = mat("LEATHER_CHESTPLATE");

    private final MineStormGuilds plugin;

    public GuiManager(MineStormGuilds plugin) { this.plugin = plugin; }

    private static Material mat(String... names) {
        for (String n : names) {
            Material m = Material.getMaterial(n);
            if (m != null) return m;
        }
        return Material.STONE;
    }

    private static Enchantment glowEnchant() {
        Enchantment e = Enchantment.getByName("DURABILITY");
        if (e == null) e = Enchantment.getByName("UNBREAKING");
        return e;
    }

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


    private ItemStack filler() {
        Material modern = Material.getMaterial("GRAY_STAINED_GLASS_PANE");
        if (modern != null) return item(modern, 0, " ", null, false);
        return item(mat("STAINED_GLASS_PANE"), 7, " ", null, false); // 1.8 - 1.12
    }

    private void fill(Inventory inv) {
        ItemStack f = filler();
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, f.clone());
    }

    private void populateColor(Inventory inv, Guild g) {
        fill(inv);
        List<String> cur = new ArrayList<String>();
        cur.add(Msg.color("&" + g.getColor() + g.getName()));
        cur.add(Msg.color("&7Color: &" + g.getColor() + colorName(g.getColor())));
        inv.setItem(4, item(NAME_TAG, 0, "&bCurrent guild color", cur, false));
        Enchantment glow = glowEnchant();
        for (int i = 0; i < 16; i++) {
            char c = CODES[i];
            boolean selected = Character.toLowerCase(g.getColor()) == c;
            ItemStack it = new ItemStack(LEATHER_CHEST);
            ItemMeta raw = it.getItemMeta();
            if (raw instanceof LeatherArmorMeta) ((LeatherArmorMeta) raw).setColor(Color.fromRGB(RGB[i]));
            raw.setDisplayName(Msg.color("&" + c + COLOR_NAMES[i]));
            List<String> lore = new ArrayList<String>();
            lore.add(ChatColor.GRAY + "Code: " + ChatColor.WHITE + "&" + c);
            lore.add(Msg.color("&" + c + "The quick brown fox"));
            lore.add(" ");
            lore.add(Msg.color(selected ? "&bCurrently selected" : "&fClick to select"));
            raw.setLore(lore);
            raw.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            if (selected && glow != null) {
                raw.addEnchant(glow, 1, true);
                raw.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
            it.setItemMeta(raw);
            inv.setItem(slotForColor(i), it);
        }
        inv.setItem(31, item(BARRIER, 0, "&cClose", null, false));
    }


    private int slotForColor(int i) { return i < 8 ? 9 + i : 18 + i - 8; }

    private int colorForSlot(int slot) {
        if (slot >= 9 && slot <= 16) return slot - 9;
        if (slot >= 18 && slot <= 25) return slot - 18 + 8;
        return -1;
    }

    @SuppressWarnings("deprecation")
    private ItemStack item(Material m, int data, String name, List<String> lore, boolean glow) {
        ItemStack it = data == 0 ? new ItemStack(m) : new ItemStack(m, 1, (short) data);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName(Msg.color(name));
        if (lore != null) meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        Enchantment ench = glow ? glowEnchant() : null;
        if (ench != null) {
            meta.addEnchant(ench, 1, true);
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
        if (holder.getType() != Type.COLOR) return;
        if (slot == 31) { p.closeInventory(); return; }
        int idx = colorForSlot(slot);
        if (idx < 0) return;
        g.setColor(CODES[idx]);
        plugin.getGuildManager().save();
        populateColor(top, g);
        plugin.broadcast(g, plugin.getMessages().format("color-set",
                "code", "&" + CODES[idx], "name", COLOR_NAMES[idx]));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof GuiHolder) e.setCancelled(true);
    }
}
