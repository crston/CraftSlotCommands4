package com.gmail.bobason01.util;

import net.kyori.adventure.text.Component;
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

public final class ItemBuilder {

    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final Logger LOGGER = Bukkit.getLogger();
    private static final String LOGGER_PREFIX = "[EcoSystem | ItemBuilder] ";

    private static final ItemStack ERROR_ITEM_CLONE;
    private static final Map<String, ItemStack> CACHE = new ConcurrentHashMap<>();
    private static final Map<String, AttributeModifier> ZERO_MODIFIERS = new HashMap<>();

    // LRU 캐시 (최대 512개까지 저장)
    private static final Map<String, Component> PARSE_CACHE =
            Collections.synchronizedMap(new LinkedHashMap<>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Component> eldest) {
                    return size() > 512;
                }
            });

    private static final String NON_ITALIC = "<italic:false>";
    private static final EnumSet<ItemFlag> ALL_FLAGS = EnumSet.allOf(ItemFlag.class);

    static {
        // 기본 에러 아이템 설정
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MM.deserialize("<dark_red>ERROR"));
        meta.lore(List.of(MM.deserialize("<red>Check plugin configuration.")));
        item.setItemMeta(meta);
        ERROR_ITEM_CLONE = item;

        // ZERO_MODIFIERS 미리 초기화 (런타임 compute 비용 제거)
        for (Attribute attribute : Attribute.values()) {
            for (EquipmentSlot slot : EquipmentSlot.values()) {
                String key = attribute.name() + ":" + slot.name();
                ZERO_MODIFIERS.put(key, new AttributeModifier(
                        UUID.nameUUIDFromBytes(key.getBytes()),
                        "zero_" + attribute.name().toLowerCase(Locale.ROOT),
                        0.0,
                        AttributeModifier.Operation.ADD_NUMBER,
                        slot
                ));
            }
        }
    }

    private ItemBuilder() {
    }

    public static void loadFromConfig(ConfigurationSection root) {
        CACHE.clear();
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
                        section.getInt("damage"),
                        section.getBoolean("unbreakable"),
                        section.getBoolean("strip-attributes"),
                        section.getBoolean("hide-all-flags"),
                        section.getStringList("hide-flags")
                );
                CACHE.put(key, build(model));
            } catch (Exception e) {
                log("Failed to build item '" + key + "': " + e.getMessage());
                CACHE.put(key, ERROR_ITEM_CLONE.clone());
            }
        }
    }

    public static ItemStack get(String key) {
        ItemStack original = CACHE.get(key);
        return original != null ? original.clone() : ERROR_ITEM_CLONE.clone();
    }

    public static ItemStack build(ItemModel model) {
        if (model.material() == null || model.material().isBlank()) {
            throw new IllegalArgumentException("Material field is missing or blank");
        }

        Material mat = Material.matchMaterial(model.material());
        if (mat == null) throw new IllegalArgumentException("Invalid material: " + model.material());

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item;

        if (model.name() != null) {
            meta.displayName(parse(model.name()));
        }
        if (model.model() != 0) {
            meta.setCustomModelData(model.model());
        }
        if (model.lore() != null && !model.lore().isEmpty()) {
            meta.lore(model.lore().stream().map(ItemBuilder::parse).toList());
        }
        if (model.unbreakable()) {
            meta.setUnbreakable(true);
        }
        if (meta instanceof Damageable dmg && model.damage() > 0) {
            dmg.setDamage(model.damage());
        }

        if (model.hideAllFlags()) {
            meta.addItemFlags(ALL_FLAGS.toArray(new ItemFlag[0]));
        } else if (model.hideFlags() != null) {
            for (String flagName : model.hideFlags()) {
                try {
                    meta.addItemFlags(ItemFlag.valueOf(flagName.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    log("Unknown ItemFlag: " + flagName);
                }
            }
        }

        if (model.stripAttributes()) {
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            for (Attribute attribute : Attribute.values()) {
                for (EquipmentSlot slot : EquipmentSlot.values()) {
                    String key = attribute.name() + ":" + slot.name();
                    AttributeModifier mod = ZERO_MODIFIERS.get(key);
                    if (mod != null) meta.addAttributeModifier(attribute, mod);
                }
            }
        }

        item.setItemMeta(meta);
        return item;
    }

    private static Component parse(String text) {
        if (text == null || text.isEmpty()) return Component.empty();
        return PARSE_CACHE.computeIfAbsent(text, t -> MM.deserialize(convertLegacyToMiniMessage(t)));
    }

    private static String convertLegacyToMiniMessage(String input) {
        if (input == null || input.isEmpty()) return NON_ITALIC;

        StringBuilder sb = new StringBuilder(NON_ITALIC);
        boolean lastWasColor = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '&' && i + 1 < input.length()) {
                char code = Character.toLowerCase(input.charAt(++i));
                String tag = LEGACY_MAP.get(code);
                if (tag != null) {
                    sb.append(tag);
                    if (code <= 'f' || code == 'r') {
                        lastWasColor = true;
                    }
                    continue;
                }
            }
            if (lastWasColor) {
                sb.append(NON_ITALIC);
                lastWasColor = false;
            }
            sb.append(c);
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
            Map.entry('k', "<obfuscated>"), Map.entry('l', "<bold>"),
            Map.entry('m', "<strikethrough>"), Map.entry('n', "<underlined>"),
            Map.entry('o', "<italic>"), Map.entry('r', "<reset>")
    );

    private static void log(String message) {
        LOGGER.warning(LOGGER_PREFIX + message);
    }
}
