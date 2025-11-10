package com.gmail.bobason01.util;

import org.bukkit.entity.Player;

public final class BedrockDetector {

    private BedrockDetector() {}

    public static boolean isBedrock(Player player) {
        // Primary check using Floodgate uuid pattern
        if (player.getUniqueId().toString().startsWith("00000000")) {
            return true;
        }
        // Secondary check using permissions added by Floodgate
        try {
            return player.getEffectivePermissions().stream()
                    .anyMatch(p -> {
                        String perm = p.getPermission();
                        return perm != null && perm.startsWith("floodgate.");
                    });
        } catch (Throwable ignored) {
        }
        return false;
    }
}
