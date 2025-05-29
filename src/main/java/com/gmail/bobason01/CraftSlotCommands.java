package com.gmail.bobason01;

import com.gmail.bobason01.listener.CraftSlotFakeItemListener;
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

    private static CraftSlotCommands instance;
    private CraftSlotFakeItemListener fakeItemListener;
    private ConfigurationSection craftingSlotSection;

    private final Map<Integer, String> slotCommandCache = new HashMap<>();
    private final Map<Integer, Boolean> slotUsageMap = new HashMap<>();

    public static CraftSlotCommands getInstance() {
        return instance;
    }

    @Override
    public void onEnable() {
        if (Bukkit.getPluginManager().getPlugin("ProtocolLib") == null) {
            getLogger().severe("ProtocolLib not found! Disabling CraftSlotCommands4");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        instance = this;

        saveDefaultConfig();
        registerCommand();
        registerEvents();
        reloadPlugin();
    }

    private void registerCommand() {
        PluginCommand cmd = getCommand("craftslotcommands");
        if (cmd != null) {
            CSCCommand command = new CSCCommand();
            cmd.setExecutor(command);
            cmd.setTabCompleter(command);
        } else {
            getLogger().warning("Command 'craftslotcommands' not defined in plugin.yml!");
        }
    }

    private void registerEvents() {
        Bukkit.getPluginManager().registerEvents(this, this);
        fakeItemListener = new CraftSlotFakeItemListener(getConfig(), this);
        Bukkit.getPluginManager().registerEvents(fakeItemListener, this);
    }

    public void reloadPlugin() {
        reloadConfig();
        craftingSlotSection = getConfig().getConfigurationSection("crafting-slot");

        slotCommandCache.clear();
        if (craftingSlotSection != null) {
            for (String key : craftingSlotSection.getKeys(false)) {
                try {
                    int slot = Integer.parseInt(key);
                    String command = craftingSlotSection.getString(key);
                    if (command != null && !command.isBlank()) {
                        slotCommandCache.put(slot, command);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        ConfigurationSection useSlotSec = getConfig().getConfigurationSection("use-slot");
        slotUsageMap.clear();
        if (useSlotSec != null) {
            for (String key : useSlotSec.getKeys(false)) {
                try {
                    int slot = Integer.parseInt(key);
                    slotUsageMap.put(slot, useSlotSec.getBoolean(key));
                } catch (NumberFormatException ignored) {}
            }
        }

        if (fakeItemListener != null) {
            fakeItemListener.reload(getConfig());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot > 4) return;

        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (!(event.getInventory() instanceof CraftingInventory)) return;
        if (!isSelf2x2Crafting(player.getOpenInventory())) return;
        if (player.getGameMode() == GameMode.CREATIVE) return;

        if (!slotUsageMap.getOrDefault(rawSlot, false)) return;

        String command = slotCommandCache.get(rawSlot);
        if (command == null) return;

        event.setCancelled(true);

        Bukkit.getScheduler().runTask(this, () -> {
            String resolved = command.replace("%player%", player.getName());
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
                sendPrefixed(sender, Component.text("CraftSlotCommands4", NamedTextColor.AQUA));
            }

            return true;
        }

        @Override
        public List<String> onTabComplete(@Nonnull CommandSender sender, @Nonnull Command command, @Nonnull String alias, String[] args) {
            if (args.length == 1) {
                return List.of("reload");
            }
            return Collections.emptyList();
        }

        private void sendPrefixed(CommandSender sender, Component msg) {
            Component prefix = Component.text("[CSC4] ", NamedTextColor.GRAY);
            sender.sendMessage(prefix.append(msg));
        }
    }
}
