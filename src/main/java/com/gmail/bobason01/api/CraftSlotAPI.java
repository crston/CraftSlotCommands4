package com.gmail.bobason01.api;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

public interface CraftSlotAPI {

    ItemStack getFakeItem(int slot);

    boolean isFakeSlot(int slot);

    boolean isMenuClick(InventoryClickEvent event);

    void forceUpdatePlayerView(Player player);

    void applyModelIntegration(ItemMeta meta, int customModelData, String itemModelKey);

}