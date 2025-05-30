package com.gmail.bobason01.listener;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketContainer;
import com.destroystokyo.paper.event.player.PlayerRecipeBookClickEvent;
import com.gmail.bobason01.util.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CraftSlotFakeItemListener implements Listener {

    private final Plugin plugin;
    private final Logger logger;
    private final Map<Integer, ItemStack> menuItems = new HashMap<>();
    private final Set<Integer> activeMenuSlots = new HashSet<>();
    private final ItemStack[] baseFakeInventory = new ItemStack[45];
    private final Map<UUID, Long> lastUpdate = new HashMap<>();
    private static final long MIN_UPDATE_INTERVAL_MS = 100L;

    public CraftSlotFakeItemListener(FileConfiguration config, Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        reload(config);
    }

    public void reload(FileConfiguration config) {
        activeMenuSlots.clear();
        menuItems.clear();

        ConfigurationSection itemSection = config.getConfigurationSection("slot-item");
        if (itemSection != null) {
            ItemBuilder.loadFromConfig(itemSection);
        }

        ConfigurationSection useSlotSection = config.getConfigurationSection("use-slot");
        if (useSlotSection != null) {
            for (String key : useSlotSection.getKeys(false)) {
                try {
                    int slot = Integer.parseInt(key);
                    if (useSlotSection.getBoolean(key)) {
                        activeMenuSlots.add(slot);
                        ItemStack item = ItemBuilder.get(key);
                        menuItems.put(slot, item);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        Arrays.fill(baseFakeInventory, new ItemStack(Material.AIR));
        for (int i = 0; i <= 4; i++) {
            if (menuItems.containsKey(i)) {
                baseFakeInventory[i] = menuItems.get(i).clone();
            }
        }
    }

    private boolean shouldUpdate(Player player) {
        long now = System.currentTimeMillis();
        UUID uuid = player.getUniqueId();
        long last = lastUpdate.getOrDefault(uuid, 0L);
        if (now - last < MIN_UPDATE_INTERVAL_MS) return false;
        lastUpdate.put(uuid, now);
        return true;
    }

    private void scheduleUpdate(Player player, long delayTicks) {
        if (player.getGameMode() == GameMode.CREATIVE) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (player.isOnline() && shouldUpdate(player)) {
                sendMenuViewIfNeeded(player);
                syncCursorItemAlways(player);
            }
        }, delayTicks);
    }

    private void sendMenuViewIfNeeded(Player player) {
        if (player.getOpenInventory().getType() != InventoryType.CRAFTING) return;

        ItemStack[] contents = new ItemStack[45];
        System.arraycopy(baseFakeInventory, 0, contents, 0, 5);

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
            logger.log(Level.SEVERE, "Failed to send menu view to player: " + player.getName(), e);
        }
    }

    private void syncCursorItemAlways(Player player) {
        ItemStack cursor = player.getItemOnCursor();

        player.setItemOnCursor(cursor);

        PacketContainer packet = new PacketContainer(PacketType.Play.Server.SET_SLOT);
        packet.getIntegers().write(0, -1);
        packet.getIntegers().write(1, -1);
        packet.getItemModifier().write(0, cursor);

        try {
            ProtocolLibrary.getProtocolManager().sendServerPacket(player, packet);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to sync cursor for player: " + player.getName(), e);
        }
    }

    private ItemStack safe(ItemStack item) {
        return item != null ? item : new ItemStack(Material.AIR);
    }

    @EventHandler
    public void onRecipeBookClick(PlayerRecipeBookClickEvent event) {
        event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        scheduleUpdate((Player) event.getPlayer(), 2L);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        if (event.getView().getType() == InventoryType.CRAFTING && activeMenuSlots.contains(event.getRawSlot())) {
            event.setCancelled(true);
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (shouldUpdate(player)) {
                sendMenuViewIfNeeded(player);
                syncCursorItemAlways(player);
            }
        });
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Player player = (Player) event.getWhoClicked();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        if (event.getView().getType() == InventoryType.CRAFTING &&
                event.getRawSlots().stream().anyMatch(activeMenuSlots::contains)) {
            event.setCancelled(true);
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (shouldUpdate(player)) {
                sendMenuViewIfNeeded(player);
                syncCursorItemAlways(player);
            }
        });
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        scheduleUpdate((Player) event.getPlayer(), 2L);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        scheduleUpdate(event.getPlayer(), 3L);
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        scheduleUpdate(event.getPlayer(), 3L);
    }
}
