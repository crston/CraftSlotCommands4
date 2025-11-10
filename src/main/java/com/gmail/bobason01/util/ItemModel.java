package com.gmail.bobason01.util;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

public record ItemModel(
        String material,
        String name,
        List<String> lore,
        int model,
        int damage,
        boolean unbreakable,
        boolean stripAttributes,
        boolean hideAllFlags,
        List<String> hideFlags
) {
    public ItemModel {
        // null 방지 처리 (불변 레코드 특성상 생성자 내부에서 초기화)
        lore = lore == null ? Collections.emptyList() : List.copyOf(lore);
        hideFlags = hideFlags == null ? Collections.emptyList() : List.copyOf(hideFlags);
        material = Objects.requireNonNullElse(material, "BARRIER");
    }

    /**
     * 간단한 디버깅용 문자열 표현
     */
    @Override
    public String toString() {
        return "ItemModel[" +
                "material=" + material +
                ", name=" + name +
                ", model=" + model +
                ", damage=" + damage +
                ", unbreakable=" + unbreakable +
                ", stripAttributes=" + stripAttributes +
                ", hideAllFlags=" + hideAllFlags +
                ", loreSize=" + lore.size() +
                ", hideFlagsSize=" + hideFlags.size() +
                ']';
    }
}
