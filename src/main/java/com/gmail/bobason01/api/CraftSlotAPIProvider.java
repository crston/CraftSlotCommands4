package com.gmail.bobason01.api;

// API 인스턴스를 보관하고 제공하는 프로바이더 클래스입니다
public final class CraftSlotAPIProvider {

    private static CraftSlotAPI api;

    private CraftSlotAPIProvider() {}

    // API 구현체를 등록합니다
    public static void register(CraftSlotAPI implementation) {
        api = implementation;
    }

    // 등록된 API 인스턴스를 가져옵니다
    public static CraftSlotAPI get() {
        return api;
    }

}