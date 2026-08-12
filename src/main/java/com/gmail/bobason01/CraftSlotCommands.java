package com.gmail.bobason01;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.packettype.PacketTypeCommon;
import com.gmail.bobason01.api.CraftSlotAPI;
import com.gmail.bobason01.api.CraftSlotAPIProvider;
import com.gmail.bobason01.listener.CraftSlotFakeItemListener;
import com.gmail.bobason01.util.BedrockDetector;
import com.gmail.bobason01.util.InventoryUtil;
import com.gmail.bobason01.util.MenuSlots;
import com.gmail.bobason01.util.SchedulerUtil;
import com.gmail.bobason01.util.UpdateTaskPool;
import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CraftSlotCommands extends JavaPlugin implements Listener, CraftSlotAPI {

    private static final long IGNORE_CLICK_MS = 300L;

    private static CraftSlotCommands instance;
    private CraftSlotFakeItemListener fakeItemListener;

    private final Map<UUID, String> playerMenuState = new ConcurrentHashMap<>();
    private final Map<UUID, Long> bedrockCloseTimestamps = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, String>> pageSlotCommandCache = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, Boolean>> pageSlotUsageMap = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, Map<String, String>>> pageKeybindCommandMap = new ConcurrentHashMap<>();

    private volatile String commandType = "crafting-slot";

    public static CraftSlotCommands getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        CraftSlotAPIProvider.register(this);

        if (Bukkit.getPluginManager().getPlugin("packetevents") == null) {
            getLogger().severe("PacketEvents plugin is missing");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        UpdateTaskPool.init(this);
        registerCommand();
        registerEvents();
        registerPacketEvents();
        reloadPlugin();
    }

    @Override
    public void onDisable() {
        if (fakeItemListener != null) fakeItemListener.shutdown();
        UpdateTaskPool.shutdown();
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
        fakeItemListener = new CraftSlotFakeItemListener(this);
        Bukkit.getPluginManager().registerEvents(fakeItemListener, this);
    }

    private void registerPacketEvents() {
        PacketEvents.getAPI().getEventManager().registerListener(new PacketListenerAbstract(PacketListenerPriority.NORMAL) {
            @Override
            public void onPacketReceive(PacketReceiveEvent event) {
                PacketTypeCommon type = event.getPacketType();
                if (type != PacketType.Play.Client.RECIPE_BOOK_DATA
                        && type != PacketType.Play.Client.CRAFT_RECIPE_REQUEST) {
                    return;
                }
                Object playerObj = event.getPlayer();
                if (!(playerObj instanceof Player player)) return;
                if (!InventoryUtil.isSelf2x2Crafting(player.getOpenInventory())) return;
                event.setCancelled(true);
                SchedulerUtil.run(instance, () -> postUpdatePlayerView(player));
            }
        });
    }

    public CraftSlotFakeItemListener getFakeItemListener() {
        return fakeItemListener;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        playerMenuState.put(event.getPlayer().getUniqueId(), "MAIN");
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        playerMenuState.remove(uuid);
        bedrockCloseTimestamps.remove(uuid);
    }

    public String getPlayerState(UUID uuid) {
        return playerMenuState.getOrDefault(uuid, "MAIN");
    }

    public void setPlayerState(UUID uuid, String state) {
        playerMenuState.put(uuid, state);
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline() && fakeItemListener != null) {
            fakeItemListener.forceClientRefresh(player);
        }
    }

    public String getMessage(String key) {
        String msg = getConfig().getString("messages." + key);
        if (msg == null) {
            msg = switch (key) {
                case "prefix" -> "&7CSC5 ";
                case "no-permission" -> "&cYou do not have permission";
                case "reload-success" -> "&aConfiguration files reloaded successfully";
                default -> key;
            };
        }
        return ChatColor.translateAlternateColorCodes('&', msg);
    }

    public synchronized void reloadPlugin() {
        SchedulerUtil.runAsync(this, () -> {
            reloadConfig();

            com.gmail.bobason01.util.ItemBuilder.prepareReload();
            ConfigurationSection rootPages = getConfig().getConfigurationSection("menu-pages");
            if (rootPages != null) {
                for (String pageKey : rootPages.getKeys(false)) {
                    ConfigurationSection pageSec = rootPages.getConfigurationSection(pageKey);
                    if (pageSec == null) continue;
                    ConfigurationSection itemSection = pageSec.getConfigurationSection("slot-item");
                    if (itemSection != null) {
                        com.gmail.bobason01.util.ItemBuilder.loadFromConfig(pageKey, itemSection);
                    }
                }
            }

            Map<String, Map<Integer, String>> newCommands = new HashMap<>();
            Map<String, Map<Integer, Boolean>> newUsages = new HashMap<>();
            Map<String, Map<Integer, Map<String, String>>> newKeybinds = new HashMap<>();
            String newCommandType = getConfig().getString("cmd-type", "crafting-slot").toLowerCase(Locale.ROOT);

            if (rootPages != null) {
                for (String pageKey : rootPages.getKeys(false)) {
                    ConfigurationSection pageSec = rootPages.getConfigurationSection(pageKey);
                    if (pageSec == null) continue;

                    Map<Integer, Boolean> slotUsage = new HashMap<>();
                    ConfigurationSection useSlotSec = pageSec.getConfigurationSection("use-slot");
                    if (useSlotSec != null) {
                        for (String key : useSlotSec.getKeys(false)) {
                            try {
                                int slot = Integer.parseInt(key);
                                if (!useSlotSec.getBoolean(key)) continue;
                                if (!MenuSlots.isCraftSlot(slot)) continue;
                                slotUsage.put(slot, true);
                            } catch (NumberFormatException ignored) {
                            }
                        }
                    }
                    newUsages.put(pageKey, slotUsage);

                    Map<Integer, String> slotCommands = new HashMap<>();
                    Map<Integer, Map<String, String>> keybinds = new HashMap<>();

                    if ("crafting-slot".equals(newCommandType)) {
                        ConfigurationSection sec = pageSec.getConfigurationSection("crafting-slot");
                        if (sec != null) {
                            for (String key : sec.getKeys(false)) {
                                try {
                                    int slot = Integer.parseInt(key);
                                    if (!MenuSlots.isCraftSlot(slot)) continue;
                                    String cmd = sec.getString(key, "").trim();
                                    if (!cmd.isEmpty()) slotCommands.put(slot, cmd);
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                    } else if ("keybind-commands".equals(newCommandType)) {
                        ConfigurationSection sec = pageSec.getConfigurationSection("keybind-commands");
                        if (sec != null) {
                            for (String slotKey : sec.getKeys(false)) {
                                try {
                                    int slot = Integer.parseInt(slotKey);
                                    if (!MenuSlots.isCraftSlot(slot)) continue;
                                    ConfigurationSection slotSection = sec.getConfigurationSection(slotKey);
                                    if (slotSection == null) continue;
                                    Map<String, String> binds = new HashMap<>();
                                    for (String key : slotSection.getKeys(false)) {
                                        String cmd = slotSection.getString(key);
                                        if (cmd != null && !cmd.isBlank()) {
                                            binds.put(key.toUpperCase(Locale.ROOT), cmd);
                                        }
                                    }
                                    keybinds.put(slot, binds);
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                    }
                    newCommands.put(pageKey, slotCommands);
                    newKeybinds.put(pageKey, keybinds);
                }
            }

            this.commandType = newCommandType;
            this.pageSlotUsageMap.clear();
            this.pageSlotUsageMap.putAll(newUsages);
            this.pageSlotCommandCache.clear();
            this.pageSlotCommandCache.putAll(newCommands);
            this.pageKeybindCommandMap.clear();
            this.pageKeybindCommandMap.putAll(newKeybinds);

            if (fakeItemListener != null) {
                SchedulerUtil.run(this, () -> fakeItemListener.reload(getConfig()));
            }
        });
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        Player player = (Player) event.getPlayer();
        if (BedrockDetector.isBedrock(player)) {
            bedrockCloseTimestamps.put(player.getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler(priority = org.bukkit.event.EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!isMenuClick(event)) return;

        Player player = (Player) event.getWhoClicked();
        event.setCancelled(true);

        if (BedrockDetector.isBedrock(player)) {
            long closed = bedrockCloseTimestamps.getOrDefault(player.getUniqueId(), 0L);
            if (System.currentTimeMillis() - closed < IGNORE_CLICK_MS) {
                postUpdatePlayerView(player);
                return;
            }
        }

        String command = resolveCommand(event, event.getRawSlot());
        if (command != null && !command.isBlank()) {
            SchedulerUtil.runForPlayer(this, player, () -> dispatchCommand(player, command));
        }
        postUpdatePlayerView(player);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getType() != InventoryType.CRAFTING) return;

        Player player = (Player) event.getWhoClicked();
        String state = getPlayerState(player.getUniqueId());
        Map<Integer, Boolean> usages = pageSlotUsageMap.getOrDefault(state, Collections.emptyMap());

        boolean hit = false;
        for (int s : event.getRawSlots()) {
            if (MenuSlots.isCraftSlot(s) && usages.getOrDefault(s, false)) {
                hit = true;
                break;
            }
        }
        if (!hit) return;

        event.setCancelled(true);
        postUpdatePlayerView(player);
    }

    private String resolveCommand(InventoryClickEvent event, int slot) {
        Player player = (Player) event.getWhoClicked();
        String state = getPlayerState(player.getUniqueId());

        if ("crafting-slot".equals(commandType)) {
            return pageSlotCommandCache.getOrDefault(state, Collections.emptyMap()).get(slot);
        }

        if ("keybind-commands".equals(commandType)) {
            Map<String, String> slotCommands = pageKeybindCommandMap
                    .getOrDefault(state, Collections.emptyMap())
                    .get(slot);
            if (slotCommands == null) return null;

            ClickType click = event.getClick();
            String mappingKey = null;
            if (click == ClickType.LEFT) mappingKey = "LEFT";
            else if (click == ClickType.RIGHT) mappingKey = "RIGHT";
            else if (click == ClickType.DROP || click == ClickType.CONTROL_DROP) mappingKey = "Q";
            else if (click == ClickType.SWAP_OFFHAND) mappingKey = "F";
            else if (click == ClickType.NUMBER_KEY) mappingKey = String.valueOf(event.getHotbarButton() + 1);

            if (mappingKey != null) {
                String cmd = slotCommands.get(mappingKey);
                if (cmd == null && "Q".equals(mappingKey)) cmd = slotCommands.get("DROP");
                return cmd;
            }
        }
        return null;
    }

    private void dispatchCommand(Player player, String rawCommand) {
        String resolved = PlaceholderAPI.setPlaceholders(player, rawCommand);
        if (resolved.startsWith("*")) {
            String sub = resolved.substring(1).trim();
            if (sub.toLowerCase(Locale.ROOT).startsWith("csc_state")) {
                String[] split = sub.split("\\s+");
                if (split.length > 1) {
                    setPlayerState(player.getUniqueId(), split[1].toUpperCase(Locale.ROOT));
                }
            } else {
                SchedulerUtil.run(this, () -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), sub));
            }
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
        return null;
    }

    public ItemStack getFakeItemForPlayer(Player player, int slot) {
        if (!MenuSlots.isCraftSlot(slot)) return null;
        return com.gmail.bobason01.util.ItemBuilder.get(player, getPlayerState(player.getUniqueId()), String.valueOf(slot));
    }

    @Override
    public boolean isFakeSlot(int slot) {
        return false;
    }

    public boolean isFakeSlotForPlayer(Player player, int slot) {
        if (!MenuSlots.isCraftSlot(slot)) return false;
        Map<Integer, Boolean> usages = pageSlotUsageMap.getOrDefault(
                getPlayerState(player.getUniqueId()), Collections.emptyMap());
        return usages.getOrDefault(slot, false);
    }

    @Override
    public boolean isMenuClick(InventoryClickEvent event) {
        if (event == null || event.getView().getType() != InventoryType.CRAFTING) return false;
        int rawSlot = event.getRawSlot();
        if (!MenuSlots.isCraftSlot(rawSlot)) return false;
        return isFakeSlotForPlayer((Player) event.getWhoClicked(), rawSlot);
    }

    @Override
    public void forceUpdatePlayerView(Player player) {
        postUpdatePlayerView(player);
    }

    @Override
    public void applyModelIntegration(ItemMeta meta, int customModelData, String itemModelKey) {
        if (meta == null) return;
        if (customModelData != 0) meta.setCustomModelData(customModelData);
        if (itemModelKey != null && !itemModelKey.isBlank()) {
            try {
                if (itemModelKey.indexOf(':') != -1) {
                    meta.setItemModel(NamespacedKey.fromString(itemModelKey));
                } else {
                    meta.setItemModel(NamespacedKey.minecraft(itemModelKey));
                }
            } catch (Exception ignored) {
            }
        }
    }

    public static class CSCCommand implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(@Nonnull CommandSender sender, @Nonnull Command command,
                                 @Nonnull String label, @Nonnull String[] args) {
            CraftSlotCommands plugin = CraftSlotCommands.getInstance();
            if (args.length == 0) {
                sender.sendMessage(plugin.getMessage("prefix") + "CraftSlotCommands");
                sender.sendMessage(ChatColor.GRAY + "/" + label + " reload");
                return true;
            }
            if (args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("csc.admin")) {
                    sender.sendMessage(plugin.getMessage("no-permission"));
                    return true;
                }
                plugin.reloadPlugin();
                sender.sendMessage(plugin.getMessage("reload-success"));
                return true;
            }
            sender.sendMessage(plugin.getMessage("prefix") + "CraftSlotCommands");
            return true;
        }

        @Override
        public List<String> onTabComplete(@Nonnull CommandSender sender, @Nonnull Command command,
                                          @Nonnull String alias, @Nonnull String[] args) {
            if (args.length == 1) {
                String t = args[0].toLowerCase(Locale.ROOT);
                List<String> out = new ArrayList<>(1);
                if ("reload".startsWith(t)) out.add("reload");
                return out;
            }
            return Collections.emptyList();
        }
    }
}
