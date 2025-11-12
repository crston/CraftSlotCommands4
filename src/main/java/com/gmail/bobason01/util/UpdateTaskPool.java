package com.gmail.bobason01.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UpdateTaskPool:
 * - Folia/Paper 겸용 업데이트 풀.
 * - 플레이어별 1개 예약만 유지, 요청 병합(Coalescing) 방식.
 * - Folia에서는 player.getScheduler() 기반 지역 실행.
 */
public final class UpdateTaskPool {

    private static final class Entry {
        long runAtTick;
        Runnable action;
        BukkitTask task; // Paper 환경 전용 (Folia에서는 사용하지 않음)
    }

    private static final Map<UUID, Entry> TASKS = new ConcurrentHashMap<>();
    private static volatile Plugin plugin;

    private UpdateTaskPool() {}

    public static void init(Plugin pl) {
        plugin = pl;
    }

    public static void scheduleCoalesced(UUID uuid, long delayTicks, Runnable action) {
        if (plugin == null) return;

        TASKS.compute(uuid, (id, prev) -> {
            final long desiredTick = Bukkit.getCurrentTick() + Math.max(0L, delayTicks);

            if (prev == null) {
                Entry e = new Entry();
                e.runAtTick = desiredTick;
                e.action = action;
                scheduleInternal(id, action, delayTicks);
                return e;
            }

            if (desiredTick < prev.runAtTick) {
                cancelSafely(prev);
                prev.runAtTick = desiredTick;
                prev.action = action;
                scheduleInternal(id, action, delayTicks);
                return prev;
            }

            prev.action = action;
            return prev;
        });
    }

    private static void scheduleInternal(UUID id, Runnable action, long delayTicks) {
        if (SchedulerUtil.isFolia()) {
            Player player = Bukkit.getPlayer(id);
            if (player == null || !player.isOnline()) return;

            SchedulerUtil.runForPlayerLater(plugin, player, () -> runAndClear(id), delayTicks);
        } else {
            BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> runAndClear(id), delayTicks);
            Entry entry = TASKS.get(id);
            if (entry != null) entry.task = task;
        }
    }

    private static void runAndClear(UUID id) {
        Entry e = TASKS.remove(id);
        if (e == null || e.action == null) return;
        try {
            e.action.run();
        } catch (Throwable t) {
            Bukkit.getLogger().warning("[UpdateTaskPool] Task error: " + t.getMessage());
        }
    }

    public static void shutdown() {
        TASKS.values().forEach(UpdateTaskPool::cancelSafely);
        TASKS.clear();
    }

    private static void cancelSafely(Entry e) {
        if (e == null || e.task == null) return;
        try {
            e.task.cancel();
        } catch (Throwable ignored) {}
    }
}
