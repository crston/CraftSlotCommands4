package com.gmail.bobason01.listener;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketContainer;
import com.destroystokyo.paper.event.player.PlayerRecipeBookClickEvent;
import com.gmail.bobason01.util.ItemBuilder;
import com.gmail.bobason01.util.UpdateTaskPool;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
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

    private final Object2LongOpenHashMap<UUID> lastUpdate = new Object2LongOpenHashMap<>();
    private static final long MIN_UPDATE_INTERVAL_MS = 100L;

    public CraftSlotFakeItemListener(FileConfiguration config, Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        lastUpdate.defaultReturnValue(0L);
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
        long last = lastUpdate.getLong(player.getUniqueId());
        if (now - last < MIN_UPDATE_INTERVAL_MS) return false;
        lastUpdate.put(player.getUniqueId(), now);
        return true;
    }

    private void scheduleUpdate(Player player, long delayTicks) {
        GameMode mode = player.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) return;

        UpdateTaskPool.scheduleCoalesced(player.getUniqueId(), delayTicks, () -> {
            if (!player.isOnline()) return;
            GameMode currentMode = player.getGameMode();
            if (currentMode != GameMode.SURVIVAL && currentMode != GameMode.ADVENTURE) return;
            if (shouldUpdate(player)) {
                sendMenuViewIfNeeded(player);
                syncCursorItemAlways(player);
            }
        });
    }

    public void forceClientRefresh(Player player) {
        if (!player.isOnline()) return;
        GameMode currentMode = player.getGameMode();
        if (currentMode != GameMode.SURVIVAL && currentMode != GameMode.ADVENTURE) return;
        sendMenuViewIfNeeded(player);
        syncCursorItemAlways(player);
    }

    private void sendMenuViewIfNeeded(Player player) {
        GameMode mode = player.getGameMode();
        InventoryType invType = player.getOpenInventory().getType();

        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR
                || invType == InventoryType.CREATIVE
                || invType == InventoryType.PLAYER) {
            return;
        }

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

        for (int i = 0; i <= 8; i++) {
            contents[36 + i] = i < inv.length ? safe(inv[i]) : new ItemStack(Material.AIR);
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
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) return;

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
        InventoryType type = event.getPlayer().getOpenInventory().getTopInventory().getType();
        if (type == InventoryType.CRAFTING) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        Player player = (Player) event.getPlayer();
        if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) return;
        scheduleUpdate(player, 2L);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Player player = (Player) event.getPlayer();
        if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) return;
        scheduleUpdate(player, 2L);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) return;

        if (event.getView().getType() == InventoryType.CRAFTING && activeMenuSlots.contains(event.getRawSlot())) {
            event.setCancelled(true);
        }
        UpdateTaskPool.scheduleCoalesced(player.getUniqueId(), 1L, () -> forceClientRefresh(player));
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Player player = (Player) event.getWhoClicked();
        if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) return;

        if (event.getView().getType() == InventoryType.CRAFTING &&
                event.getRawSlots().stream().anyMatch(activeMenuSlots::contains)) {
            event.setCancelled(true);
        }
        UpdateTaskPool.scheduleCoalesced(player.getUniqueId(), 1L, () -> forceClientRefresh(player));
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) return;
        scheduleUpdate(player, 3L);
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) return;
        scheduleUpdate(player, 3L);
    }

    @EventHandler
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        Player player = event.getPlayer();
        GameMode oldMode = player.getGameMode();
        GameMode newMode = event.getNewGameMode();

        if (newMode == GameMode.CREATIVE || newMode == GameMode.SPECTATOR) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;

                for (Map.Entry<Integer, ItemStack> entry : menuItems.entrySet()) {
                    int slot = entry.getKey();
                    ItemStack expected = entry.getValue();
                    ItemStack current = player.getInventory().getItem(slot);
                    if (current != null && current.isSimilar(expected)) {
                        player.getInventory().setItem(slot, new ItemStack(Material.AIR));
                    }
                }

                ItemStack[] fullContents = player.getInventory().getContents();
                ItemStack[] armor = new ItemStack[] {
                        safe(player.getInventory().getHelmet()),
                        safe(player.getInventory().getChestplate()),
                        safe(player.getInventory().getLeggings()),
                        safe(player.getInventory().getBoots())
                };

                ItemStack[] contents = new ItemStack[45];
                for (int i = 0; i <= 4; i++) contents[i] = new ItemStack(Material.AIR);

                contents[5] = armor[0];
                contents[6] = armor[1];
                contents[7] = armor[2];
                contents[8] = armor[3];

                for (int i = 9; i <= 35; i++) {
                    if (i < fullContents.length) contents[i] = safe(fullContents[i]);
                }

                for (int i = 0; i <= 8; i++) {
                    contents[36 + i] = i < fullContents.length ? safe(fullContents[i]) : new ItemStack(Material.AIR);
                }

                PacketContainer packet = new PacketContainer(PacketType.Play.Server.WINDOW_ITEMS);
                packet.getIntegers().write(0, 0);
                packet.getItemListModifier().write(0, Arrays.asList(contents));

                try {
                    ProtocolLibrary.getProtocolManager().sendServerPacket(player, packet);
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "Failed to send cleared inventory to player: " + player.getName(), e);
                }

                player.closeInventory();
            });
            return;
        }

        if ((oldMode == GameMode.CREATIVE || oldMode == GameMode.SPECTATOR)
                && (newMode == GameMode.SURVIVAL || newMode == GameMode.ADVENTURE)) {
            UpdateTaskPool.scheduleCoalesced(player.getUniqueId(), 2L, () -> {
                if (player.isOnline() && player.getGameMode() == newMode) {
                    forceClientRefresh(player);
                }
            });
        }
    }
}
