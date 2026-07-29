package com.steven.assets.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 複合條件群組的 AND 判定 {@code StockAlertService.groupTriggered}（Requirement 16 / Task 253）。
 *
 * <p>單一成員的判定（{@code matches}）依賴 repository 與現價，不在此涵蓋；
 * 這裡守的是把各成員判定結果合成群組結論的那一步，特別是<b>空集合陷阱</b>：
 * {@code Stream.allMatch} 對空集合恆真，少了筆數下限，一個沒有成員的群組會變成
 * 「無條件觸發、每 24 小時寄一封信」，而且完全沒有錯誤訊息可循。
 */
class StockAlertGroupMatchTest {

    @Test
    @DisplayName("兩個條件都成立 → 觸發")
    void allTrueTriggers() {
        assertTrue(StockAlertService.groupTriggered(List.of(true, true)));
    }

    @Test
    @DisplayName("三個條件都成立 → 觸發")
    void threeTrueTriggers() {
        assertTrue(StockAlertService.groupTriggered(List.of(true, true, true)));
    }

    @Test
    @DisplayName("其中一個不成立 → 不觸發（AND，不是 OR）")
    void oneFalseDoesNotTrigger() {
        assertFalse(StockAlertService.groupTriggered(List.of(true, false)));
    }

    @Test
    @DisplayName("全部不成立 → 不觸發")
    void allFalseDoesNotTrigger() {
        assertFalse(StockAlertService.groupTriggered(List.of(false, false)));
    }

    @Test
    @DisplayName("只有 1 個成員 → 不觸發（那是獨立條件，不該走群組路徑）")
    void singleMemberDoesNotTrigger() {
        assertFalse(StockAlertService.groupTriggered(List.of(true)));
    }

    @Test
    @DisplayName("空集合 → 不觸發（allMatch 對空集合恆真的陷阱）")
    void emptyDoesNotTrigger() {
        assertFalse(StockAlertService.groupTriggered(List.of()));
    }

    @Test
    @DisplayName("成員結果含 null → 視為不成立，且不丟 NPE")
    void nullMemberResultDoesNotTrigger() {
        assertFalse(StockAlertService.groupTriggered(Arrays.asList(true, null)));
    }

    @Test
    @DisplayName("結果清單為 null → 不觸發，且不丟 NPE")
    void nullListDoesNotTrigger() {
        assertFalse(StockAlertService.groupTriggered(null));
    }
}
