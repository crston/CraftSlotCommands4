package com.gmail.bobason01.listener;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketContainer;
import com.destroystokyo.paper.event.player.PlayerRecipeBookClickEvent;
import com.gmail.bobason01.util.ItemBuilder;
import com.gmail.bobason01.util.SchedulerUtil;
import com.gmail.bobason01.util.UpdateTaskPool;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
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
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.Arrays;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

// 가짜 아이템을 렌더링하고 패킷을 제어하는 리스너입니다
public class CraftSlotFakeItemListener implements Listener {

    private final Plugin plugin;
    private final Logger logger;

    // 성능 최적화를 위해 Fastutil 맵을 사용하고 락 프리 교체 방식을 적용합니다
    private volatile Int2ObjectMap<ItemStack> menuItems = Int2ObjectMaps.emptyMap();
    private volatile IntSet activeMenuSlots = IntSets.emptySet();
    private volatile ItemStack[] baseFakeInventory = new ItemStack[45];
    private volatile boolean itemsEnabled = true;

    private final Object2LongOpenHashMap<UUID> lastUpdate = new Object2LongOpenHashMap<>();
    private static final long MIN_UPDATE_INTERVAL_MS = 100L;
    private static final ItemStack AIR = new ItemStack(Material.AIR);

    public CraftSlotFakeItemListener(FileConfiguration config, Plugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        lastUpdate.defaultReturnValue(0L);
        reload(config);
    }

    // 설정 리로딩 시 비동기 스레드에서도 안전하도록 새로운 객체를 생성하여 원자적으로 덮어씌웁니다
    public void reload(FileConfiguration config) {
        IntSet newActiveSlots = new IntOpenHashSet();
        Int2ObjectMap<ItemStack> newMenuItems = new Int2ObjectOpenHashMap<>();
        ItemStack[] newBaseInventory = new ItemStack[45];
        Arrays.fill(newBaseInventory, AIR);

        boolean newItemsEnabled = config.getBoolean("items-enabled", true);

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
                        newActiveSlots.add(slot);
                        if (newItemsEnabled) {
                            ItemStack item = ItemBuilder.get(key);
                            newMenuItems.put(slot, item);
                        }
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        if (newItemsEnabled) {
            for (int i = 0; i <= 4; i++) {
                ItemStack item = newMenuItems.get(i);
                if (item != null) {
                    newBaseInventory[i] = item.clone();
                }
            }
        }

        this.activeMenuSlots = newActiveSlots;
        this.menuItems = newMenuItems;
        this.baseFakeInventory = newBaseInventory;
        this.itemsEnabled = newItemsEnabled;
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

    // 패킷 전송 시 불필요한 아이템 복제본 생성을 방지하여 성능을 극대화합니다
    private void sendMenuViewIfNeeded(Player player) {
        if (!itemsEnabled) return;

        GameMode mode = player.getGameMode();
        InventoryType invType = player.getOpenInventory().getType();

        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR
                || invType == InventoryType.CREATIVE
                || invType == InventoryType.PLAYER) {
            return;
        }

        ItemStack[] contents = new ItemStack[45];
        System.arraycopy(baseFakeInventory, 0, contents, 0, 5);

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

        ItemStack cursor = player.getItemOnCursor();
        player.setItemOnCursor(cursor);

        PacketContainer packet = new PacketContainer(PacketType.Play.Server.SET_SLOT);
        packet.getIntegers().write(0, -1);
        packet.getIntegers().write(1, -1);
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

        if (event.getView().getType() == InventoryType.CRAFTING) {
            for (int rawSlot : event.getRawSlots()) {
                if (activeMenuSlots.contains(rawSlot)) {
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

                for (Int2ObjectMap.Entry<ItemStack> entry : menuItems.int2ObjectEntrySet()) {
                    int slot = entry.getIntKey();
                    ItemStack expected = entry.getValue();
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