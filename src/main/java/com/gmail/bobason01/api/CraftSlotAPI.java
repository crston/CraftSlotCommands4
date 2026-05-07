package com.gmail.bobason01.api;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

// 플러그인 외부에서 접근할 수 있는 통합 API 인터페이스입니다
public interface CraftSlotAPI {

    // 특정 슬롯의 가짜 아이템을 반환합니다
    ItemStack getFakeItem(int slot);

    // 해당 슬롯이 가짜 아이템 슬롯으로 사용 중인지 확인합니다
    boolean isFakeSlot(int slot);

    // 플레이어의 인벤토리 뷰를 강제로 새로고침합니다
    void forceUpdatePlayerView(Player player);

    // 커스텀 모델 데이터와 최신 아이템 모델을 아이템 메타에 통합 적용합니다
    void applyModelIntegration(ItemMeta meta, int customModelData, String itemModelKey);

}