package com.gmail.bobason01.util;

import java.util.List;

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
) {}
