package com.steven.assets.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 320 驗證 (e)：即時折溢價<b>不得進評分</b>的反射釘子。
 *
 * <p>「不進規則」是本任務最容易被日後好意破壞的一條——欄位既然已經算出來了，
 * 把它接進 {@code buyGate} 或某個因子看起來只是順手。但那會把<b>未完成 session 的 NAV</b>
 * 帶進決策，且盤中每 5 分鐘就讓同一決策日的 veto 結果漂移一次
 * （dated 的 {@code etfPremiumPct} 之所以刻意排除當日，理由正是如此）。
 *
 * <p>反射掃 record component 名而不是 grep：規則引擎的輸入形狀是編譯期契約，
 * 任何人把新欄位接進去都必須先在這兩個 record 上開欄，這條就會紅。
 */
class TradingRadarLivePremiumRuleIsolationTest {

    private static List<String> componentNames(Class<?> recordClass) {
        List<String> names = new ArrayList<>();
        for (RecordComponent c : recordClass.getRecordComponents()) names.add(c.getName());
        return names;
    }

    @Test
    @DisplayName("(e) StockInput／MarketInput 不得出現 etfPremiumLive 字樣的因子")
    void 即時折溢價不得進規則引擎輸入() {
        List<String> stockInput = componentNames(TradingRadarRuleEngine.StockInput.class);
        List<String> marketInput = componentNames(TradingRadarRuleEngine.MarketInput.class);

        assertThat(stockInput)
                .as("即時折溢價是純揭露欄，不得成為 StockInput 的因子")
                .noneMatch(n -> n.toLowerCase(Locale.ROOT).contains("etfpremiumlive"));
        assertThat(marketInput)
                .as("即時折溢價是個股欄，更不該出現在大盤輸入")
                .noneMatch(n -> n.toLowerCase(Locale.ROOT).contains("etfpremiumlive"));
    }

    @Test
    @DisplayName("(e) dated 的 etfPremiumPct／etfPremiumPercentile 接線維持不變")
    void dated折溢價的既有接線不變() {
        assertThat(componentNames(TradingRadarRuleEngine.StockInput.class))
                .as("Task 320 不得動到 dated 折溢價進規則的既有接線")
                .contains("etfPremiumPct", "etfPremiumPercentile");
    }

    @Test
    @DisplayName("(e) DTO 的新欄位一律附加在 StockDecision 之後，既有 component 順序不變")
    void 新欄位附加在DTO最末() {
        List<String> components =
                componentNames(com.steven.assets.dto.TradingRadarDto.StockDecision.class);

        // Task 356.11b/408/438：62 → 75（既有十二欄後再附加 completed-local bollinger）。
        assertThat(components).hasSize(75);
        // Task 320 的兩欄仍緊接 actionGateReasons，且相對順序未變——
        // 中間插入會讓既有 positional 呼叫端一起位移。
        assertThat(components.subList(59, 62))
                .as("Task 320 的兩欄仍在既有 62 欄的最末")
                .containsExactly("actionGateReasons", "etfPremiumLivePct", "etfPremiumLiveNavAsOf");
        // Task 356 的 11 欄與 Task408 technicalResolution 都一律追加在既有 62 個之後；
        // Task 438 bollinger 再接在 technicalResolution 之後，舊有 positional constructor 不得被中途位移。
        assertThat(components.subList(62, components.size()))
                .containsExactly("swingAction", "swingActionLabel", "swingScore",
                        "swingReasons", "swingRisks", "swingDownsideRisk",
                        "swingEvidenceConfidence", "swingRiskCoverage", "swingCandidateAction",
                        "dailyCandle", "weeklyIndicators", "technicalResolution", "bollinger");
    }
}
