package com.gmail.bobason01.util;

/** Crafting-grid menu slots only (player inv raw 0–4). */
public final class MenuSlots {

    public static final int CRAFT_MIN = 0;
    public static final int CRAFT_MAX = 4;
    public static final int SLOT_COUNT = 5;

    private MenuSlots() {}

    public static boolean isCraftSlot(int rawSlot) {
        return rawSlot >= CRAFT_MIN && rawSlot <= CRAFT_MAX;
    }

    public static boolean isMenuRange(int rawSlot) {
        return isCraftSlot(rawSlot);
    }
}
