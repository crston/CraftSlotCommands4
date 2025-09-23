package com.gmail.bobason01.util;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;

public final class InventoryUtil {

    public static boolean isSelf2x2Crafting(InventoryView view) {
        Inventory top = view.getTopInventory();
        if (top.getType() != InventoryType.CRAFTING) {
            return false;
        }

        if (top.getSize() != 5) {
            return false;
        }

        Object viewer = view.getPlayer();
        InventoryHolder holder = top.getHolder();

        return viewer instanceof Player && viewer.equals(holder);
    }
}