package com.steven.assets.service;

import com.steven.assets.dto.StockAlertDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 複合條件群組的條件清單驗證 {@code StockAlertService.validateConditions}（Requirement 16 / Task 253）。
 *
 * <p>這幾條規則是使用者送出表單時的第一道防線（違反 → 400 ProblemDetail），
 * 也是「空群組 / 單一成員群組不得進入群組評估路徑」的源頭保證
 * —— 群組評估端的 {@code groupTriggered} 只是第二道，見 {@code StockAlertGroupMatchTest}。
 * 「與其他群組重複」那條需查 DB，留在 service 內、不在此涵蓋。
 */
class StockAlertGroupValidationTest {

    private static StockAlertDto.ConditionItem cond(String alertType, Integer maPeriod, String threshold) {
        return new StockAlertDto.ConditionItem(
                alertType, maPeriod, threshold == null ? null : new BigDecimal(threshold), null);
    }

    /** 斷言丟出 IllegalArgumentException 且訊息含指定片段（訊息會原樣呈現在使用者的錯誤對話框）。 */
    private static void assertRejects(List<StockAlertDto.ConditionItem> conditions, String messagePart) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> StockAlertService.validateConditions(conditions));
        assertTrue(e.getMessage() != null && e.getMessage().contains(messagePart),
                "錯誤訊息應含「%s」，實際為：%s".formatted(messagePart, e.getMessage()));
    }

    @Test
    @DisplayName("只有 1 個條件 → 拒絕（AND 至少要兩條才有意義）")
    void rejectsSingleCondition() {
        assertRejects(List.of(cond("KD_BELOW", null, "15")), "至少需要 2 個條件");
    }

    @Test
    @DisplayName("conditions 為 null → 同樣以「至少需要 2 個條件」拒絕，不丟 NPE")
    void rejectsNullConditions() {
        assertRejects(null, "至少需要 2 個條件");
    }

    @Test
    @DisplayName("6 個條件 → 拒絕（上限 5）")
    void rejectsMoreThanFiveConditions() {
        assertRejects(List.of(
                cond("PRICE_ABOVE", null, "1"),
                cond("PRICE_BELOW", null, "2"),
                cond("KD_ABOVE", null, "3"),
                cond("KD_BELOW", null, "4"),
                cond("KD_D_ABOVE", null, "5"),
                cond("KD_D_BELOW", null, "6")), "最多 5 個條件");
    }

    @Test
    @DisplayName("組內兩條完全相同 → 拒絕；0 與 0.0000 的 scale 差異必須判為相同")
    void rejectsDuplicateConditionIgnoringScale() {
        // threshold 用 compareTo 而非 equals 比：BigDecimal("0").equals(BigDecimal("0.0000")) 為 false，
        // 用 equals 會讓使用者把同一條件輸入兩次卻通過驗證，合併 label 出現重複文案。
        assertRejects(List.of(
                cond("MA_BELOW_PCT", 60, "0"),
                cond("MA_BELOW_PCT", 60, "0.0000")), "重複");
    }

    @Test
    @DisplayName("MA_ABOVE_PCT 沒帶 maPeriod → 拒絕（無從決定比哪一條均線）")
    void rejectsMaTypeWithoutPeriod() {
        assertRejects(List.of(
                cond("MA_ABOVE_PCT", null, "5"),
                cond("KD_BELOW", null, "15")), "不合法");
    }

    @Test
    @DisplayName("KD_ABOVE 卻帶 maPeriod → 拒絕（前端組錯 payload，靜默忽略會讓使用者以為設定生效）")
    void rejectsNonMaTypeWithPeriod() {
        assertRejects(List.of(
                cond("KD_ABOVE", 60, "80"),
                cond("KD_BELOW", null, "15")), "不合法");
    }

    @Test
    @DisplayName("未知的 alertType → 拒絕")
    void rejectsUnknownAlertType() {
        assertRejects(List.of(
                cond("MACD_CROSS", null, "0"),
                cond("KD_BELOW", null, "15")), "不合法");
    }

    @Test
    @DisplayName("alertType 為 null → 拒絕，不丟 NPE")
    void rejectsNullAlertType() {
        assertRejects(List.of(
                cond(null, null, "1"),
                cond("KD_BELOW", null, "15")), "不合法");
    }

    @Test
    @DisplayName("條件本身為 null（JSON 陣列含 null 元素）→ 拒絕，不丟 NPE")
    void rejectsNullConditionItem() {
        assertRejects(Arrays.asList(
                null,
                cond("KD_BELOW", null, "15")), "不合法");
    }

    @Test
    @DisplayName("threshold 未填 → 拒絕（判定與文案都要用到門檻值）")
    void rejectsNullThreshold() {
        assertRejects(List.of(
                cond("KD_ABOVE", null, null),
                cond("KD_BELOW", null, "15")), "不合法");
    }

    @Test
    @DisplayName("合法的 2 個條件 → 通過")
    void acceptsTwoValidConditions() {
        assertDoesNotThrow(() -> StockAlertService.validateConditions(List.of(
                cond("MA_BELOW_PCT", 60, "10"),
                cond("KD_BELOW", null, "15"))));
    }

    @Test
    @DisplayName("合法的 5 個條件 → 通過（上限邊界不得誤擋）")
    void acceptsFiveValidConditions() {
        assertDoesNotThrow(() -> StockAlertService.validateConditions(List.of(
                cond("PRICE_ABOVE", null, "1"),
                cond("PRICE_BELOW", null, "2"),
                cond("KD_ABOVE", null, "3"),
                cond("KD_BELOW", null, "4"),
                cond("KD_D_ABOVE", null, "5"))));
    }

    @Test
    @DisplayName("同型別但門檻不同 → 通過（不擋恆偽組合，那是使用者的自由）")
    void acceptsSameTypeDifferentThreshold() {
        assertDoesNotThrow(() -> StockAlertService.validateConditions(List.of(
                cond("PRICE_ABOVE", null, "300"),
                cond("PRICE_BELOW", null, "200"))));
    }
}
