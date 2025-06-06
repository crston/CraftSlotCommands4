package com.gmail.bobason01.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class ItemBuilder {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Logger LOGGER = Bukkit.getLogger();

    private static final ItemStack ERROR_ITEM_CLONE;
    private static final Map<String, ItemStack> CACHE = new HashMap<>();
    private static final Map<String, AttributeModifier> ZERO_MODIFIERS = new ConcurrentHashMap<>();
    private static final Map<String, Component> PARSE_CACHE = new ConcurrentHashMap<>();
    private static final String NON_ITALIC = "<italic:false>";

    static {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("ERROR", NamedTextColor.DARK_RED));
            meta.lore(List.of(Component.text("Check config", NamedTextColor.RED)));
            item.setItemMeta(meta);
        }
        ERROR_ITEM_CLONE = item.clone();
    }

    public static void loadFromConfig(ConfigurationSection root) {
        CACHE.clear();
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            if (section == null) continue;

            try {
                CACHE.put(key, buildRaw(section));
            } catch (Exception e) {
                log("Failed to build item for: " + key + " - " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
                CACHE.put(key, ERROR_ITEM_CLONE.clone());
            }
        }
    }

    public static ItemStack get(String key) {
        ItemStack original = CACHE.get(key);
        return original != null ? original.clone() : ERROR_ITEM_CLONE.clone();
    }

    private static ItemStack buildRaw(ConfigurationSection config) {
        String materialName = config.getString("material");
        if (materialName == null || materialName.isBlank()) {
            throw new IllegalArgumentException("Missing or blank material name");
        }

        Material mat = Material.matchMaterial(materialName);
        if (mat == null) throw new IllegalArgumentException("Invalid material: " + materialName);

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return ERROR_ITEM_CLONE.clone();

        if (config.contains("name")) {
            meta.displayName(parse(config.getString("name")));
        }

        if (config.contains("model")) {
            try {
                meta.setCustomModelData(config.getInt("model"));
            } catch (Exception e) {
                log("CustomModelData not supported on material: " + mat);
            }
        }

        // Hide attributes unless explicitly allowed
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);

        Object flagsObj = config.get("hide-flags");
        if (flagsObj instanceof Boolean bool && bool) {
            meta.addItemFlags(ItemFlag.values());
        } else if (flagsObj instanceof List<?> list) {
            for (Object obj : list) {
                if (obj instanceof String str) {
                    try {
                        String flagName = str.trim().toUpperCase(Locale.ROOT);
                        meta.addItemFlags(ItemFlag.valueOf(flagName));
                    } catch (IllegalArgumentException e) {
                        log("Unknown ItemFlag: " + str);
                    }
                }
            }
        }

        boolean stripAttributes = config.getBoolean("strip-attributes", true);
        if (stripAttributes) {
            for (Attribute attribute : Attribute.values()) {
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    String key = attribute.name() + ":" + slot.name();
                    AttributeModifier mod = ZERO_MODIFIERS.computeIfAbsent(key, k ->
                            new AttributeModifier(UUID.nameUUIDFromBytes(k.getBytes()), "zero_" + attribute.name().toLowerCase(), 0.0,
                                    AttributeModifier.Operation.ADD_NUMBER, slot));
                    try {
                        meta.addAttributeModifier(attribute, mod);
                    } catch (Exception e) {
                        log("Failed to apply modifier " + attribute + "@" + slot + ": " + e.getMessage());
                    }
                }
            }
        }

        if (config.getBoolean("unbreakable")) {
            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        }

        if (meta instanceof Damageable dmg && config.contains("damage")) {
            dmg.setDamage(config.getInt("damage"));
        }

        if (config.contains("lore")) {
            List<String> loreStrings = config.getStringList("lore");
            List<Component> lore = new ArrayList<>();
            for (String line : loreStrings) {
                lore.add(parse(line));
            }
            meta.lore(lore);
        }

        item.setItemMeta(meta);
        return item;
    }

    private static Component parse(String legacy) {
        if (legacy == null) return Component.empty();
        return PARSE_CACHE.computeIfAbsent(legacy, l -> MM.deserialize(convertLegacyToMiniMessage(l)));
    }

    private static String convertLegacyToMiniMessage(String input) {
        if (input == null || input.isEmpty()) return NON_ITALIC;
        StringBuilder sb = new StringBuilder(NON_ITALIC);
        char[] chars = input.toCharArray();

        for (int i = 0; i < chars.length; i++) {
            if (chars[i] == '&' && i + 1 < chars.length) {
                char code = Character.toLowerCase(chars[++i]);
                String tag = LEGACY_MAP.get(code);
                if (tag != null) {
                    sb.append(tag).append(NON_ITALIC);
                    continue;
                }
            }
            sb.append(chars[i]);
        }

        return sb.toString();
    }

    private static final Map<Character, String> LEGACY_MAP = Map.ofEntries(
            Map.entry('0', "<black>"), Map.entry('1', "<dark_blue>"),
            Map.entry('2', "<dark_green>"), Map.entry('3', "<dark_aqua>"),
            Map.entry('4', "<dark_red>"), Map.entry('5', "<dark_purple>"),
            Map.entry('6', "<gold>"), Map.entry('7', "<gray>"),
            Map.entry('8', "<dark_gray>"), Map.entry('9', "<blue>"),
            Map.entry('a', "<green>"), Map.entry('b', "<aqua>"),
            Map.entry('c', "<red>"), Map.entry('d', "<light_purple>"),
            Map.entry('e', "<yellow>"), Map.entry('f', "<white>"),
            Map.entry('l', "<bold>"), Map.entry('m', "<strikethrough>"),
            Map.entry('n', "<underlined>"), Map.entry('o', "<italic>"),
            Map.entry('r', "<reset>")
    );

    private static void log(String msg) {
        LOGGER.warning("[CSC4] " + msg);
    }
}
