package com.gmail.bobason01.util;

import org.bukkit.entity.Player;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// 베드락 에디션 접속 유저를 검사하는 유틸리티입니다
public final class BedrockDetector {

    private static final Set<UUID> BEDROCK_CACHE = ConcurrentHashMap.newKeySet();

    private BedrockDetector() {}

    public static boolean isBedrock(Player player) {
        UUID id = player.getUniqueId();

        if (BEDROCK_CACHE.contains(id)) return true;

        if ((id.getMostSignificantBits() >>> 32) == 0) {
            BEDROCK_CACHE.add(id);
            return true;
        }

        try {
            if (player.hasPermission("floodgate.player")) {
                BEDROCK_CACHE.add(id);
                return true;
            }
        } catch (Throwable ignored) {}

        return false;
    }
}