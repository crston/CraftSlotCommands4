package com.gmail.bobason01.util;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.InventoryHolder;

public class InventoryUtil {

    public static boolean isSelf2x2Crafting(InventoryView view) {

        // 가장 빠르게 실패할 수 있는 조건부터 체크
        Inventory top = view.getTopInventory();
        if (top.getType() != InventoryType.CRAFTING) return false;

        // 정해진 크기 (5슬롯: 2x2 crafting + result)
        if (top.getSize() != 5) return false;

        // 두 객체 모두 Player 여야 함
        Object viewer = view.getPlayer();
        InventoryHolder holder = top.getHolder();

        // 빠른 instanceof + equals 체크
        return viewer instanceof Player v && holder instanceof Player h && v == h;
    }
}
