package com.gmail.bobason01;

import com.destroystokyo.paper.event.player.PlayerRecipeBookClickEvent;
import com.gmail.bobason01.listener.CraftSlotFakeItemListener;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.plugin.java.JavaPlugin;

import javax.annotation.Nonnull;
import java.util.*;

import static com.gmail.bobason01.util.InventoryUtil.isSelf2x2Crafting;

public class CraftSlotCommands extends JavaPlugin implements Listener {

    private static final int MIN_MENU_SLOT = 0;
    private static final int MAX_MENU_SLOT = 4;
    private static final long IGNORE_CLICK_MS = 300L;

    private static CraftSlotCommands instance;
    private CraftSlotFakeItemListener fakeItemListener;

    private final Map<Integer, String> slotCommandCache = new HashMap<>(5);
    private final Map<Integer, Boolean> slotUsageMap = new HashMap<>(5);
    private final Map<UUID, Long> bedrockCloseTimestamps = new HashMap<>();
    private final Map<Integer, Map<String, String>> keybindCommandMap = new HashMap<>(5);

    private String commandType = "crafting-slot";

    public static CraftSlotCommands getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;

        if (isPluginMissing("ProtocolLib") || isPluginMissing("PlaceholderAPI")) {
            getLogger().severe("Required dependencies missing! Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        registerCommand();
        registerEvents();
        reloadPlugin();
    }

    private boolean isPluginMissing(String plugin) {
        return Bukkit.getPluginManager().getPlugin(plugin) == null;
    }

    private void registerCommand() {
        PluginCommand cmd = getCommand("craftslotcommands");
        if (cmd != null) {
            CSCCommand command = new CSCCommand();
            cmd.setExecutor(command);
            cmd.setTabCompleter(command);
        }
    }

    private void registerEvents() {
        Bukkit.getPluginManager().registerEvents(this, this);
        fakeItemListener = new CraftSlotFakeItemListener(getConfig(), this);
        Bukkit.getPluginManager().registerEvents(fakeItemListener, this);
    }

    public synchronized void reloadPlugin() {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            reloadConfig();
            slotCommandCache.clear();
            slotUsageMap.clear();
            keybindCommandMap.clear();

            commandType = getConfig().getString("cmd-type", "crafting-slot").toLowerCase();
            ConfigurationSection useSlotSec = getConfig().getConfigurationSection("use-slot");

            if (useSlotSec != null) {
                for (String key : useSlotSec.getKeys(false)) {
                    try {
                        slotUsageMap.put(Integer.parseInt(key), useSlotSec.getBoolean(key));
                    } catch (NumberFormatException ignored) {}
                }
            }

            if (commandType.equals("crafting-slot")) {
                loadCraftingSlotCommands();
            } else if (commandType.equals("keybind-commands")) {
                loadKeybindCommands();
            }

            if (fakeItemListener != null) {
                Bukkit.getScheduler().runTask(this, () -> fakeItemListener.reload(getConfig()));
            }
        });
    }

    private void loadCraftingSlotCommands() {
        ConfigurationSection section = getConfig().getConfigurationSection("crafting-slot");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            try {
                int slot = Integer.parseInt(key);
                String cmd = section.getString(key, "");
                if (!cmd.isBlank()) slotCommandCache.put(slot, cmd);
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

                Map<String, String> binds = new HashMap<>();
                for (String key : slotSection.getKeys(false)) {
                    binds.put(key.toUpperCase(), slotSection.getString(key));
                }
                keybindCommandMap.put(slot, binds);
            } catch (NumberFormatException ignored) {}
        }
    }

    private boolean isBedrockPlayer(Player player) {
        return player.getUniqueId().toString().startsWith("00000000");
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
        if (event.getRawSlot() > MAX_MENU_SLOT) return;

        if (event.getView().getType() != InventoryType.CRAFTING) return;
        Player player = (Player) event.getWhoClicked();

        if (!slotUsageMap.getOrDefault(event.getRawSlot(), false)) return;
        if (isBedrockPlayer(player)) {
            long closed = bedrockCloseTimestamps.getOrDefault(player.getUniqueId(), 0L);
            if (System.currentTimeMillis() - closed < IGNORE_CLICK_MS) return;
        }

        String command = resolveCommand(event, event.getRawSlot());
        if (command == null || command.isBlank()) return;

        event.setCancelled(true);
        Bukkit.getScheduler().runTask(this, () -> dispatchCommand(player, command));
    }

    private String resolveCommand(InventoryClickEvent event, int slot) {
        if (commandType.equals("crafting-slot")) return slotCommandCache.get(slot);

        if (commandType.equals("keybind-commands")) {
            String clickType = event.getClick().name();
            Map<String, String> slotCommands = keybindCommandMap.get(slot);
            if (slotCommands == null) return null;

            String command = slotCommands.get(clickType);
            if (command == null && event.getClick().isKeyboardClick()) {
                int hotbar = event.getHotbarButton();
                command = slotCommands.get(hotbar >= 0 && hotbar <= 8 ? String.valueOf(hotbar + 1) : "Q");
            }
            return command;
        }
        return null;
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        if (!commandType.equals("keybind-commands")) return;
        Player player = event.getPlayer();
        if (isValidCraftingContext(player)) return;

        event.setCancelled(true);
        triggerAllCursorKeybinds(player, "DROP");
    }

    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (!commandType.equals("keybind-commands")) return;
        Player player = event.getPlayer();
        if (isValidCraftingContext(player)) return;

        event.setCancelled(true);
        triggerAllCursorKeybinds(player, "F");
    }

    private boolean isValidCraftingContext(Player player) {
        return !(player.getOpenInventory().getTopInventory() instanceof CraftingInventory)
                || !isSelf2x2Crafting(player.getOpenInventory())
                || player.getItemOnCursor() == null
                || player.getItemOnCursor().getType().isAir();
    }

    private void triggerAllCursorKeybinds(Player player, String key) {
        for (int slot = MIN_MENU_SLOT; slot <= MAX_MENU_SLOT; slot++) {
            if (!slotUsageMap.getOrDefault(slot, false)) continue;
            Map<String, String> cmds = keybindCommandMap.get(slot);
            if (cmds == null) continue;

            String command = cmds.get(key.toUpperCase());
            if (command == null || command.isBlank()) continue;

            Bukkit.getScheduler().runTask(this, () -> dispatchCommand(player, command));
        }
    }

    @EventHandler
    public void onRecipeClick(PlayerRecipeBookClickEvent event) {
        Player player = event.getPlayer();
        if (!isSelf2x2Crafting(player.getOpenInventory())) return;

        event.setCancelled(true);
        player.closeInventory();
        Bukkit.getScheduler().runTask(this, () -> player.openWorkbench(null, true));
    }

    private void dispatchCommand(Player player, String rawCommand) {
        String resolved = PlaceholderAPI.setPlaceholders(player, rawCommand);
        if (resolved.startsWith("*")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved.substring(1));
        } else {
            Bukkit.dispatchCommand(player, resolved);
        }
    }

    public static class CSCCommand implements CommandExecutor, TabCompleter {

        @Override
        public boolean onCommand(@Nonnull CommandSender sender, @Nonnull Command command, @Nonnull String label, @Nonnull String[] args) {
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
        public List<String> onTabComplete(@Nonnull CommandSender sender, @Nonnull Command command, @Nonnull String alias, String[] args) {
            return args.length == 1 ? List.of("reload") : Collections.emptyList();
        }

        private void sendPrefixed(CommandSender sender, Component msg) {
            sender.sendMessage(Component.text("[CSC4] ", NamedTextColor.GRAY).append(msg));
        }
    }
}