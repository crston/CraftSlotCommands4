package com.gmail.bobason01.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.function.Consumer;

public final class SchedulerUtil {

    private static boolean folia = false;

    private static Object globalRegionScheduler;
    private static Object asyncScheduler;

    private static Method globalExecuteMethod;
    private static Method globalRunDelayedMethod;
    private static Method asyncRunNowMethod;
    private static Method playerSchedulerMethod;
    private static Method playerRunMethod;
    private static Method playerRunDelayedMethod;

    static {
        detectFolia();
    }

    private SchedulerUtil() {}

    private static void detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            folia = true;

            Method getGlobalRegionScheduler = Bukkit.class.getMethod("getGlobalRegionScheduler");
            globalRegionScheduler = getGlobalRegionScheduler.invoke(null);
            Class<?> globalSchedulerClass = globalRegionScheduler.getClass();

            globalExecuteMethod = globalSchedulerClass.getMethod("execute", Plugin.class, Runnable.class);
            globalRunDelayedMethod = globalSchedulerClass.getMethod("runDelayed", Plugin.class, Consumer.class, long.class);

            Method getAsyncScheduler = Bukkit.class.getMethod("getAsyncScheduler");
            asyncScheduler = getAsyncScheduler.invoke(null);
            Class<?> asyncSchedulerClass = asyncScheduler.getClass();

            asyncRunNowMethod = asyncSchedulerClass.getMethod("runNow", Plugin.class, Consumer.class);

            playerSchedulerMethod = Player.class.getMethod("getScheduler");
            Class<?> playerSchedulerClass = playerSchedulerMethod.getReturnType();

            playerRunMethod = playerSchedulerClass.getMethod("run", Plugin.class, Consumer.class, Runnable.class);
            playerRunDelayedMethod = playerSchedulerClass.getMethod("runDelayed", Plugin.class, Consumer.class, Runnable.class, long.class);

            Bukkit.getLogger().info("Folia detected: Reflection-backed regionized scheduling enabled");
        } catch (Throwable e) {
            folia = false;
            globalRegionScheduler = null;
            asyncScheduler = null;
        }
    }

    public static boolean isFolia() {
        return folia;
    }

    public static void run(Plugin plugin, Runnable task) {
        if (folia && globalExecuteMethod != null) {
            try {
                globalExecuteMethod.invoke(globalRegionScheduler, plugin, task);
                return;
            } catch (Throwable ignored) {}
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }

    public static void runLater(Plugin plugin, Runnable task, long delayTicks) {
        if (folia && globalRunDelayedMethod != null) {
            try {
                Consumer<Object> consumer = t -> task.run();
                globalRunDelayedMethod.invoke(globalRegionScheduler, plugin, consumer, delayTicks);
                return;
            } catch (Throwable ignored) {}
        }
        Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
    }

    public static void runAsync(Plugin plugin, Runnable task) {
        if (folia && asyncRunNowMethod != null) {
            try {
                Consumer<Object> consumer = scheduledTask -> task.run();
                asyncRunNowMethod.invoke(asyncScheduler, plugin, consumer);
                return;
            } catch (Throwable ignored) {}
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    public static void runForPlayer(Plugin plugin, Player player, Runnable task) {
        if (folia && playerSchedulerMethod != null && playerRunMethod != null) {
            try {
                Object scheduler = playerSchedulerMethod.invoke(player);
                Consumer<Object> consumer = t -> task.run();
                playerRunMethod.invoke(scheduler, plugin, consumer, null);
                return;
            } catch (Throwable ignored) {}
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }

    public static void runForPlayerLater(Plugin plugin, Player player, Runnable task, long delayTicks) {
        if (folia && playerSchedulerMethod != null && playerRunDelayedMethod != null) {
            try {
                Object scheduler = playerSchedulerMethod.invoke(player);
                Consumer<Object> consumer = t -> task.run();
                playerRunDelayedMethod.invoke(scheduler, plugin, consumer, null, delayTicks);
                return;
            } catch (Throwable ignored) {}
        }
        Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
    }
}