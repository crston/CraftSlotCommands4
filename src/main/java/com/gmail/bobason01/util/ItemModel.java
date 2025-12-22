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
        lore = lore == null ? Collections.emptyList() : List.copyOf(lore);
        hideFlags = hideFlags == null ? Collections.emptyList() : List.copyOf(hideFlags);
        material = Objects.requireNonNullElse(material, "BARRIER");
    }

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