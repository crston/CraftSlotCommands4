package com.gmail.bobason01.util;

import com.gmail.bobason01.api.CraftSlotAPIProvider;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class ItemBuilder {

    private static final Logger LOGGER = Bukkit.getLogger();
    private static final ItemStack ERROR_ITEM;
    private static final Map<String, Map<String, ItemModel>> PAGE_CACHE = new ConcurrentHashMap<>();
    /** material.ordinal << 32 | cmd — creative NBT round-trip may drop PDC. */
    private static final Set<Long> MENU_FINGERPRINTS = ConcurrentHashMap.newKeySet();
    private static final Map<String, AttributeModifier> ZERO_MODIFIERS = new HashMap<>();
    private static final ItemFlag[] ALL_FLAGS_ARRAY = ItemFlag.values();
    private static final Attribute[] ATTRIBUTES_ARRAY = Attribute.values();
    private static final NamespacedKey MENU_ICON_KEY = new NamespacedKey("craftslotcommands5", "menu_icon");

    static {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.DARK_RED + "ERROR");
            meta.setLore(List.of(ChatColor.RED + "Check plugin configuration"));
            item.setItemMeta(meta);
        }
        ERROR_ITEM = item;

        for (Attribute attribute : ATTRIBUTES_ARRAY) {
            String key = attribute.name();
            String keyLower = key.toLowerCase(Locale.ROOT).replace("_", "");
            ZERO_MODIFIERS.put(key, new AttributeModifier(
                    NamespacedKey.minecraft("zero_" + keyLower),
                    0.0,
                    AttributeModifier.Operation.ADD_NUMBER,
                    EquipmentSlotGroup.ANY
            ));
        }
    }

    private ItemBuilder() {}

    public static void prepareReload() {
        PAGE_CACHE.clear();
        MENU_FINGERPRINTS.clear();
    }

    public static void loadFromConfig(String pageState, ConfigurationSection root) {
        Map<String, ItemModel> pageMap = PAGE_CACHE.computeIfAbsent(pageState, k -> new ConcurrentHashMap<>());
        pageMap.clear();
        if (root == null) return;

        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) continue;
            try {
                ItemModel model = new ItemModel(
                        section.getString("material"),
                        section.getString("name"),
                        section.getStringList("lore"),
                        section.getInt("model"),
                        section.getString("item-model"),
                        section.getInt("damage"),
                        section.getBoolean("unbreakable"),
                        section.getBoolean("strip-attributes"),
                        section.getBoolean("hide-all-flags"),
                        section.getStringList("hide-flags")
                );
                pageMap.put(key, model);
                Material mat = Material.matchMaterial(model.material());
                if (mat != null) {
                    MENU_FINGERPRINTS.add(fingerprint(mat, model.model()));
                }
            } catch (Exception e) {
                LOGGER.warning("ItemBuilder Failed to load item model " + key + " on page " + pageState);
            }
        }
    }

    private static long fingerprint(Material mat, int customModelData) {
        return ((long) mat.ordinal() << 32) | (customModelData & 0xffffffffL);
    }

    public static ItemStack get(String pageState, String key) {
        return get(null, pageState, key);
    }

    public static ItemStack get(Player player, String pageState, String key) {
        Map<String, ItemModel> pageMap = PAGE_CACHE.get(pageState);
        if (pageMap == null) return ERROR_ITEM.clone();
        ItemModel model = pageMap.get(key);
        return model != null ? build(player, model) : ERROR_ITEM.clone();
    }

    public static ItemStack build(Player player, ItemModel model) {
        Material mat = Material.matchMaterial(model.material());
        if (mat == null) mat = Material.BARRIER;

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        if (model.name() != null) {
            meta.setDisplayName(parse(player, model.name()));
        }
        CraftSlotAPIProvider.get().applyModelIntegration(meta, model.model(), model.itemModel());

        if (!model.lore().isEmpty()) {
            List<String> lore = new ArrayList<>(model.lore().size());
            for (String line : model.lore()) lore.add(parse(player, line));
            meta.setLore(lore);
        }
        if (model.unbreakable()) meta.setUnbreakable(true);
        if (meta instanceof Damageable dmg && model.damage() > 0) dmg.setDamage(model.damage());

        if (model.hideAllFlags()) {
            meta.addItemFlags(ALL_FLAGS_ARRAY);
        } else if (!model.hideFlags().isEmpty()) {
            for (String flagName : model.hideFlags()) {
                try {
                    meta.addItemFlags(ItemFlag.valueOf(flagName.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {
                }
            }
        }

        if (model.stripAttributes()) {
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            for (Attribute attribute : ATTRIBUTES_ARRAY) {
                AttributeModifier mod = ZERO_MODIFIERS.get(attribute.name());
                if (mod != null) meta.addAttributeModifier(attribute, mod);
            }
        }

        item.setItemMeta(meta);
        markMenuIcon(item);
        return item;
    }

    public static boolean isMenuIcon(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        try {
            if (meta.getPersistentDataContainer().has(MENU_ICON_KEY, PersistentDataType.BYTE)) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        // Creative sync can drop PDC; fall back to material + CMD fingerprint.
        int cmd = meta.hasCustomModelData() ? meta.getCustomModelData() : 0;
        return MENU_FINGERPRINTS.contains(fingerprint(item.getType(), cmd));
    }

    private static void markMenuIcon(ItemStack item) {
        try {
            ItemMeta meta = item.getItemMeta();
            if (meta == null) return;
            meta.getPersistentDataContainer().set(MENU_ICON_KEY, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        } catch (Throwable ignored) {
        }
    }

    private static String parse(Player player, String text) {
        if (text == null || text.isEmpty()) return "";
        String parsed = text;
        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            parsed = PlaceholderAPI.setPlaceholders(player, parsed);
        }
        try {
            parsed = LegacyComponentSerializer.legacySection().serialize(
                    MiniMessage.miniMessage().deserialize(parsed)
            );
        } catch (Throwable ignored) {
        }
        return ChatColor.translateAlternateColorCodes('&', parsed);
    }
}
