package com.gmail.bobason01.util;

import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

public final class MenuSlots {

    public static final int CRAFT_MIN = 0;
    public static final int CRAFT_MAX = 4;
    public static final int ARMOR_MIN = 5;
    public static final int ARMOR_MAX = 8;
    public static final int MENU_MIN = 0;
    public static final int MENU_MAX = 8;
    public static final int SLOT_COUNT = 9;

    private MenuSlots() {}

    public static boolean isCraftSlot(int rawSlot) {
        return rawSlot >= CRAFT_MIN && rawSlot <= CRAFT_MAX;
    }

    public static boolean isArmorSlot(int rawSlot) {
        return rawSlot >= ARMOR_MIN && rawSlot <= ARMOR_MAX;
    }

    public static boolean isMenuRange(int rawSlot) {
        return rawSlot >= MENU_MIN && rawSlot <= MENU_MAX;
    }

    public static EquipmentSlot toPacketEquipmentSlot(int armorRawSlot) {
        return switch (armorRawSlot) {
            case 5 -> EquipmentSlot.HELMET;
            case 6 -> EquipmentSlot.CHEST_PLATE;
            case 7 -> EquipmentSlot.LEGGINGS;
            case 8 -> EquipmentSlot.BOOTS;
            default -> null;
        };
    }

    public static Integer armorRawSlotForMaterial(Material material) {
        if (material == null || material.isAir()) return null;
        org.bukkit.inventory.EquipmentSlot slot;
        try {
            slot = material.getEquipmentSlot();
        } catch (Exception e) {
            return null;
        }
        if (slot == null) return null;
        return switch (slot) {
            case HEAD -> 5;
            case CHEST -> 6;
            case LEGS -> 7;
            case FEET -> 8;
            default -> null;
        };
    }

    public static Integer armorRawSlotForItem(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        return armorRawSlotForMaterial(item.getType());
    }
}
