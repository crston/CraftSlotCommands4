package com.gmail.bobason01;

import com.destroystokyo.paper.event.player.PlayerRecipeBookClickEvent;
import com.gmail.bobason01.api.CraftSlotAPI;
import com.gmail.bobason01.api.CraftSlotAPIProvider;
import com.gmail.bobason01.listener.CraftSlotFakeItemListener;
import com.gmail.bobason01.util.BedrockDetector;
import com.gmail.bobason01.util.UpdateTaskPool;
import com.gmail.bobason01.util.SchedulerUtil;
import it.unimi.dsi.fastutil.ints.Int2BooleanMap;
import it.unimi.dsi.fastutil.ints.Int2BooleanMaps;
import it.unimi.dsi.fastutil.ints.Int2BooleanOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.gmail.bobason01.util.InventoryUtil.isSelf2x2Crafting;

// 플러그인 메인 클래스이자 제공된 API의 실제 구현체입니다
public final class CraftSlotCommands extends JavaPlugin implements Listener, CraftSlotAPI {

    private static final int MIN_MENU_SLOT = 0;
    private static final int MAX_MENU_SLOT = 4;
    private static final long IGNORE_CLICK_MS = 300L;

    private static CraftSlotCommands instance;
    private CraftSlotFakeItemListener fakeItemListener;

    private volatile Int2ObjectMap<String> slotCommandCache = Int2ObjectMaps.emptyMap();
    private volatile Int2BooleanMap slotUsageMap = Int2BooleanMaps.EMPTY_MAP;
    private volatile Int2ObjectMap<Map<String, String>> keybindCommandMap = Int2ObjectMaps.emptyMap();

    private final Map<UUID, Long> bedrockCloseTimestamps = new ConcurrentHashMap<>();

    private volatile String commandType = "crafting-slot";

    public static CraftSlotCommands getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        CraftSlotAPIProvider.register(this);

        if (isPluginMissing("ProtocolLib")) {
            getLogger().severe("Required dependencies missing");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        UpdateTaskPool.init(this);
        registerCommand();
        registerEvents();
        reloadPlugin();
    }

    @Override
    public void onDisable() {
        UpdateTaskPool.shutdown();
    }

    private boolean isPluginMissing(String plugin) {
        return Bukkit.getPluginManager().getPlugin(plugin) == null;
    }

    private void registerCommand() {
        PluginCommand cmd = getCommand("craftslotcommands");
        if (cmd != null) {
            CSCCommand executor = new CSCCommand();
            cmd.setExecutor(executor);
            cmd.setTabCompleter(executor);
        }
    }

    private void registerEvents() {
        Bukkit.getPluginManager().registerEvents(this, this);
        fakeItemListener = new CraftSlotFakeItemListener(getConfig(), this);
        Bukkit.getPluginManager().registerEvents(fakeItemListener, this);
    }

    public synchronized void reloadPlugin() {
        SchedulerUtil.runAsync(this, () -> {
            reloadConfig();

            Int2ObjectOpenHashMap<String> newSlotCommandCache = new Int2ObjectOpenHashMap<>();
            Int2BooleanOpenHashMap newSlotUsageMap = new Int2BooleanOpenHashMap();
            Int2ObjectOpenHashMap<Map<String, String>> newKeybindCommandMap = new Int2ObjectOpenHashMap<>();

            String newCommandType = getConfig().getString("cmd-type", "crafting-slot").toLowerCase(Locale.ROOT);

            ConfigurationSection useSlotSec = getConfig().getConfigurationSection("use-slot");
            if (useSlotSec != null) {
                for (String key : useSlotSec.getKeys(false)) {
                    try {
                        newSlotUsageMap.put(Integer.parseInt(key), useSlotSec.getBoolean(key));
                    } catch (NumberFormatException ignored) {}
                }
            }

            if ("crafting-slot".equals(newCommandType)) {
                ConfigurationSection sec = getConfig().getConfigurationSection("crafting-slot");
                if (sec != null) {
                    for (String key : sec.getKeys(false)) {
                        try {
                            int slot = Integer.parseInt(key);
                            String cmd = sec.getString(key, "").trim();
                            if (!cmd.isEmpty()) newSlotCommandCache.put(slot, cmd);
                        } catch (NumberFormatException ignored) {}
                    }
                }
            } else if ("keybind-commands".equals(newCommandType)) {
                ConfigurationSection sec = getConfig().getConfigurationSection("keybind-commands");
                if (sec != null) {
                    for (String slotKey : sec.getKeys(false)) {
                        try {
                            int slot = Integer.parseInt(slotKey);
                            ConfigurationSection slotSection = sec.getConfigurationSection(slotKey);
                            if (slotSection == null) continue;

                            Map<String, String> binds = new HashMap<>();
                            for (String key : slotSection.getKeys(false)) {
                                String cmd = slotSection.getString(key);
                                if (cmd != null && !cmd.isBlank()) {
                                    binds.put(key.toUpperCase(Locale.ROOT), cmd);
                                }
                            }
                            newKeybindCommandMap.put(slot, binds);
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }

            this.commandType = newCommandType;
            this.slotUsageMap = newSlotUsageMap;
            this.slotCommandCache = newSlotCommandCache;
            this.keybindCommandMap = newKeybindCommandMap;

            if (fakeItemListener != null) {
                SchedulerUtil.run(this, () -> fakeItemListener.reload(getConfig()));
            }
        });
    }

    private boolean isBedrockPlayer(Player player) {
        return BedrockDetector.isBedrock(player);
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Player player = (Player) event.getPlayer();
        if (isBedrockPlayer(player)) {
            bedrockCloseTimestamps.put(player.getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().getType() != InventoryType.CRAFTING) return;

        int rawSlot = event.getRawSlot();
        if (rawSlot < MIN_MENU_SLOT || rawSlot > MAX_MENU_SLOT) return;

        if (!slotUsageMap.getOrDefault(rawSlot, false)) return;

        Player player = (Player) event.getWhoClicked();

        if (isBedrockPlayer(player)) {
            long closed = bedrockCloseTimestamps.getOrDefault(player.getUniqueId(), 0L);
            if (System.currentTimeMillis() - closed < IGNORE_CLICK_MS) return;
        }

        String command = resolveCommand(event, rawSlot);
        if (command == null || command.isBlank()) return;

        event.setCancelled(true);
        SchedulerUtil.runForPlayer(this, player, () -> dispatchCommand(player, command));

        postUpdatePlayerView(player);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getType() != InventoryType.CRAFTING) return;

        boolean hit = false;
        for (int s : event.getRawSlots()) {
            if (s >= MIN_MENU_SLOT && s <= MAX_MENU_SLOT && slotUsageMap.getOrDefault(s, false)) {
                hit = true;
                break;
            }
        }

        if (!hit) return;

        event.setCancelled(true);
        postUpdatePlayerView((Player) event.getWhoClicked());
    }

    @EventHandler
    public void onRecipeClick(PlayerRecipeBookClickEvent event) {
        Player player = event.getPlayer();
        if (!isSelf2x2Crafting(player.getOpenInventory())) return;

        event.setCancelled(true);
        SchedulerUtil.runForPlayer(this, player, () -> player.openWorkbench(null, true));
        postUpdatePlayerView(player);
    }

    private String resolveCommand(InventoryClickEvent event, int slot) {
        if ("crafting-slot".equals(commandType)) {
            return slotCommandCache.get(slot);
        }

        if ("keybind-commands".equals(commandType)) {
            Map<String, String> slotCommands = keybindCommandMap.get(slot);
            if (slotCommands == null) return null;

            ClickType click = event.getClick();
            String mappingKey = null;

            if (click == ClickType.LEFT) mappingKey = "LEFT";
            else if (click == ClickType.RIGHT) mappingKey = "RIGHT";
            else if (click == ClickType.DROP || click == ClickType.CONTROL_DROP) mappingKey = "Q";
            else if (click == ClickType.SWAP_OFFHAND) mappingKey = "F";
            else if (click == ClickType.NUMBER_KEY) {
                int num = event.getHotbarButton() + 1;
                mappingKey = String.valueOf(num);
            }

            if (mappingKey != null) {
                String cmd = slotCommands.get(mappingKey);
                if (cmd == null && "Q".equals(mappingKey)) {
                    cmd = slotCommands.get("DROP");
                }
                return cmd;
            }
        }
        return null;
    }

    private void dispatchCommand(Player player, String rawCommand) {
        String resolved = PlaceholderAPI.setPlaceholders(player, rawCommand);
        if (resolved.startsWith("*")) {
            String consoleCmd = resolved.substring(1);
            SchedulerUtil.run(this, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), consoleCmd));
        } else {
            Bukkit.dispatchCommand(player, resolved);
        }
    }

    private void postUpdatePlayerView(Player player) {
        UpdateTaskPool.scheduleCoalesced(player.getUniqueId(), 1L, () -> {
            if (player.isOnline() && fakeItemListener != null) {
                fakeItemListener.forceClientRefresh(player);
            }
        });
    }

    @Override
    public ItemStack getFakeItem(int slot) {
        if (slot < MIN_MENU_SLOT || slot > MAX_MENU_SLOT) return null;
        return com.gmail.bobason01.util.ItemBuilder.get(String.valueOf(slot));
    }

    @Override
    public boolean isFakeSlot(int slot) {
        return slotUsageMap.getOrDefault(slot, false);
    }

    @Override
    public void forceUpdatePlayerView(Player player) {
        postUpdatePlayerView(player);
    }

    @Override
    public void applyModelIntegration(ItemMeta meta, int customModelData, String itemModelKey) {
        if (meta == null) return;

        if (customModelData != 0) {
            meta.setCustomModelData(customModelData);
        }

        if (itemModelKey != null && !itemModelKey.isBlank()) {
            try {
                if (itemModelKey.indexOf(':') != -1) {
                    meta.setItemModel(NamespacedKey.fromString(itemModelKey));
                } else {
                    meta.setItemModel(NamespacedKey.minecraft(itemModelKey));
                }
            } catch (Exception ignored) {}
        }
    }

    public static class CSCCommand implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(@Nonnull CommandSender sender, @Nonnull Command command,
                                 @Nonnull String label, @Nonnull String[] args) {
            if (!sender.hasPermission("csc.admin")) {
                sendPrefixed(sender, Component.text("You do not have permission", NamedTextColor.RED));
                return true;
            }
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                CraftSlotCommands.getInstance().reloadPlugin();
                sendPrefixed(sender, Component.text("Reloaded successfully", NamedTextColor.GREEN));
                return true;
            }
            sendPrefixed(sender, Component.text("CraftSlotCommands", NamedTextColor.AQUA));
            return true;
        }

        @Override
        public List<String> onTabComplete(@Nonnull CommandSender sender, @Nonnull Command command,
                                          @Nonnull String alias, String[] args) {
            return args.length == 1 ? List.of("reload") : Collections.emptyList();
        }

        private void sendPrefixed(CommandSender sender, Component msg) {
            sender.sendMessage(Component.text("CSC4 ", NamedTextColor.GRAY).append(msg));
        }
    }
}