package com.steven.assets.bff.gdptwse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 釘住 {@link MarketIndexChartService#movingAverage(List, int)} 的 MA 定義（Requirement 18／45、Task 285／317）。
 *
 * <p><b>為什麼需要這一支：</b>「週線MA5」同一個語意的值有兩份實作——本圖表走這裡，
 * 而該頁匯出的「週線MA5」欄走 business 的 {@code ExcelExportService.indexMaAt}。兩者在不同 Maven 專案
 * （{@code asset-management-bff} 與 {@code asset-management}，無共同 parent、彼此無相依），
 * <b>寫不出「A 等於 B」的跨模組測試</b>；改以「同定義同精度」保證同值，代償就是兩邊各自把定義釘在
 * 自己的測試裡——任一邊改了視窗／null 行為／捨入精度，該邊就會紅。
 *
 * <p>取捨與被放棄的選項見 spec/design.md 的 Requirement 45「週線MA5：唯一的計算欄」。
 *
 * <p>{@code movingAverage} 是 package-private 純函式，不需要起 Spring context 或 mock WebClient。
 */
class GdpTwseBffControllerMaTest {

    private static List<BigDecimal> closes(String... values) {
        return java.util.Arrays.stream(values).map(BigDecimal::new).toList();
    }

    @Test
    @DisplayName("MA5：視窗未滿填 null、長度等於輸入、值為精確平均的 2 位 HALF_UP")
    void ma5定義() {
        // 與 backend DualFormatSingleTableExportTest 的 MA5 fixture 同一組收盤值，期望值同樣在測試內手算
        List<BigDecimal> ma5 = MarketIndexChartService.movingAverage(
                closes("100.00", "100.01", "100.02", "100.03", "100.05", "100.03", "100.07"), 5);

        assertThat(ma5).as("回傳長度須等於輸入長度").hasSize(7);
        assertThat(ma5.subList(0, 4)).as("視窗未滿一律 null，不得以不足視窗的平均充數")
                .containsExactly(null, null, null, null);

        // 100.00+100.01+100.02+100.03+100.05 = 500.11 → /5 = 100.022 → HALF_UP(2) = 100.02
        assertThat((BigDecimal) ma5.get(4)).isEqualByComparingTo("100.02");
        // 100.01+100.02+100.03+100.05+100.03 = 500.14 → /5 = 100.028 → HALF_UP(2) = 100.03（進位）
        assertThat((BigDecimal) ma5.get(5)).isEqualByComparingTo("100.03");
        // 100.02+100.03+100.05+100.03+100.07 = 500.20 → /5 = 100.04（整除）
        assertThat((BigDecimal) ma5.get(6)).isEqualByComparingTo("100.04");

        // 精度是契約的一部分：scale 必須恰為 2（不是「值相等就好」）
        assertThat(((BigDecimal) ma5.get(4)).scale()).as("一律 2 位小數").isEqualTo(2);
    }

    @Test
    @DisplayName("MA5：4 位小數的海外指數收盤同樣捨入到 2 位（精確加總，不得先轉 double）")
    void 海外指數精度() {
        // us_index_daily_history.close_point 為 numeric(14,4)
        List<BigDecimal> ma5 = MarketIndexChartService.movingAverage(
                closes("5000.1234", "5000.2345", "5000.3456", "5000.4567", "5000.5678"), 5);

        // 合計 25001.7280 → /5 = 5000.3456 → HALF_UP(2) = 5000.35
        assertThat((BigDecimal) ma5.get(4)).isEqualByComparingTo("5000.35");
    }

    @Test
    @DisplayName("資料筆數少於視窗時整段皆為 null（不回空清單、不縮短長度）")
    void 資料不足視窗() {
        List<BigDecimal> ma5 = MarketIndexChartService.movingAverage(closes("100.00", "100.01"), 5);
        assertThat(ma5).containsExactly(null, null);
    }
}
