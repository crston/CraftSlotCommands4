package com.gmail.bobason01;

import com.destroystokyo.paper.event.player.PlayerRecipeBookClickEvent;
import com.gmail.bobason01.listener.CraftSlotFakeItemListener;
import com.gmail.bobason01.util.BedrockDetector;
import com.gmail.bobason01.util.UpdateTaskPool;
import com.gmail.bobason01.util.SchedulerUtil;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.plugin.java.JavaPlugin;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.gmail.bobason01.util.InventoryUtil.isSelf2x2Crafting;

public final class CraftSlotCommands extends JavaPlugin implements Listener {

    private static final int MIN_MENU_SLOT = 0;
    private static final int MAX_MENU_SLOT = 4;
    private static final long IGNORE_CLICK_MS = 300L;

    private static CraftSlotCommands instance;
    private CraftSlotFakeItemListener fakeItemListener;

    private final Map<Integer, String> slotCommandCache = new ConcurrentHashMap<>(5);
    private final Map<Integer, Boolean> slotUsageMap = new ConcurrentHashMap<>(5);
    private final Map<UUID, Long> bedrockCloseTimestamps = new ConcurrentHashMap<>();
    private final Map<Integer, Map<String, String>> keybindCommandMap = new ConcurrentHashMap<>(5);

    private String commandType = "crafting-slot";

    public static CraftSlotCommands getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;

        if (isPluginMissing("ProtocolLib")) {
            getLogger().severe("Required dependencies missing. Disabling plugin.");
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

            slotCommandCache.clear();
            slotUsageMap.clear();
            keybindCommandMap.clear();

            commandType = getConfig().getString("cmd-type", "crafting-slot")
                    .toLowerCase(Locale.ROOT);

            ConfigurationSection useSlotSec = getConfig().getConfigurationSection("use-slot");
            if (useSlotSec != null) {
                for (String key : useSlotSec.getKeys(false)) {
                    try {
                        slotUsageMap.put(Integer.parseInt(key), useSlotSec.getBoolean(key));
                    } catch (NumberFormatException ignored) {}
                }
            }

            if ("crafting-slot".equals(commandType)) {
                loadCraftingSlotCommands();
            } else if ("keybind-commands".equals(commandType)) {
                loadKeybindCommands();
            }

            if (fakeItemListener != null) {
                SchedulerUtil.run(this, () -> fakeItemListener.reload(getConfig()));
            }
        });
    }

    private void loadCraftingSlotCommands() {
        ConfigurationSection section = getConfig().getConfigurationSection("crafting-slot");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            try {
                int slot = Integer.parseInt(key);
                String cmd = section.getString(key, "").trim();
                if (!cmd.isEmpty()) slotCommandCache.put(slot, cmd);
            } catch (NumberFormatException ignored) {}
        }
    }

    private void loadKeybindCommands() {
        ConfigurationSection section = getConfig().getConfigurationSection("keybind-commands");
        if (section == null) return;

        for (String slotKey : section.getKeys(false)) {
            try {
                int slot = Integer.parseInt(slotKey);
                ConfigurationSection slotSection = section.getConfigurationSection(slotKey);
                if (slotSection == null) continue;

                Map<String, String> binds = new ConcurrentHashMap<>();
                for (String key : slotSection.getKeys(false)) {
                    // 값을 문자열로 가져옵니다.
                    String cmd = slotSection.getString(key);

                    // 핵심 로직: 문자열이 null이 아니고 비어있지 않은 경우에만 맵에 등록합니다.
                    // config에서 ""로 설정된 항목은 여기서 걸러져서 아예 등록되지 않으므로 작동하지 않게 됩니다.
                    if (cmd != null && !cmd.isBlank()) {
                        binds.put(key.toUpperCase(Locale.ROOT), cmd);
                    }
                }
                keybindCommandMap.put(slot, binds);
            } catch (NumberFormatException ignored) {}
        }
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

        boolean hit = event.getRawSlots().stream()
                .anyMatch(s -> s >= MIN_MENU_SLOT && s <= MAX_MENU_SLOT && slotUsageMap.getOrDefault(s, false));

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
            else if (click == ClickType.DROP || click == ClickType.CONTROL_DROP) mappingKey = "Q"; // Q키 매핑
            else if (click == ClickType.SWAP_OFFHAND) mappingKey = "F"; // F키 매핑
            else if (click == ClickType.NUMBER_KEY) {
                int num = event.getHotbarButton() + 1;
                mappingKey = String.valueOf(num);
            }

            if (mappingKey != null) {
                // 1차적으로 매핑된 키(예: "Q")를 찾습니다.
                String cmd = slotCommands.get(mappingKey);

                // 만약 Q키 입력인데 "Q" 설정이 없고 "DROP" 설정이 있다면 대체해서 찾습니다.
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

    public static class CSCCommand implements CommandExecutor, TabCompleter {
        @Override
        public boolean onCommand(@Nonnull CommandSender sender, @Nonnull Command command,
                                 @Nonnull String label, @Nonnull String[] args) {
            if (!sender.hasPermission("csc.admin")) {
                sendPrefixed(sender, Component.text("You do not have permission.", NamedTextColor.RED));
                return true;
            }
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                CraftSlotCommands.getInstance().reloadPlugin();
                sendPrefixed(sender, Component.text("Reloaded successfully.", NamedTextColor.GREEN));
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
            sender.sendMessage(Component.text("[CSC4] ", NamedTextColor.GRAY).append(msg));
        }
    }
}