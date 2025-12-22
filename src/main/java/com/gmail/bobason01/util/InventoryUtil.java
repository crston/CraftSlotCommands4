package com.gmail.bobason01.util;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;

public final class InventoryUtil {

    private InventoryUtil() {
    }

    public static boolean isSelf2x2Crafting(InventoryView view) {
        Inventory top = view.getTopInventory();
        if (top.getType() != InventoryType.CRAFTING || top.getSize() != 5) {
            return false;
        }

        InventoryHolder holder = top.getHolder();
        if (!(view.getPlayer() instanceof Player player)) {
            return false;
        }

        return holder == player;
    }
}