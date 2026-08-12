package com.steven.assets.bff.gdptwse;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link MarketIndexChartService#buildIndexDailyBody(boolean, List)} 的成交量/成交金額欄位（Task 288／317）。
 *
 * 純函式測試，不涉及 WebClient/Mono，風格比照 {@code ChartSeriesAlignerTest}。
 * 測試方法名以 e/e2/f/g 標註對應 spec 的驗證項目，方便追溯。
 */
@SuppressWarnings("unchecked")
class GdpTwseBffControllerIndexDailyVolumeTest {

    /** 台股列：tradingDate/closePoint + tradeVolume（成交股數）+ tradeValue（成交金額）。 */
    private static Map<String, Object> twRow(String date, String close, Object tradeVolume, Object tradeValue) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tradingDate", date);
        m.put("closePoint", close);
        m.put("tradeVolume", tradeVolume);
        m.put("tradeValue", tradeValue);
        return m;
    }

    /** 海外指數列：tradingDate/closePoint + volume（Yahoo 無成交金額欄）。 */
    private static Map<String, Object> overseasRow(String date, String close, Object volume) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tradingDate", date);
        m.put("closePoint", close);
        m.put("volume", volume);
        return m;
    }

    private static Map<String, Object> result(boolean tw, List<Map<String, Object>> rows) {
        return MarketIndexChartService.buildIndexDailyBody(tw, rows);
    }

    // ---------------------------------------------------------------------
    // (e) hasVolume false when the deciding column is uniformly zero or null
    // ---------------------------------------------------------------------

    @Test
    void hasVolume_falseWhenTradeValueAllZero_e() {
        // tradeValue 分別以 Integer 0 與 String "0" 表示（真實 JSON 反序列化兩種型別都可能出現）
        List<Map<String, Object>> rows = List.of(
                twRow("2026-07-01", "100", 1000, 0),
                twRow("2026-07-02", "101", 1200, "0"));

        Map<String, Object> out = result(true, rows);

        assertThat((Boolean) out.get("hasVolume")).isFalse();
    }

    @Test
    void hasVolume_falseWhenVolumeAllZeroOverseas_e() {
        // 對應真實 SOX 情境：Yahoo 的 volume 對純運算型指數恆為 0（非 null）
        List<Map<String, Object>> rows = List.of(
                overseasRow("2026-07-01", "1000.00", 0),
                overseasRow("2026-07-02", "1010.00", "0"));

        Map<String, Object> out = result(false, rows);

        assertThat((Boolean) out.get("hasVolume")).isFalse();
    }

    @Test
    void hasVolume_falseWhenDecidingFieldAllNull_e() {
        // tradeValue 全部缺席（非 0，是真的沒有這個值）
        List<Map<String, Object>> rows = List.of(
                twRow("2026-07-01", "100", 1000, null),
                twRow("2026-07-02", "101", 1200, null));

        Map<String, Object> out = result(true, rows);

        assertThat((Boolean) out.get("hasVolume")).isFalse();
    }

    @Test
    void hasVolume_trueWhenAtLeastOneNonZero_e() {
        List<Map<String, Object>> rows = List.of(
                twRow("2026-07-01", "100", 1000, 0),
                twRow("2026-07-02", "101", 1200, new BigDecimal("123456789")));

        Map<String, Object> out = result(true, rows);

        assertThat((Boolean) out.get("hasVolume")).isTrue();
    }

    @Test
    void tradeValue_acceptsSupportedNumericFormsWithoutBinaryTail() {
        List<Map<String, Object>> rows = List.of(
                twRow("2026-07-01", "100", 1000, 12),
                twRow("2026-07-02", "101", 1000, 13L),
                twRow("2026-07-03", "102", 1000, new BigDecimal("14.50")),
                twRow("2026-07-04", "103", 1000, 0.1d),
                twRow("2026-07-05", "104", 1000, 0.2f),
                twRow("2026-07-06", "105", 1000, "15.75"),
                twRow("2026-07-07", "106", 1000, null));

        List<BigDecimal> turnovers = (List<BigDecimal>) result(true, rows).get("turnovers");

        assertThat(turnovers).containsExactly(
                new BigDecimal("12"),
                new BigDecimal("13"),
                new BigDecimal("14.50"),
                new BigDecimal("0.1"),
                new BigDecimal("0.2"),
                new BigDecimal("15.75"),
                null);
        assertThat(turnovers.get(3).toPlainString()).isEqualTo("0.1");
        assertThat(turnovers.get(4).toPlainString()).isEqualTo("0.2");
    }

    @Test
    void malformedTradeValueThrowsTypedPayloadException() {
        assertThatThrownBy(() -> result(true, List.of(
                twRow("2026-07-01", "100", 1000, "not-a-number"))))
                .isInstanceOf(MalformedMarketIndexPayloadException.class)
                .hasMessageContaining("tradeValue", "not-a-number");
    }

    // ---------------------------------------------------------------------
    // (e2) hasVolume must NOT be an OR across tradeVolume/tradeValue for TWSE
    // ---------------------------------------------------------------------

    /**
     * 最關鍵的一條：tradeVolume 全部有大量非零值，但 tradeValue 全部為 null，
     * hasVolume 仍必須是 false——因為台股子圖畫的是 turnovers（tradeValue），
     * 判斷只能看 tradeValue，不能看 tradeVolume。
     *
     * 若誤植為「volumes 或 turnovers 任一非零即 true」的 OR 邏輯，本測試會得到
     * true 而失敗——這正是 code review 已抓到並修正的 bug。
     */
    @Test
    void hasVolume_mustNotOrAcrossTradeVolumeAndTradeValue_e2() {
        List<Map<String, Object>> rows = List.of(
                twRow("2026-07-01", "100", 14683404939L, null),
                twRow("2026-07-02", "101", 15000000000L, null));

        Map<String, Object> out = result(true, rows);

        assertThat((Boolean) out.get("hasVolume")).isFalse();
    }

    // ---------------------------------------------------------------------
    // (f) turnovers is always a same-length, all-null array for overseas markets
    // ---------------------------------------------------------------------

    @Test
    void turnovers_alwaysNullArraySameLengthAsDatesForOverseas_f() {
        List<Map<String, Object>> rows = List.of(
                overseasRow("2026-07-01", "1000.00", 111_000L),
                overseasRow("2026-07-02", "1010.00", 222_000L),
                overseasRow("2026-07-03", "1020.00", 333_000L));

        Map<String, Object> out = result(false, rows);

        List<String> dates = (List<String>) out.get("dates");
        List<BigDecimal> turnovers = (List<BigDecimal>) out.get("turnovers");

        assertThat(turnovers).hasSize(dates.size());
        assertThat(turnovers).containsOnlyNulls();
    }

    // ---------------------------------------------------------------------
    // (g) volumes/turnovers stay aligned with dates/closes even when rows are skipped
    // ---------------------------------------------------------------------

    @Test
    void arraysStayAlignedWhenAMiddleRowIsSkipped_g() {
        List<Map<String, Object>> rows = List.of(
                twRow("2026-07-01", "100", 1000, 10000),
                twRow("2026-07-02", "101", 1001, 10001),
                twRow("2026-07-03", null, 1002, 10002), // closePoint 缺席 → 應被靜默跳過
                twRow("2026-07-04", "103", 1003, 10003),
                twRow("2026-07-05", "104", 1004, 10004));

        Map<String, Object> out = result(true, rows);

        List<String> dates = (List<String>) out.get("dates");
        List<BigDecimal> closes = (List<BigDecimal>) out.get("closes");
        List<Object> volumes = (List<Object>) out.get("volumes");
        List<BigDecimal> turnovers = (List<BigDecimal>) out.get("turnovers");

        assertThat(dates).hasSize(4);
        assertThat(closes).hasSize(4);
        assertThat(volumes).hasSize(4);
        assertThat(turnovers).hasSize(4);

        // 原始最後一列（2026-07-05）跳過中段那列後，仍應正確落在輸出陣列的最後一個位置
        assertThat(dates.get(3)).isEqualTo("2026-07-05");
        assertThat(closes.get(3)).isEqualByComparingTo(new BigDecimal("104"));
        assertThat(volumes.get(3)).isEqualTo(1004);
        assertThat(turnovers.get(3)).isEqualByComparingTo("10004");
    }

    // ---------------------------------------------------------------------
    // happy path：真實台股資料的正常映射
    // ---------------------------------------------------------------------

    @Test
    void happyPath_realisticTwseRowMapsAllFieldsCorrectly() {
        BigDecimal tradeValue = new BigDecimal("1367817795171");
        List<Map<String, Object>> rows = List.of(
                twRow("2026-07-01", "47018.99", 14683404939L, tradeValue));

        Map<String, Object> out = result(true, rows);

        List<String> dates = (List<String>) out.get("dates");
        List<BigDecimal> closes = (List<BigDecimal>) out.get("closes");
        List<Object> volumes = (List<Object>) out.get("volumes");
        List<BigDecimal> turnovers = (List<BigDecimal>) out.get("turnovers");

        assertThat(dates.get(0)).isEqualTo("2026-07-01");
        assertThat(closes.get(0)).isEqualByComparingTo(new BigDecimal("47018.99"));
        assertThat(volumes.get(0)).isEqualTo(14683404939L);
        assertThat(turnovers.get(0)).isEqualTo(tradeValue);
        assertThat((Boolean) out.get("hasVolume")).isTrue();
    }
}
