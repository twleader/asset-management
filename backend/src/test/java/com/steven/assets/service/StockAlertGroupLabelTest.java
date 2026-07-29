package com.steven.assets.service;

import com.steven.assets.model.StockAlert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 複合條件群組的合併文案 {@code StockAlertService.buildGroupLabel}（Requirement 16 / Task 253）。
 *
 * <p>警示頁、觀察頁、email digest、補發四條路徑都用這一支產文案，
 * 分隔符的字面值一旦分歧就會有一邊對不上，所以這裡一律<b>斷言整個字串逐字相等</b>
 * ——用 {@code contains("且")} 會讓寫成沒有前後空白的 {@code "且"} 也通過，
 * 而其他端假設的是 {@code " 且 "}（半形空白 + 且 + 半形空白）。
 */
class StockAlertGroupLabelTest {

    /** 產一個只有判定 / 文案欄位的成員；buildLabel 只讀 alertType / maPeriod / threshold。 */
    private static StockAlert member(String alertType, Integer maPeriod, String threshold) {
        return StockAlert.builder()
                .alertType(alertType)
                .maPeriod(maPeriod)
                .threshold(new BigDecimal(threshold))
                .build();
    }

    @Test
    @DisplayName("兩個條件以「 且 」串接，順序即傳入順序（逐字相等）")
    void twoConditionsJoined() {
        assertEquals("低於季線 10% 且 K 值低於 15",
                StockAlertService.buildGroupLabel(
                        List.of(member("MA_BELOW_PCT", 60, "10"),
                                member("KD_BELOW", null, "15")),
                        null));
    }

    @Test
    @DisplayName("三個條件有兩個「 且 」分隔符")
    void threeConditionsJoined() {
        assertEquals("低於季線 10% 且 K 值低於 15 且 股價高於 100",
                StockAlertService.buildGroupLabel(
                        List.of(member("MA_BELOW_PCT", 60, "10"),
                                member("KD_BELOW", null, "15"),
                                member("PRICE_ABOVE", null, "100")),
                        null));
    }

    @Test
    @DisplayName("單一條件無分隔符，回傳值等於該條件自己的 label")
    void singleConditionHasNoSeparator() {
        StockAlert only = member("KD_BELOW", null, "15");
        assertEquals(StockAlertService.buildLabel(only, null),
                StockAlertService.buildGroupLabel(List.of(only), null));
        assertEquals("K 值低於 15", StockAlertService.buildGroupLabel(List.of(only), null));
    }

    @Test
    @DisplayName("空 list 回空字串、不丟例外（成員被外力清掉的孤兒群組不該讓整份清單載入失敗）")
    void emptyMembersReturnsEmptyString() {
        assertEquals("", StockAlertService.buildGroupLabel(List.of(), null));
        assertEquals("", StockAlertService.buildGroupLabel(null, null));
    }

    @Test
    @DisplayName("帶指標時只有 MA_*_PCT 且 threshold≠0 的成員附換算觸發價，其餘成員不受影響")
    void maMemberGetsTriggerPriceSuffix() {
        // 季線 102.32 × (1 - 10%) = 92.088 → 四捨五入到小數 2 位 = 92.09
        TechnicalIndicatorService.FullIndicators ind = new TechnicalIndicatorService.FullIndicators(
                new BigDecimal("55.00"),    // 月線（本例的成員用不到，故意給不同值以免抓錯均線也剛好通過）
                new BigDecimal("102.32"),   // 季線
                new BigDecimal("77.00"),    // 年線
                new BigDecimal("12.3"), new BigDecimal("14.5"),
                new BigDecimal("11.0"), new BigDecimal("13.0"));

        assertEquals("低於季線 10%（92.09） 且 K 值低於 15",
                StockAlertService.buildGroupLabel(
                        List.of(member("MA_BELOW_PCT", 60, "10"),
                                member("KD_BELOW", null, "15")),
                        ind));
    }

    @Test
    @DisplayName("threshold=0 的 MA 條件不附觸發價（「剛跨過」語意沒有百分比可換算）")
    void zeroThresholdMaHasNoSuffix() {
        TechnicalIndicatorService.FullIndicators ind = new TechnicalIndicatorService.FullIndicators(
                new BigDecimal("55.00"), new BigDecimal("102.32"), new BigDecimal("77.00"),
                new BigDecimal("12.3"), new BigDecimal("14.5"),
                new BigDecimal("11.0"), new BigDecimal("13.0"));

        assertEquals("低於季線 且 K 值低於 15",
                StockAlertService.buildGroupLabel(
                        List.of(member("MA_BELOW_PCT", 60, "0"),
                                member("KD_BELOW", null, "15")),
                        ind));
    }
}
