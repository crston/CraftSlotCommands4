package com.gmail.bobason01.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * SchedulerUtil:
 * Folia / Paper / Spigot 전부 자동 감지 및 호환되는 스케줄러 유틸.
 *
 * 런타임에 Folia 환경을 감지하여 region-safe 스케줄러를 자동 사용.
 * 모든 run, runLater, runForPlayer 호출은 안전하게 실행됨.
 */
public final class SchedulerUtil {

    private static boolean folia = false;

    static {
        detectFolia();
    }

    private SchedulerUtil() {}

    private static void detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;
            Bukkit.getLogger().info("[SchedulerUtil] Folia detected: regionized scheduling enabled.");
        } catch (ClassNotFoundException e) {
            folia = false;
        }
    }

    public static boolean isFolia() {
        return folia;
    }

    public static void run(Plugin plugin, Runnable task) {
        if (folia) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, task);
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public static void runLater(Plugin plugin, Runnable task, long delayTicks) {
        if (folia) {
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> task.run(), delayTicks);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        }
    }

    public static void runAsync(Plugin plugin, Runnable task) {
        // Folia와 Paper 모두 공용 async pool 사용 가능
        Bukkit.getAsyncScheduler().runNow(plugin, scheduledTask -> task.run());
    }

    public static void runForPlayer(Plugin plugin, Player player, Runnable task) {
        if (folia) {
            try {
                player.getScheduler().run(plugin, t -> task.run(), null);
            } catch (Throwable ignored) {}
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    public static void runForPlayerLater(Plugin plugin, Player player, Runnable task, long delayTicks) {
        if (folia) {
            try {
                player.getScheduler().runDelayed(plugin, t -> task.run(), null, delayTicks);
            } catch (Throwable ignored) {}
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        }
    }
}
