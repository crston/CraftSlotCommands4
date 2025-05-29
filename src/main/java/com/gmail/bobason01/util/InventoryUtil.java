package com.gmail.bobason01.util;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;

public class InventoryUtil {

    public static boolean isSelf2x2Crafting(InventoryView view) {
        Inventory inv = view.getTopInventory();
        return inv instanceof CraftingInventory
                && inv.getSize() == 5
                && inv.getType() == InventoryType.CRAFTING
                && view.getPlayer() instanceof Player p
                && p.equals(inv.getHolder());
    }
}
