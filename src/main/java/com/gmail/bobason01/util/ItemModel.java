package com.gmail.bobason01.util;

import java.util.Collections;
import java.util.List;

// 아이템 모델 데이터 구조체입니다
public record ItemModel(
        String material,
        String name,
        List<String> lore,
        int model,
        String itemModel,
        int damage,
        boolean unbreakable,
        boolean stripAttributes,
        boolean hideAllFlags,
        List<String> hideFlags
) {
    public ItemModel {
        lore = lore == null ? Collections.emptyList() : List.copyOf(lore);
        hideFlags = hideFlags == null ? Collections.emptyList() : List.copyOf(hideFlags);
        material = material == null ? "BARRIER" : material;
    }
}