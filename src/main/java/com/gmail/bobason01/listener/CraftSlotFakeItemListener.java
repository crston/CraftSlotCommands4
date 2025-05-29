package com.gmail.bobason01.listener;

import com.gmail.bobason01.util.ItemBuilder;
import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketContainer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CraftSlotFakeItemListener implements Listener {

    private final ItemStack[] fakeItems = new ItemStack[5];
    private final Plugin plugin;
    private final Logger logger;

    public CraftSlotFakeItemListener(FileConfiguration config, Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        reload(config);
    }

    public void reload(FileConfiguration config) {
        ItemBuilder.loadFromConfig(Objects.requireNonNull(config.getConfigurationSection("slot-item")));

        for (int i = 0; i <= 4; i++) {
            ItemStack base = ItemBuilder.get(String.valueOf(i));
            fakeItems[i] = base.clone();
        }
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (player.getGameMode() == GameMode.CREATIVE) return;
        if (event.getInventory().getType() != InventoryType.CRAFTING) return;

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            sendWindowItems(player);
            syncCursorItem(player);
        }, 2L);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (player.getGameMode() == GameMode.CREATIVE) return;
        if (event.getInventory().getType() != InventoryType.CRAFTING) return;

        int slot = event.getRawSlot();
        boolean blocked = slot >= 0 && slot <= 4 && fakeItems[slot] != null;

        if (blocked) {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.setItemOnCursor(new ItemStack(Material.AIR));
                sendWindowItems(player);
                Bukkit.getScheduler().runTaskLater(plugin, () -> syncCursorItem(player), 1L);
            });
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> {
                sendWindowItems(player);
                syncCursorItem(player);
            });
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (player.getGameMode() == GameMode.CREATIVE) return;
        if (event.getInventory().getType() != InventoryType.CRAFTING) return;

        boolean cancel = event.getRawSlots().stream().anyMatch(slot -> slot >= 0 && slot <= 4);

        if (cancel) {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(plugin, () -> {
                player.setItemOnCursor(new ItemStack(Material.AIR));
                sendWindowItems(player);
                Bukkit.getScheduler().runTaskLater(plugin, () -> syncCursorItem(player), 1L);
            });
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Player player = (Player) event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;
        if (event.getInventory().getType() != InventoryType.CRAFTING) return;

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            sendWindowItems(player);
            syncCursorItem(player);
        }, 2L);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            sendWindowItems(player);
            syncCursorItem(player);
        }, 3L);
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;
            sendWindowItems(player);
            syncCursorItem(player);
        }, 3L);
    }

    private void sendWindowItems(Player player) {
        ItemStack[] contents = new ItemStack[45];
        Arrays.fill(contents, new ItemStack(Material.AIR));

        for (int i = 0; i <= 4; i++) {
            contents[i] = fakeItems[i] != null ? fakeItems[i] : new ItemStack(Material.AIR);
        }

        contents[5] = safe(player.getInventory().getHelmet());
        contents[6] = safe(player.getInventory().getChestplate());
        contents[7] = safe(player.getInventory().getLeggings());
        contents[8] = safe(player.getInventory().getBoots());

        ItemStack[] inv = player.getInventory().getContents();
        for (int i = 9; i <= 35; i++) {
            if (i < inv.length) contents[i] = safe(inv[i]);
        }
        for (int i = 36; i <= 44; i++) {
            int index = i - 36;
            if (index < inv.length) contents[i] = safe(inv[index]);
        }

        PacketContainer packet = new PacketContainer(PacketType.Play.Server.WINDOW_ITEMS);
        packet.getIntegers().write(0, 0);
        packet.getItemListModifier().write(0, Arrays.asList(contents));

        try {
            ProtocolLibrary.getProtocolManager().sendServerPacket(player, packet);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to send window items to player: " + player.getName(), e);
        }
    }

    private void syncCursorItem(Player player) {
        ItemStack cursor = player.getItemOnCursor();

        player.setItemOnCursor(cursor);

        PacketContainer packet = new PacketContainer(PacketType.Play.Server.SET_SLOT);
        packet.getIntegers().write(0, -1);
        packet.getIntegers().write(1, -1);
        packet.getItemModifier().write(0, cursor);

        try {
            ProtocolLibrary.getProtocolManager().sendServerPacket(player, packet);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to send cursor item to player: " + player.getName(), e);
        }
    }

    private ItemStack safe(ItemStack item) {
        return item != null ? item : new ItemStack(Material.AIR);
    }
}
