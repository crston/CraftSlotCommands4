package com.gmail.bobason01.listener;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
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
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class CraftSlotFakeItemListener implements Listener {

    private final CraftSlotCommands plugin;
    private final Logger logger;

    private final Map<String, boolean[]> pageFixedUsageArray = new ConcurrentHashMap<>();

    private volatile boolean itemsEnabled = true;
    private volatile boolean armorSlotsAsMenu = false;

    private final Map<UUID, Long> lastUpdate = new ConcurrentHashMap<>();
    private static final long MIN_UPDATE_INTERVAL_MS = 100L;
    private static final org.bukkit.inventory.ItemStack AIR = new org.bukkit.inventory.ItemStack(Material.AIR);
    private static final int WINDOW_SIZE = 46;

    public CraftSlotFakeItemListener(CraftSlotCommands plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        registerPacketSendListener();
    }

    public boolean isArmorSlotsAsMenu() {
        return armorSlotsAsMenu;
    }

    private void registerPacketSendListener() {
        PacketEvents.getAPI().getEventManager().registerListener(new PacketListenerAbstract(PacketListenerPriority.HIGH) {
            @Override
            public void onPacketSend(PacketSendEvent event) {
                Object playerObj = event.getPlayer();
                if (!(playerObj instanceof Player player)) return;
                if (!itemsEnabled || !armorSlotsAsMenu) return;

                GameMode mode = player.getGameMode();
                if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) return;

                boolean[] usages = usagesFor(player);
                if (usages == null || !hasArmorMenu(usages)) return;

                if (event.getPacketType() == PacketType.Play.Server.WINDOW_ITEMS) {
                    patchWindowItemsPacket(event, player, usages);
                    return;
                }

                if (event.getPacketType() == PacketType.Play.Server.SET_SLOT) {
                    patchSetSlotPacket(event, player, usages);
                }
            }
        });
    }

    private void patchWindowItemsPacket(PacketSendEvent event, Player player, boolean[] usages) {
        WrapperPlayServerWindowItems wrapper = new WrapperPlayServerWindowItems(event);
        if (wrapper.getWindowId() != 0) return;

        List<ItemStack> items = wrapper.getItems();
        if (items == null || items.size() <= MenuSlots.ARMOR_MAX) return;

        String state = plugin.getPlayerState(player.getUniqueId());
        List<ItemStack> patched = new ArrayList<>(items);
        boolean changed = false;
        for (int slot = MenuSlots.ARMOR_MIN; slot <= MenuSlots.ARMOR_MAX; slot++) {
            if (!isArmorMenuSlot(usages, slot)) continue;
            patched.set(slot, safeConvert(menuItem(player, state, slot)));
            changed = true;
        }
        if (!changed) return;
        wrapper.setItems(patched);
        event.markForReEncode(true);
    }

    private void patchSetSlotPacket(PacketSendEvent event, Player player, boolean[] usages) {
        WrapperPlayServerSetSlot wrapper = new WrapperPlayServerSetSlot(event);
        if (wrapper.getWindowId() != 0) return;

        int slot = wrapper.getSlot();
        if (!isArmorMenuSlot(usages, slot)) return;

        String state = plugin.getPlayerState(player.getUniqueId());
        wrapper.setItem(safeConvert(menuItem(player, state, slot)));
        event.markForReEncode(true);
    }

    public void reload(FileConfiguration config) {
        this.itemsEnabled = config.getBoolean("items-enabled", true);
        this.armorSlotsAsMenu = config.getBoolean("armor-slots-as-menu", false);
        pageFixedUsageArray.clear();

        ConfigurationSection rootPages = config.getConfigurationSection("menu-pages");
        if (rootPages == null) return;

        for (String pageKey : rootPages.getKeys(false)) {
            ConfigurationSection pageSec = rootPages.getConfigurationSection(pageKey);
            if (pageSec == null) continue;

            boolean[] fixedUsageArray = new boolean[MenuSlots.SLOT_COUNT];

            ConfigurationSection useSlotSection = pageSec.getConfigurationSection("use-slot");
            if (useSlotSection != null) {
                for (String key : useSlotSection.getKeys(false)) {
                    try {
                        int slot = Integer.parseInt(key);
                        if (!useSlotSection.getBoolean(key)) continue;
                        if (MenuSlots.isCraftSlot(slot)) {
                            fixedUsageArray[slot] = true;
                        } else if (MenuSlots.isArmorSlot(slot) && armorSlotsAsMenu) {
                            fixedUsageArray[slot] = true;
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }

            pageFixedUsageArray.put(pageKey, fixedUsageArray);
        }

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (armorSlotsAsMenu) {
                evacuateMenuArmor(player);
            }
            forceClientRefresh(player);
        }
    }

    private boolean[] usagesFor(Player player) {
        String state = plugin.getPlayerState(player.getUniqueId());
        boolean[] usages = pageFixedUsageArray.get(state);
        if (usages == null) {
            usages = pageFixedUsageArray.get("MAIN");
        }
        return usages;
    }

    private boolean isArmorMenuSlot(boolean[] usages, int rawSlot) {
        return armorSlotsAsMenu
                && usages != null
                && MenuSlots.isArmorSlot(rawSlot)
                && rawSlot < usages.length
                && usages[rawSlot];
    }

    private boolean hasArmorMenu(boolean[] usages) {
        if (!armorSlotsAsMenu || usages == null) return false;
        for (int i = MenuSlots.ARMOR_MIN; i <= MenuSlots.ARMOR_MAX; i++) {
            if (i < usages.length && usages[i]) return true;
        }
        return false;
    }

    public boolean isMenuSlot(Player player, int rawSlot) {
        if (!MenuSlots.isMenuRange(rawSlot)) return false;
        if (MenuSlots.isArmorSlot(rawSlot) && !armorSlotsAsMenu) return false;
        boolean[] usages = usagesFor(player);
        return usages != null && rawSlot < usages.length && usages[rawSlot];
    }

    private org.bukkit.inventory.ItemStack menuItem(Player player, String state, int slot) {
        return ItemBuilder.get(player, state, String.valueOf(slot));
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

    private ItemStack safeConvert(org.bukkit.inventory.ItemStack bukkitItem) {
        if (bukkitItem == null || bukkitItem.getType() == Material.AIR) {
            try {
                return SpigotConversionUtil.fromBukkitItemStack(AIR);
            } catch (Exception e) {
                return ItemStack.EMPTY;
            }
        }
        try {
            return SpigotConversionUtil.fromBukkitItemStack(bukkitItem);
        } catch (Exception e) {
            try {
                org.bukkit.inventory.ItemStack fallback = new org.bukkit.inventory.ItemStack(bukkitItem.getType(), bukkitItem.getAmount());
                return SpigotConversionUtil.fromBukkitItemStack(fallback);
            } catch (Exception ex) {
                try {
                    return SpigotConversionUtil.fromBukkitItemStack(new org.bukkit.inventory.ItemStack(Material.BARRIER));
                } catch (Exception ex2) {
                    return ItemStack.EMPTY;
                }
            }
        }
    }

    private void evacuateMenuArmor(Player player) {
        boolean[] usages = usagesFor(player);
        if (!hasArmorMenu(usages)) return;

        PlayerInventory inv = player.getInventory();
        for (int raw = MenuSlots.ARMOR_MIN; raw <= MenuSlots.ARMOR_MAX; raw++) {
            if (!usages[raw]) continue;
            org.bukkit.inventory.ItemStack worn = getArmorItem(inv, raw);
            if (worn == null || worn.getType().isAir()) continue;

            setArmorItem(inv, raw, null);
            HashMap<Integer, org.bukkit.inventory.ItemStack> leftover = inv.addItem(worn);
            if (!leftover.isEmpty()) {
                for (org.bukkit.inventory.ItemStack drop : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), drop);
                }
            }
        }
    }

    private org.bukkit.inventory.ItemStack getArmorItem(PlayerInventory inv, int rawSlot) {
        return switch (rawSlot) {
            case 5 -> inv.getHelmet();
            case 6 -> inv.getChestplate();
            case 7 -> inv.getLeggings();
            case 8 -> inv.getBoots();
            default -> null;
        };
    }

    private void setArmorItem(PlayerInventory inv, int rawSlot, org.bukkit.inventory.ItemStack item) {
        switch (rawSlot) {
            case 5 -> inv.setHelmet(item);
            case 6 -> inv.setChestplate(item);
            case 7 -> inv.setLeggings(item);
            case 8 -> inv.setBoots(item);
            default -> {}
        }
    }

    private void sendMenuViewIfNeeded(Player player) {
        if (!itemsEnabled) return;

        GameMode mode = player.getGameMode();
        InventoryView openInv = player.getOpenInventory();
        InventoryType invType = openInv.getType();

        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR || invType == InventoryType.CREATIVE) {
            return;
        }

        Inventory topInv = openInv.getTopInventory();
        if (invType != InventoryType.CRAFTING || topInv.getSize() != 5) {
            return;
        }

        InventoryHolder holder = topInv.getHolder();
        if (!(holder instanceof Player) || !holder.equals(player)) {
            return;
        }

        if (hasArmorMenu(usagesFor(player))) {
            evacuateMenuArmor(player);
        }

        String state = plugin.getPlayerState(player.getUniqueId());
        boolean[] usages = usagesFor(player);

        org.bukkit.inventory.ItemStack[] contents = new org.bukkit.inventory.ItemStack[WINDOW_SIZE];
        Arrays.fill(contents, AIR);

        if (usages != null) {
            for (int i = MenuSlots.CRAFT_MIN; i <= MenuSlots.CRAFT_MAX; i++) {
                if (usages[i]) {
                    contents[i] = menuItem(player, state, i);
                }
            }
        }

        org.bukkit.inventory.ItemStack[] inv = player.getInventory().getContents();

        for (int raw = MenuSlots.ARMOR_MIN; raw <= MenuSlots.ARMOR_MAX; raw++) {
            if (isArmorMenuSlot(usages, raw)) {
                contents[raw] = menuItem(player, state, raw);
            } else {
                contents[raw] = safeRef(getArmorItem(player.getInventory(), raw));
            }
        }

        for (int i = 9; i <= 35; i++) {
            if (i < inv.length) contents[i] = safeRef(inv[i]);
        }

        for (int i = 0; i <= 8; i++) {
            contents[36 + i] = i < inv.length ? safeRef(inv[i]) : AIR;
        }

        contents[45] = safeRef(player.getInventory().getItemInOffHand());

        List<ItemStack> peItems = new ArrayList<>(WINDOW_SIZE);
        for (org.bukkit.inventory.ItemStack bukkitItem : contents) {
            peItems.add(safeConvert(bukkitItem));
        }

        ItemStack peCursor = safeConvert(player.getItemOnCursor());
        WrapperPlayServerWindowItems packet = new WrapperPlayServerWindowItems(0, 1, peItems, peCursor);

        try {
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to send menu view via PacketEvents", e);
            return;
        }

        // Reinforce armor menu slots — server SET_SLOT(air) after evacuate can race WindowItems.
        if (hasArmorMenu(usages)) {
            for (int raw = MenuSlots.ARMOR_MIN; raw <= MenuSlots.ARMOR_MAX; raw++) {
                if (!isArmorMenuSlot(usages, raw)) continue;
                ItemStack peItem = safeConvert(menuItem(player, state, raw));
                WrapperPlayServerSetSlot setSlot = new WrapperPlayServerSetSlot(0, 1, raw, peItem);
                try {
                    PacketEvents.getAPI().getPlayerManager().sendPacket(player, setSlot);
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "Failed to send armor menu SetSlot via PacketEvents", e);
                }
            }
        }
    }

    private void syncCursorItemAlways(Player player) {
        GameMode mode = player.getGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) return;

        InventoryView openInv = player.getOpenInventory();
        if (openInv.getType() != InventoryType.CRAFTING || openInv.getTopInventory().getSize() != 5) {
            return;
        }

        org.bukkit.inventory.ItemStack cursor = player.getItemOnCursor();
        player.setItemOnCursor(cursor);

        ItemStack peCursor = safeConvert(cursor);
        WrapperPlayServerSetSlot packet = new WrapperPlayServerSetSlot(-1, 1, -1, peCursor);

        try {
            PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Failed to sync cursor via PacketEvents", e);
        }
    }

    private org.bukkit.inventory.ItemStack safeRef(org.bukkit.inventory.ItemStack item) {
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

        plugin.setPlayerState(player.getUniqueId(), "MAIN");
        scheduleUpdate(player, 2L);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        Player player = (Player) event.getWhoClicked();
        if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) return;
        if (event.getView().getType() != InventoryType.CRAFTING) return;

        boolean[] usages = usagesFor(player);
        if (usages == null) {
            UpdateTaskPool.scheduleCoalesced(player.getUniqueId(), 1L, () -> forceClientRefresh(player));
            return;
        }

        int rawSlot = event.getRawSlot();
        if (isMenuSlot(player, rawSlot)) {
            event.setCancelled(true);
        }

        if (hasArmorMenu(usages) && shouldBlockArmorEquipClick(event, usages)) {
            event.setCancelled(true);
        }

        UpdateTaskPool.scheduleCoalesced(player.getUniqueId(), 1L, () -> forceClientRefresh(player));
    }

    private boolean shouldBlockArmorEquipClick(InventoryClickEvent event, boolean[] usages) {
        ClickType click = event.getClick();
        InventoryAction action = event.getAction();

        if (click == ClickType.NUMBER_KEY) {
            int hotbar = event.getHotbarButton();
            if (hotbar >= 0) {
                org.bukkit.inventory.ItemStack hotbarItem = event.getWhoClicked().getInventory().getItem(hotbar);
                Integer armorSlot = MenuSlots.armorRawSlotForItem(hotbarItem);
                if (armorSlot != null && isArmorMenuSlot(usages, armorSlot) && MenuSlots.isArmorSlot(event.getRawSlot())) {
                    return true;
                }
                if (isArmorMenuSlot(usages, event.getRawSlot())) {
                    return true;
                }
            }
        }

        if (click.isShiftClick()) {
            org.bukkit.inventory.ItemStack current = event.getCurrentItem();
            Integer armorSlot = MenuSlots.armorRawSlotForItem(current);
            if (armorSlot != null && isArmorMenuSlot(usages, armorSlot)) {
                return true;
            }
        }

        if (action == InventoryAction.HOTBAR_SWAP || action == InventoryAction.HOTBAR_MOVE_AND_READD) {
            if (isArmorMenuSlot(usages, event.getRawSlot())) {
                return true;
            }
        }

        org.bukkit.inventory.ItemStack cursor = event.getCursor();
        if (isArmorMenuSlot(usages, event.getRawSlot()) && cursor != null && !cursor.getType().isAir()) {
            return true;
        }

        return false;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryDrag(InventoryDragEvent event) {
        Player player = (Player) event.getWhoClicked();
        if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) return;

        if (event.getView().getType() == InventoryType.CRAFTING) {
            for (int rawSlot : event.getRawSlots()) {
                if (isMenuSlot(player, rawSlot)) {
                    event.setCancelled(true);
                    break;
                }
            }
        }
        UpdateTaskPool.scheduleCoalesced(player.getUniqueId(), 1L, () -> forceClientRefresh(player));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorRightClickEquip(PlayerInteractEvent event) {
        if (!armorSlotsAsMenu) return;

        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        if (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE) return;

        boolean[] usages = usagesFor(player);
        if (!hasArmorMenu(usages)) return;

        org.bukkit.inventory.ItemStack item = event.getItem();
        Integer armorSlot = MenuSlots.armorRawSlotForItem(item);
        if (armorSlot != null && isArmorMenuSlot(usages, armorSlot)) {
            event.setCancelled(true);
        }
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

                for (int slot = MenuSlots.CRAFT_MIN; slot <= MenuSlots.CRAFT_MAX; slot++) {
                    if (usages == null || !usages[slot]) continue;
                    org.bukkit.inventory.ItemStack expected = ItemBuilder.get(player, state, String.valueOf(slot));
                    org.bukkit.inventory.ItemStack current = player.getInventory().getItem(slot);
                    if (current != null && current.isSimilar(expected)) {
                        player.getInventory().setItem(slot, AIR);
                    }
                }

                org.bukkit.inventory.ItemStack[] fullContents = player.getInventory().getContents();
                org.bukkit.inventory.ItemStack[] contents = new org.bukkit.inventory.ItemStack[WINDOW_SIZE];
                Arrays.fill(contents, AIR);

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
                contents[45] = safeRef(player.getInventory().getItemInOffHand());

                List<ItemStack> peItems = new ArrayList<>(WINDOW_SIZE);
                for (org.bukkit.inventory.ItemStack bukkitItem : contents) {
                    peItems.add(safeConvert(bukkitItem));
                }

                ItemStack peCursor = safeConvert(player.getItemOnCursor());
                WrapperPlayServerWindowItems packet = new WrapperPlayServerWindowItems(0, 1, peItems, peCursor);

                try {
                    PacketEvents.getAPI().getPlayerManager().sendPacket(player, packet);
                } catch (Exception e) {
                    logger.log(Level.SEVERE, "Failed to clear fake inventory via PacketEvents", e);
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
