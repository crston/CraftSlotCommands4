package com.gmail.bobason01.util;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * UpdateTaskPool:
 * 플레이어별 하나의 예약만 유지하며, 요청이 잦은 업데이트를 효율적으로 병합 관리한다.
 *
 * 동작 요약:
 * - 더 빠른 실행 요청이 들어오면 기존 예약을 취소하고 새로 예약.
 * - 더 느린 실행 요청은 기존 예약 유지, 액션만 최신으로 교체.
 * - init()으로 Plugin을 등록한 후 사용해야 한다.
 */
public final class UpdateTaskPool {

    private static final class Entry {
        BukkitTask task;
        long runAtTick;
        Runnable action;
    }

    private static final Map<UUID, Entry> TASKS = new ConcurrentHashMap<>();
    private static volatile Plugin plugin;

    private UpdateTaskPool() {}

    /**
     * 플러그인 인스턴스 초기화 (필수 호출)
     */
    public static void init(Plugin pl) {
        plugin = pl;
    }

    /**
     * 지정된 UUID에 대한 업데이트를 병합 예약한다.
     * 동일 UUID는 하나의 예약만 유지된다.
     */
    public static void scheduleCoalesced(UUID uuid, long delayTicks, Runnable action) {
        if (plugin == null) {
            // 안전장치: init이 안된 경우 가능한 첫 플러그인으로 예약
            Plugin fallback = safeFallbackPlugin();
            if (fallback != null) {
                Bukkit.getScheduler().runTaskLater(fallback, wrapSafe(action), Math.max(0L, delayTicks));
            }
            return;
        }

        final BukkitScheduler scheduler = Bukkit.getScheduler();
        final long desiredTick = Bukkit.getCurrentTick() + Math.max(0L, delayTicks);

        TASKS.compute(uuid, (id, prev) -> {
            if (prev == null) {
                Entry entry = new Entry();
                entry.runAtTick = desiredTick;
                entry.action = action;
                entry.task = scheduler.runTaskLater(plugin, () -> runAndClear(id), delayTicks);
                return entry;
            }

            // 새 요청이 더 빠를 경우 기존 예약 취소 후 재예약
            if (desiredTick < prev.runAtTick) {
                cancelSafely(prev.task);
                prev.runAtTick = desiredTick;
                prev.action = action;
                long newDelay = Math.max(0L, desiredTick - Bukkit.getCurrentTick());
                prev.task = scheduler.runTaskLater(plugin, () -> runAndClear(id), newDelay);
                return prev;
            }

            // 기존 예약이 더 빠르면 실행 시점은 그대로, 액션만 교체
            prev.action = action;
            return prev;
        });
    }

    private static void runAndClear(UUID id) {
        Entry entry = TASKS.remove(id);
        if (entry == null) return;

        Runnable action = entry.action;
        if (action != null) {
            try {
                action.run();
            } catch (Throwable t) {
                Bukkit.getLogger().warning("[UpdateTaskPool] Action execution error: " + t.getMessage());
            }
        }
    }

    /**
     * 모든 예약 취소 및 정리
     */
    public static void shutdown() {
        for (Entry entry : TASKS.values()) {
            cancelSafely(entry.task);
        }
        TASKS.clear();
    }

    /**
     * 안전하게 이전 태스크 취소
     */
    private static void cancelSafely(BukkitTask task) {
        if (task == null) return;
        try {
            task.cancel();
        } catch (Throwable ignored) {}
    }

    /**
     * null-safe로 래핑된 Runnable
     */
    private static Runnable wrapSafe(Runnable action) {
        return () -> {
            try {
                if (action != null) action.run();
            } catch (Throwable ignored) {}
        };
    }

    /**
     * init() 호출 전 대비용 fallback 플러그인 선택
     */
    private static Plugin safeFallbackPlugin() {
        try {
            Plugin[] plugins = Bukkit.getPluginManager().getPlugins();
            return plugins.length > 0 ? plugins[0] : null;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
