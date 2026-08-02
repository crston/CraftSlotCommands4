package com.gmail.bobason01.listener;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetSlot;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerWindowItems;
import com.gmail.bobason01.CraftSlotCommands;
import com.gmail.bobason01.util.ItemBuilder;
import com.gmail.bobason01.util.MenuSlots;
import com.gmail.bobason01.util.SchedulerUtil;
import com.gmail.bobason01.util.UpdateTaskPool;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Craft-slot (0–4) fake menu items only. No armor packet path.
 * Hot path: coalesced WindowItems refresh, early returns, minimal alloc.
 */
public class CraftSlotFakeItemListener implements Listener {

    private static final org.bukkit.inventory.ItemStack AIR = new org.bukkit.inventory.ItemStack(Material.AIR);
    private static final int WINDOW_SIZE = 46;
    private static final long MIN_UPDATE_INTERVAL_MS = 50L;

    private final CraftSlotCommands plugin;
    private final Logger logger;
    private final Map<String, boolean[]> pageUsage = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastUpdate = new ConcurrentHashMap<>();
    private volatile boolean itemsEnabled = true;

    public CraftSlotFakeItemListener(CraftSlotCommands plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public void reload(FileConfiguration config) {
        this.itemsEnabled = config.getBoolean("items-enabled", true);
        pageUsage.clear();

        ConfigurationSection rootPages = config.getConfigurationSection("menu-pages");
        if (rootPages != null) {
            for (String pageKey : rootPages.getKeys(false)) {
                ConfigurationSection pageSec = rootPages.getConfigurationSection(pageKey);
                if (pageSec == null) continue;
                boolean[] usage = new boolean[MenuSlots.SLOT_COUNT];
                ConfigurationSection useSlot = pageSec.getConfigurationSection("use-slot");
                if (useSlot != null) {
                    for (String key : useSlot.getKeys(false)) {
                        try {
                            int slot = Integer.parseInt(key);
                            if (MenuSlots.isCraftSlot(slot) && useSlot.getBoolean(key)) {
                                usage[slot] = true;
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                pageUsage.put(pageKey, usage);
            }
        }

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            forceClientRefresh(player);
        }
    }

    public void shutdown() {
        pageUsage.clear();
        lastUpdate.clear();
    }

    private boolean[] usagesFor(Player player) {
        return pageUsage.get(plugin.getPlayerState(player.getUniqueId()));
    }

    private boolean isMenuSlot(Player player, int rawSlot) {
        if (!MenuSlots.isCraftSlot(rawSlot)) return false;
        boolean[] usages = usagesFor(player);
        return usages != null && rawSlot < usages.length && usages[rawSlot];
    }

    private boolean shouldUpdate(Player player) {
        long now = System.currentTimeMillis();
        Long prev = lastUpdate.get(player.getUniqueId());
        if (prev != null && now - prev < MIN_UPDATE_INTERVAL_MS) return false;
        lastUpdate.put(player.getUniqueId(), now);
        return true;
    }

    public void scheduleUpdate(Player player, long delayTicks) {
        UpdateTaskPool.scheduleCoalesced(player.getUniqueId(), delayTicks, () -> {
            if (!player.isOnline()) return;
            GameMode mode = player.getGameMode();
            if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) return;
            if (shouldUpdate(player)) {
                sendMenuViewIfNeeded(player);
                syncCursor(player);
            }
        });
    }

    public void forceClientRefresh(Player player) {
        if (!player.isOnline()) return;
        GameMode mode = player.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) return;
        sendMenuViewIfNeeded(player);
        syncCursor(player);
    }

    private ItemStack toPe(org.bukkit.inventory.ItemStack bukkit) {
        try {
            if (bukkit == null || bukkit.getType().isAir()) {
                return SpigotConversionUtil.fromBukkitItemStack(AIR);
            }
            return SpigotConversionUtil.fromBukkitItemStack(bukkit);
        } catch (Exception e) {
            return ItemStack.EMPTY;
        }
    }

    private org.bukkit.inventory.ItemStack safeRef(org.bukkit.inventory.ItemStack item) {
        return (item != null && item.getType() != Material.AIR) ? item : AIR;
    }

    private void sendMenuViewIfNeeded(Player player) {
        if (!itemsEnabled) return;

        InventoryView view = player.getOpenInventory();
        if (view.getType() != InventoryType.CRAFTING) return;
        Inventory top = view.getTopInventory();
        if (top.getSize() != 5) return;
        InventoryHolder holder = top.getHolder();
        if (!(holder instanceof Player) || !holder.equals(player)) return;

        String state = plugin.getPlayerState(player.getUniqueId());
        boolean[] usages = usagesFor(player);

        org.bukkit.inventory.ItemStack[] contents = new org.bukkit.inventory.ItemStack[WINDOW_SIZE];
        Arrays.fill(contents, AIR);

        if (usages != null) {
            for (int i = MenuSlots.CRAFT_MIN; i <= MenuSlots.CRAFT_MAX; i++) {
                if (usages[i]) {
                    contents[i] = ItemBuilder.get(player, state, String.valueOf(i));
                }
            }
        }

        org.bukkit.inventory.ItemStack[] storage = player.getInventory().getStorageContents();
        contents[5] = safeRef(player.getInventory().getHelmet());
        contents[6] = safeRef(player.getInventory().getChestplate());
        contents[7] = safeRef(player.getInventory().getLeggings());
        contents[8] = safeRef(player.getInventory().getBoots());

        for (int i = 9; i <= 35; i++) {
            if (storage != null && i < storage.length) contents[i] = safeRef(storage[i]);
        }
        for (int i = 0; i <= 8; i++) {
            contents[36 + i] = (storage != null && i < storage.length) ? safeRef(storage[i]) : AIR;
        }
        contents[45] = safeRef(player.getInventory().getItemInOffHand());

        List<ItemStack> peItems = new ArrayList<>(WINDOW_SIZE);
        for (org.bukkit.inventory.ItemStack stack : contents) {
            peItems.add(toPe(stack));
        }

        try {
            PacketEvents.getAPI().getPlayerManager().sendPacket(
                    player,
                    new WrapperPlayServerWindowItems(0, 1, peItems, toPe(player.getItemOnCursor()))
            );
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to send craft menu view", e);
        }
    }

    private void syncCursor(Player player) {
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) return;
        InventoryView view = player.getOpenInventory();
        if (view.getType() != InventoryType.CRAFTING || view.getTopInventory().getSize() != 5) return;

        try {
            PacketEvents.getAPI().getPlayerManager().sendPacket(
                    player,
                    new WrapperPlayServerSetSlot(-1, 1, -1, toPe(player.getItemOnCursor()))
            );
        } catch (Exception ignored) {
        }
    }

    private void stripMenuIcons(Player player) {
        org.bukkit.inventory.ItemStack[] storage = player.getInventory().getStorageContents();
        if (storage != null) {
            boolean changed = false;
            for (int i = 0; i < storage.length; i++) {
                if (ItemBuilder.isMenuIcon(storage[i])) {
                    storage[i] = null;
                    changed = true;
                }
            }
            if (changed) player.getInventory().setStorageContents(storage);
        }
        if (ItemBuilder.isMenuIcon(player.getInventory().getItemInOffHand())) {
            player.getInventory().setItemInOffHand(null);
        }
        if (ItemBuilder.isMenuIcon(player.getItemOnCursor())) {
            player.setItemOnCursor(AIR.clone());
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Player player = (Player) event.getPlayer();
        if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) return;
        plugin.setPlayerState(player.getUniqueId(), "MAIN");
        scheduleUpdate(player, 2L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) return;
        if (event.getView().getType() != InventoryType.CRAFTING) return;

        int raw = event.getRawSlot();
        boolean blocked = isMenuSlot(player, raw)
                || ItemBuilder.isMenuIcon(event.getCursor())
                || ItemBuilder.isMenuIcon(event.getCurrentItem());

        if (!blocked) {
            UpdateTaskPool.scheduleCoalesced(player.getUniqueId(), 1L, () -> forceClientRefresh(player));
            return;
        }

        event.setCancelled(true);
        try {
            event.setResult(org.bukkit.event.Event.Result.DENY);
        } catch (Throwable ignored) {
        }

        final org.bukkit.inventory.ItemStack cursorKeep =
                ItemBuilder.isMenuIcon(player.getItemOnCursor()) ? null
                        : (player.getItemOnCursor() == null || player.getItemOnCursor().getType().isAir()
                        ? null : player.getItemOnCursor().clone());

        if (ItemBuilder.isMenuIcon(player.getItemOnCursor())) {
            player.setItemOnCursor(AIR.clone());
        }

        SchedulerUtil.runForPlayer(plugin, player, () -> {
            if (!player.isOnline()) return;
            stripMenuIcons(player);
            if (cursorKeep != null) player.setItemOnCursor(cursorKeep);
            forceClientRefresh(player);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) return;
        if (event.getView().getType() != InventoryType.CRAFTING) return;

        for (int raw : event.getRawSlots()) {
            if (isMenuSlot(player, raw)) {
                event.setCancelled(true);
                break;
            }
        }
        if (ItemBuilder.isMenuIcon(event.getOldCursor()) || ItemBuilder.isMenuIcon(event.getCursor())) {
            event.setCancelled(true);
        }
        if (event.isCancelled()) {
            SchedulerUtil.runForPlayer(plugin, player, () -> {
                stripMenuIcons(player);
                forceClientRefresh(player);
            });
        }
        UpdateTaskPool.scheduleCoalesced(player.getUniqueId(), 1L, () -> forceClientRefresh(player));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDropMenuIcon(org.bukkit.event.player.PlayerDropItemEvent event) {
        if (!ItemBuilder.isMenuIcon(event.getItemDrop().getItemStack())) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        SchedulerUtil.runForPlayer(plugin, player, () -> {
            stripMenuIcons(player);
            forceClientRefresh(player);
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) return;
        scheduleUpdate(player, 3L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        lastUpdate.remove(event.getPlayer().getUniqueId());
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
        GameMode neu = event.getNewGameMode();
        if (neu == GameMode.CREATIVE || neu == GameMode.SPECTATOR) {
            SchedulerUtil.runForPlayer(plugin, player, () -> {
                if (!player.isOnline()) return;
                stripMenuIcons(player);
                player.closeInventory();
            });
            return;
        }
        UpdateTaskPool.scheduleCoalesced(player.getUniqueId(), 2L, () -> {
            if (player.isOnline() && player.getGameMode() == neu) {
                forceClientRefresh(player);
            }
        });
    }
}
