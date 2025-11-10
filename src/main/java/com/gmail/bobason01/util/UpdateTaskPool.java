package com.gmail.bobason01.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UpdateTaskPool coalesces delayed tasks per player.
 * Only one scheduled task per player runs at the nearest delay.
 */
public final class UpdateTaskPool {

    private static final Map<UUID, BukkitTask> TASKS = new ConcurrentHashMap<>();
    private static Plugin plugin;

    private UpdateTaskPool() {}

    public static void init(Plugin pl) {
        plugin = pl;
    }

    public static void scheduleCoalesced(UUID uuid, long delayTicks, Runnable action) {
        if (plugin == null) {
            // Fallback no-op if init was not called yet
            Bukkit.getScheduler().runTaskLater(Bukkit.getPluginManager().getPlugins()[0], action, delayTicks);
            return;
        }
        BukkitTask prev = TASKS.get(uuid);
        if (prev != null) {
            prev.cancel();
        }
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            TASKS.remove(uuid);
            action.run();
        }, delayTicks);
        TASKS.put(uuid, task);
    }

    public static void shutdown() {
        for (BukkitTask t : TASKS.values()) {
            try { t.cancel(); } catch (Throwable ignored) {}
        }
        TASKS.clear();
    }
}
