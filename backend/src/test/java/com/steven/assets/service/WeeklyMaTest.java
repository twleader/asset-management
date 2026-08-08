package com.steven.assets.service;

import com.steven.assets.model.StockPriceHistory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 週線 MA5（Task 265）。
 *
 * <p>Task 291 起，MA5 是短期約一週軌的重要輸入，中期軌亦以較低權重採計。</p>
 */
class WeeklyMaTest {

    // 第 4 參數為 Task 294 新增的 usIndexDailyHistoryRepo；本測試只涉及個股序列，傳 null 即可。
    private final TechnicalIndicatorService service = new TechnicalIndicatorService(null, null, null, null);

    @Test
    void weeklyMaIsNullWhenFewerThanFiveRows() {
        assertNull(service.computeFromSeries(descSeries(4)).weeklyMa(),
                "不足 5 根須回 null，不得以現有筆數平均充數");
    }

    @Test
    void weeklyMaEqualsArithmeticMeanOfLatestFiveClosesOfTheGivenSeries() {
        // 傳入序列由呼叫端決定價基（交易雷達餵還原序列、觀察清單餵原始序列），
        // 故斷言只針對「傳入的這一份」，不得跨路徑宣稱同值。
        List<StockPriceHistory> series = descSeries(10);
        BigDecimal expected = BigDecimal.ZERO;
        for (int i = 0; i < 5; i++) expected = expected.add(series.get(i).getClosePrice());
        expected = expected.divide(BigDecimal.valueOf(5), 2, RoundingMode.HALF_UP);

        assertEquals(0, expected.compareTo(service.computeFromSeries(series).weeklyMa()));
    }

    @Test
    void weeklyMaIsAnInputToTheV11RuleEngine() {
        assertTrue(List.of(TradingRadarRuleEngine.StockInput.class.getRecordComponents()).stream()
                        .map(RecordComponent::getName).anyMatch("weeklyMa"::equals),
                "V11 短期軌必須接入 weeklyMa，不得只停留在畫面揭露");
    }

    /** 由新到舊、收盤遞減的序列（closes[0] 最新）。 */
    private List<StockPriceHistory> descSeries(int size) {
        List<StockPriceHistory> rows = new ArrayList<>();
        LocalDate day = LocalDate.of(2026, 8, 1);
        for (int i = 0; i < size; i++) {
            rows.add(StockPriceHistory.builder()
                    .stockCode("2330").market("台股")
                    .tradingDate(day.minusDays(i))
                    .openPrice(BigDecimal.valueOf(100 - i))
                    .highPrice(BigDecimal.valueOf(101 - i))
                    .lowPrice(BigDecimal.valueOf(99 - i))
                    .closePrice(BigDecimal.valueOf(100 - i))
                    .build());
        }
        return rows;
    }
}
