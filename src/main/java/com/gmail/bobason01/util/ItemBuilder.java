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

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public final class ItemBuilder {

    private static final Logger LOGGER = Bukkit.getLogger();
    private static final String LOGGER_PREFIX = "ItemBuilder ";

    private static final ItemStack ERROR_ITEM;
    private static final Map<String, Map<String, ItemModel>> PAGE_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, AttributeModifier> ZERO_MODIFIERS = new HashMap<>();

    private static final ItemFlag[] ALL_FLAGS_ARRAY = ItemFlag.values();
    private static final Attribute[] ATTRIBUTES_ARRAY = Attribute.values();

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
            NamespacedKey namespacedKey = NamespacedKey.minecraft("zero_" + keyLower);

            ZERO_MODIFIERS.put(key, new AttributeModifier(
                    namespacedKey,
                    0.0,
                    AttributeModifier.Operation.ADD_NUMBER,
                    EquipmentSlotGroup.ANY
            ));
        }
    }

    private ItemBuilder() {}

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
            } catch (Exception e) {
                LOGGER.warning(LOGGER_PREFIX + "Failed to load item model " + key + " on page " + pageState);
            }
        }
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
            List<String> parsedLore = new ArrayList<>();
            for (String line : model.lore()) {
                parsedLore.add(parse(player, line));
            }
            meta.setLore(parsedLore);
        }
        if (model.unbreakable()) {
            meta.setUnbreakable(true);
        }
        if (meta instanceof Damageable dmg && model.damage() > 0) {
            dmg.setDamage(model.damage());
        }

        if (model.hideAllFlags()) {
            meta.addItemFlags(ALL_FLAGS_ARRAY);
        } else if (!model.hideFlags().isEmpty()) {
            for (String flagName : model.hideFlags()) {
                try {
                    meta.addItemFlags(ItemFlag.valueOf(flagName.trim().toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ignored) {}
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
        return item;
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
        } catch (Throwable ignored) {}

        return ChatColor.translateAlternateColorCodes('&', parsed);
    }
}