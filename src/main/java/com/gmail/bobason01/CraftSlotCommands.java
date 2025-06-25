package com.gmail.bobason01;

import com.gmail.bobason01.listener.CraftSlotFakeItemListener;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.CraftingInventory;
import org.bukkit.plugin.java.JavaPlugin;

import javax.annotation.Nonnull;
import java.util.*;

import static com.gmail.bobason01.util.InventoryUtil.isSelf2x2Crafting;

public class CraftSlotCommands extends JavaPlugin implements Listener {

    private static final int MIN_MENU_SLOT = 0;
    private static final int MAX_MENU_SLOT = 4;

    private static CraftSlotCommands instance;
    private CraftSlotFakeItemListener fakeItemListener;

    private final Map<Integer, String> slotCommandCache = new HashMap<>();
    private final Map<Integer, Boolean> slotUsageMap = new HashMap<>();

    public static CraftSlotCommands getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;

        if (isPluginEnabled("ProtocolLib") || isPluginEnabled("PlaceholderAPI")) {
            getLogger().severe("Required dependencies missing! Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig();
        registerCommand();
        registerEvents();
        reloadPlugin();
    }

    private boolean isPluginEnabled(String plugin) {
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

    public void reloadPlugin() {
        reloadConfig();

        slotCommandCache.clear();
        slotUsageMap.clear();

        ConfigurationSection useSlotSec = getConfig().getConfigurationSection("use-slot");
        ConfigurationSection craftingSlotSec = getConfig().getConfigurationSection("crafting-slot");

        if (useSlotSec == null) return;

        for (String key : useSlotSec.getKeys(false)) {
            try {
                int slot = Integer.parseInt(key);
                boolean enabled = useSlotSec.getBoolean(key);
                slotUsageMap.put(slot, enabled);

                if (enabled && craftingSlotSec != null) {
                    String cmd = craftingSlotSec.getString(key, "").trim();
                    if (!cmd.isEmpty()) slotCommandCache.put(slot, cmd);
                }
            } catch (NumberFormatException ignored) {}
        }

        if (fakeItemListener != null) {
            fakeItemListener.reload(getConfig());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        int slot = event.getRawSlot();
        if (slot < MIN_MENU_SLOT || slot > MAX_MENU_SLOT) return;

        Player player = (event.getWhoClicked() instanceof Player p) ? p : null;
        if (player == null || !(event.getInventory() instanceof CraftingInventory) ||
                player.getGameMode() == GameMode.CREATIVE || !isSelf2x2Crafting(player.getOpenInventory())) {
            return;
        }

        if (!slotUsageMap.getOrDefault(slot, false)) return;

        String command = slotCommandCache.get(slot);
        if (command == null || command.isBlank()) return;

        event.setCancelled(true);

        Bukkit.getScheduler().runTask(this, () -> {
            String resolved = PlaceholderAPI.setPlaceholders(player, command);
            if (resolved.startsWith("*")) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved.substring(1));
            } else {
                Bukkit.dispatchCommand(player, resolved);
            }
        });
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
            } else {
                sendPrefixed(sender, Component.text("CraftSlotCommands", NamedTextColor.AQUA));
            }

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

    public static class CrstOnCommand implements CommandExecutor {
        @Override
        public boolean onCommand(@Nonnull CommandSender sender, @Nonnull Command command, @Nonnull String label, @Nonnull String[] args) {
            if (sender instanceof Player player) {
                player.sendMessage(Component.text("Discord - crston", NamedTextColor.LIGHT_PURPLE));
            } else {
                sender.sendMessage("This command can only be used by players.");
            }
            return true;
        }
    }
}
