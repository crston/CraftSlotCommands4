package com.gmail.bobason01.listener;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketContainer;
import com.gmail.bobason01.CraftSlotCommands;
import com.gmail.bobason01.util.ItemBuilder;
import com.gmail.bobason01.util.SchedulerUtil;
import com.gmail.bobason01.util.UpdateTaskPool;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.InventoryView;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CraftSlotFakeItemListener implements Listener {

    private final CraftSlotCommands plugin;
    private final Logger logger;

    // 플레이어 상태별(MAIN, STAT_PAGE 등) 독립된 데이터 렌더링 풀 구성
    private final Map<String, ItemStack[]> pageBaseFakeInventory = new ConcurrentHashMap<>();
    private final Map<String, boolean[]> pageFixedUsageArray = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, ItemStack>> pageMenuItems = new ConcurrentHashMap<>();

    private volatile boolean itemsEnabled = true;

    private final Map<UUID, Long> lastUpdate = new ConcurrentHashMap<>();
    private static final long MIN_UPDATE_INTERVAL_MS = 100L;
    private static final ItemStack AIR = new ItemStack(Material.AIR);

    public CraftSlotFakeItemListener(CraftSlotCommands plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    public void reload(FileConfiguration config) {
        this.itemsEnabled = config.getBoolean("items-enabled", true);
        pageBaseFakeInventory.clear();
        pageFixedUsageArray.clear();
        pageMenuItems.clear();

        ConfigurationSection rootPages = config.getConfigurationSection("menu-pages");
        if (rootPages == null) return;

        for (String pageKey : rootPages.getKeys(false)) {
            ConfigurationSection pageSec = rootPages.getConfigurationSection(pageKey);
            if (pageSec == null) continue;

            Map<Integer, ItemStack> menuItems = new HashMap<>();
            ItemStack[] baseFakeInventory = new ItemStack[45];
            Arrays.fill(baseFakeInventory, AIR);
            boolean[] fixedUsageArray = new boolean[5];

            ConfigurationSection useSlotSection = pageSec.getConfigurationSection("use-slot");
            if (useSlotSection != null) {
                for (String key : useSlotSection.getKeys(false)) {
                    try {
                        int slot = Integer.parseInt(key);
                        if (useSlotSection.getBoolean(key)) {
                            if (slot >= 0 && slot < 5) {
                                fixedUsageArray[slot] = true;
                            }
                            if (itemsEnabled) {
                                ItemStack item = ItemBuilder.get(pageKey, key);
                                menuItems.put(slot, item);
                                if (slot >= 0 && slot < 5) {
                                    baseFakeInventory[slot] = item.clone();
                                }
                            }
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }

            pageMenuItems.put(pageKey, menuItems);
            pageBaseFakeInventory.put(pageKey, baseFakeInventory);
            pageFixedUsageArray.put(pageKey, fixedUsageArray);
        }
    }

    private boolean shouldUpdate(Player player) {
        long now = System.currentTimeMillis();
        long last = lastUpdate.getOrDefault(player.getUniqueId(), 0L);
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
        if (!itemsEnabled) return;

        GameMode mode = player.getGameMode();
        InventoryView openInv = player.getOpenInventory();
        InventoryType invType = openInv.getType();

        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR || invType == InventoryType.CREATIVE) {
            return;
        }

        // 대형 GUI 플러그인 상점 메뉴와 마찰을 완전히 피하기 위해 탑 인벤토리 스펙 정밀 인스펙션
        Inventory topInv = openInv.getTopInventory();
        if (invType != InventoryType.CRAFTING || topInv.getSize() != 5) {
            return;
        }

        InventoryHolder holder = topInv.getHolder();
        if (!(holder instanceof Player) || !holder.equals(player)) {
            return;
        }

        String state = plugin.getPlayerState(player.getUniqueId());
        ItemStack[] baseInv = pageBaseFakeInventory.getOrDefault(state, pageBaseFakeInventory.get("MAIN"));
        if (baseInv == null) return;

        ItemStack[] contents = new ItemStack[45];
        System.arraycopy(baseInv, 0, contents, 0, 5);

        ItemStack[] inv = player.getInventory().getContents();

        contents[5] = safeRef(player.getInventory().getHelmet());
        contents[6] = safeRef(player.getInventory().getChestplate());
        contents[7] = safeRef(player.getInventory().getLeggings());
        contents[8] = safeRef(player.getInventory().getBoots());

        for (int i = 9; i <= 35; i++) {
            if (i < inv.length) contents[i] = safeRef(inv[i]);
        }

        for (int i = 0; i <= 8; i++) {
            contents[36 + i] = i < inv.length ? safeRef(inv[i]) : AIR;
        }

        PacketContainer packet = new PacketContainer(PacketType.Play.Server.WINDOW_ITEMS);
        packet.getIntegers().write(0, 0);

        if (packet.getIntegers().size() > 1) {
            packet.getIntegers().write(1, 1);
        }

        packet.getItemListModifier().write(0, Arrays.asList(contents));

        try {
            ProtocolLibrary.getProtocolManager().sendServerPacket(player, packet);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to send menu view", e);
        }
    }

    private void syncCursorItemAlways(Player player) {
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) return;

        InventoryView openInv = player.getOpenInventory();
        if (openInv.getType() != InventoryType.CRAFTING || openInv.getTopInventory().getSize() != 5) {
            return;
        }

        ItemStack cursor = player.getItemOnCursor();
        player.setItemOnCursor(cursor);

        PacketContainer packet = new PacketContainer(PacketType.Play.Server.SET_SLOT);
        packet.getIntegers().write(0, -1);

        int intSize = packet.getIntegers().size();
        if (intSize > 2) {
            packet.getIntegers().write(1, 1);
            packet.getIntegers().write(2, -1);
        } else {
            packet.getIntegers().write(1, -1);
        }

        packet.getItemModifier().write(0, cursor);

        try {
            ProtocolLibrary.getProtocolManager().sendServerPacket(player, packet);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to sync cursor", e);
        }
    }

    private ItemStack safeRef(ItemStack item) {
        return (item != null && item.getType() != Material.AIR) ? item : AIR;
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

        // 인벤토리를 완전히 닫으면 자동으로 다음 오픈을 위해 기본 상태인 MAIN 레이어로 회수합니다.
        plugin.setPlayerState(player.getUniqueId(), "MAIN");
        scheduleUpdate(player, 2L);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) return;

        int rawSlot = event.getRawSlot();
        String state = plugin.getPlayerState(player.getUniqueId());
        boolean[] usages = pageFixedUsageArray.get(state);

        if (event.getView().getType() == InventoryType.CRAFTING && rawSlot >= 0 && rawSlot < 5 && usages != null && usages[rawSlot]) {
            event.setCancelled(true);
        }
        UpdateTaskPool.scheduleCoalesced(player.getUniqueId(), 1L, () -> forceClientRefresh(player));
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Player player = (Player) event.getWhoClicked();
        if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) return;

        String state = plugin.getPlayerState(player.getUniqueId());
        boolean[] usages = pageFixedUsageArray.get(state);

        if (event.getView().getType() == InventoryType.CRAFTING && usages != null) {
            for (int rawSlot : event.getRawSlots()) {
                if (rawSlot >= 0 && rawSlot < 5 && usages[rawSlot]) {
                    event.setCancelled(true);
                    break;
                }
            }
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
            SchedulerUtil.runForPlayer(plugin, player, () -> {
                if (!player.isOnline()) return;

                String state = plugin.getPlayerState(player.getUniqueId());
                boolean[] usages = pageFixedUsageArray.get(state);
                ItemStack[] menuItemsArray = pageBaseFakeInventory.get(state);

                for (int slot = 0; slot < 5; slot++) {
                    if (usages == null || !usages[slot]) continue;
                    ItemStack expected = menuItemsArray[slot];
                    ItemStack current = player.getInventory().getItem(slot);
                    if (current != null && current.isSimilar(expected)) {
                        player.getInventory().setItem(slot, AIR);
                    }
                }

                ItemStack[] fullContents = player.getInventory().getContents();
                ItemStack[] contents = new ItemStack[45];
                Arrays.fill(contents, 0, 5, AIR);

                contents[5] = safeRef(player.getInventory().getHelmet());
                contents[6] = safeRef(player.getInventory().getChestplate());
                contents[7] = safeRef(player.getInventory().getLeggings());
                contents[8] = safeRef(player.getInventory().getBoots());

                for (int i = 9; i <= 35; i++) {
                    if (i < fullContents.length) contents[i] = safeRef(fullContents[i]);
                }

                for (int i = 0; i <= 8; i++) {
                    contents[36 + i] = i < fullContents.length ? safeRef(fullContents[i]) : AIR;
                }

                PacketContainer packet = new PacketContainer(PacketType.Play.Server.WINDOW_ITEMS);
                packet.getIntegers().write(0, 0);
                if (packet.getIntegers().size() > 1) {
                    packet.getIntegers().write(1, 1);
                }
                packet.getItemListModifier().write(0, Arrays.asList(contents));

                try {
                    ProtocolLibrary.getProtocolManager().sendServerPacket(player, packet);
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "Failed to clear fake inventory", e);
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