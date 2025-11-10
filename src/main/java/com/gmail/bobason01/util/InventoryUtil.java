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

        // 플레이어의 개인 제작창은 항상 CRAFTING 타입이며 슬롯 5개 (2x2 grid + 결과 1칸)
        if (top.getType() != InventoryType.CRAFTING || top.getSize() != 5) {
            return false;
        }

        InventoryHolder holder = top.getHolder();
        if (!(view.getPlayer() instanceof Player player)) {
            return false;
        }

        // 자신이 연 개인 제작창인지 확인
        return holder == player;
    }
}
