package com.gmail.bobason01.util;

import org.bukkit.entity.Player;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BedrockDetector {

    private static final Set<UUID> BEDROCK_CACHE = ConcurrentHashMap.newKeySet();

    private BedrockDetector() {}

    public static boolean isBedrock(Player player) {
        UUID id = player.getUniqueId();

        // 이미 캐시에 있으면 바로 true
        if (BEDROCK_CACHE.contains(id)) return true;

        // UUID 상위 비트가 0인 경우 (00000000-로 시작하는 경우)
        if ((id.getMostSignificantBits() >>> 32) == 0) {
            BEDROCK_CACHE.add(id);
            return true;
        }

        // Floodgate 권한 확인 (빠른 경로)
        try {
            if (player.hasPermission("floodgate.player")) {
                BEDROCK_CACHE.add(id);
                return true;
            }
        } catch (Throwable ignored) {}

        return false;
    }
}
