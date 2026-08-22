package com.steven.assets.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Task 356.11e：{@code TradingRadarSnapshotStore} 無自訂 schema，快照就是這組 record 的
 * Jackson 投影，故新欄位是否保存、舊快照是否還讀得回來，只能以 DTO round-trip 證明。
 *
 * <p><b>缺值的 {@code List} 欄位會是 {@code null}，不是 {@code List.of()}。</b>
 * {@code StockDecision} 是 record 且<b>沒有</b> compact constructor 做正規化，Jackson 對缺欄一律
 * 給 {@code null}。前端、匯出與 {@code TradingRadarExportService} 必須自行處理 {@code null}；
 * 本任務刻意<b>不</b>加 compact constructor（那會連帶改動既有欄位的既有行為），
 * 只要求消費端容忍。這條測試就是把該事實釘住，避免日後有人以為它是空清單。</p>
 */
class TradingRadarSnapshotRoundTripTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** Task 356 之後的 11 個新 component；舊快照一個都沒有。 */
    private static final List<String> NEW_STOCK_FIELDS = List.of(
            "swingAction", "swingActionLabel", "swingScore", "swingReasons", "swingRisks",
            "swingDownsideRisk", "swingEvidenceConfidence", "swingRiskCoverage",
            "swingCandidateAction", "dailyCandle", "weeklyIndicators");

    private static TradingRadarDto.DailyCandle dailyCandle() {
        return new TradingRadarDto.DailyCandle(
                new BigDecimal("100.00"), new BigDecimal("110.00"),
                new BigDecimal("90.00"), new BigDecimal("105.00"),
                new BigDecimal("0.75000000"), new BigDecimal("1"), new BigDecimal("0.50000000"),
                "2026-08-21");
    }

    private static TradingRadarDto.WeeklyIndicators weeklyIndicators() {
        return new TradingRadarDto.WeeklyIndicators(
                "2026-08-14", 72,
                new BigDecimal("98.00"), new BigDecimal("112.00"), new BigDecimal("94.00"),
                new BigDecimal("108.00"), 12_345_678L,
                new BigDecimal("104.10"), new BigDecimal("102.20"), new BigDecimal("100.30"),
                new BigDecimal("62.10"), new BigDecimal("58.40"), new BigDecimal("69.50"),
                new BigDecimal("1.20"), new BigDecimal("0.80"), new BigDecimal("0.40"),
                new BigDecimal("64.00"), new BigDecimal("59.00"),
                new BigDecimal("5.60"), new BigDecimal("7.70"),
                new BigDecimal("1.35"), new BigDecimal("2.40"),
                new BigDecimal("0.77777778"), new BigDecimal("1"));
    }

    private static TradingRadarDto.StockDecision decision() {
        return new TradingRadarDto.StockDecision(
                "2330", "台積電", "台股", "股票", true, true,
                "HOLD", "續抱", 58, "NONE", "無", List.of(), List.of(), true,
                new BigDecimal("1100.00"), new BigDecimal("0.45"), "VERIFIED_CLOSE",
                "2026-08-21T13:30:00", "2026-08-21",
                new BigDecimal("1080.00"), new BigDecimal("1050.00"), new BigDecimal("980.00"),
                new BigDecimal("55.00"), new BigDecimal("50.00"), "ABOVE", "ABOVE", "ABOVE",
                null, "TWD", List.of("最新價位於日線 MA5 之上。"), List.of("風險一"),
                "NORMAL", "NEUTRAL", "中性",
                new BigDecimal("4.76"), new BigDecimal("0.82"), new BigDecimal("1090.00"),
                null, null, null,
                "WATCH", "觀察", 71, List.of("短期理由"), List.of("短期風險"), true,
                new BigDecimal("1.30"), null, false, null, TradingRadarDto.RadarEvidence.EMPTY,
                31, 42, 77, 68, 0.9, 0.8, "HOLD", "WATCH", List.of("閘門原因"),
                null, null,
                // Task 356 的 11 欄
                "TRIAL_BUY", "分批試單", 65, List.of("波段理由"), List.of("波段風險"),
                37, 73, 0.85, "TRIAL_BUY", dailyCandle(), weeklyIndicators());
    }

    @Test
    @DisplayName("356.11e：11 個新欄位經 Jackson round-trip 後逐欄保存")
    void newFieldsSurviveASnapshotRoundTrip() throws Exception {
        TradingRadarDto.StockDecision original = decision();

        String json = mapper.writeValueAsString(original);
        TradingRadarDto.StockDecision restored =
                mapper.readValue(json, TradingRadarDto.StockDecision.class);

        assertThat(restored.swingAction()).isEqualTo("TRIAL_BUY");
        assertThat(restored.swingActionLabel()).isEqualTo("分批試單");
        assertThat(restored.swingScore()).isEqualTo(65);
        assertThat(restored.swingReasons()).containsExactly("波段理由");
        assertThat(restored.swingRisks()).containsExactly("波段風險");
        assertThat(restored.swingDownsideRisk()).isEqualTo(37);
        assertThat(restored.swingEvidenceConfidence()).isEqualTo(73);
        assertThat(restored.swingRiskCoverage()).isEqualTo(0.85);
        assertThat(restored.swingCandidateAction()).isEqualTo("TRIAL_BUY");

        assertThat(restored.dailyCandle()).isEqualTo(original.dailyCandle());
        assertThat(restored.weeklyIndicators()).isEqualTo(original.weeklyIndicators());
        // 既有兩軌一併驗一次：新增欄位不得動到它們。
        assertThat(restored.shortScore()).isEqualTo(71);
        assertThat(restored.score()).isEqualTo(58);
    }

    @Test
    @DisplayName("356.11e：大盤第 31 個 component weeklyIndicators 同樣往返保存")
    void marketSummaryWeeklyIndicatorsSurviveARoundTrip() throws Exception {
        TradingRadarDto.MarketSummary original = new TradingRadarDto.MarketSummary(
                "NEUTRAL", "中性／等待確認", 52, true, false, "2026-08-21",
                new BigDecimal("22000.00"), new BigDecimal("-0.20"), "VERIFIED_CLOSE",
                null, null, null, null, null, null, "ABOVE", "ABOVE",
                List.of(), List.of(), false, null, null,
                null, null, null, null, null, null, null, false,
                weeklyIndicators());

        TradingRadarDto.MarketSummary restored = mapper.readValue(
                mapper.writeValueAsString(original), TradingRadarDto.MarketSummary.class);

        assertThat(restored.weeklyIndicators()).isEqualTo(original.weeklyIndicators());
    }

    @Test
    @DisplayName("356.11e：舊快照缺 11 個新欄位時反序列化為 null，且不得讀檔失敗")
    void legacySnapshotWithoutTheNewFieldsStillDeserialises() throws Exception {
        ObjectNode legacy = (ObjectNode) mapper.readTree(mapper.writeValueAsString(decision()));
        NEW_STOCK_FIELDS.forEach(legacy::remove);
        assertThat(legacy.has("swingScore")).as("前提：欄位真的被移除了").isFalse();
        String legacyJson = mapper.writeValueAsString(legacy);

        assertThatCode(() -> mapper.readValue(legacyJson, TradingRadarDto.StockDecision.class))
                .doesNotThrowAnyException();
        TradingRadarDto.StockDecision restored =
                mapper.readValue(legacyJson, TradingRadarDto.StockDecision.class);

        assertThat(restored.swingAction()).isNull();
        assertThat(restored.swingScore()).isNull();
        assertThat(restored.swingRiskCoverage()).isNull();
        assertThat(restored.dailyCandle()).isNull();
        assertThat(restored.weeklyIndicators()).isNull();
        // 這兩條是消費端最容易踩的：record 沒有 compact constructor，缺值的 List 是 null，
        // 不是空清單。匯出與前端必須容忍 null（Task 356.11e 的明文要求）。
        assertThat(restored.swingReasons()).as("缺值的 List 欄位是 null 而非 List.of()").isNull();
        assertThat(restored.swingRisks()).isNull();
        // 既有兩軌照常讀回，舊快照不得因為新欄位而整筆作廢。
        assertThat(restored.shortScore()).isEqualTo(71);
        assertThat(restored.score()).isEqualTo(58);
        assertThat(restored.horizonConflict()).isTrue();
    }

    @Test
    @DisplayName("356.11e：舊大盤快照缺 weeklyIndicators 時同樣讀得回來")
    void legacyMarketSummaryWithoutWeeklyIndicatorsStillDeserialises() throws Exception {
        ObjectNode legacy = (ObjectNode) mapper.readTree(mapper.writeValueAsString(
                new TradingRadarDto.MarketSummary(
                        "NEUTRAL", "中性／等待確認", 52, true, false, "2026-08-21",
                        new BigDecimal("22000.00"), new BigDecimal("-0.20"), "VERIFIED_CLOSE",
                        null, null, null, null, null, null, "ABOVE", "ABOVE",
                        List.of(), List.of(), false, null, null,
                        null, null, null, null, null, null, null, false,
                        weeklyIndicators())));
        legacy.remove("weeklyIndicators");

        TradingRadarDto.MarketSummary restored = mapper.readValue(
                mapper.writeValueAsString(legacy), TradingRadarDto.MarketSummary.class);

        assertThat(restored.weeklyIndicators()).isNull();
        assertThat(restored.dataComplete()).isTrue();
    }
}
