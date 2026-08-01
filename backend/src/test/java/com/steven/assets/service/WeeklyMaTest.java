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
 * <p>本檔的第三支測試是<b>決策的釘子</b>而非行為驗證：使用者要求加週線，但同時要求
 * 「獲利期間是數周至兩年，不是極短線」，故 MA5 刻意<b>只顯示、不參與評分</b>。</p>
 */
class WeeklyMaTest {

    private final TechnicalIndicatorService service = new TechnicalIndicatorService(null, null, null);

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
    void weeklyMaMustNotBeAnInputToTheRuleEngine() {
        for (RecordComponent c : TradingRadarRuleEngine.StockInput.class.getRecordComponents()) {
            String n = c.getName().toLowerCase();
            assertTrue(!n.contains("ma5") && !n.contains("weekly"),
                    "MA5 是 5 個交易日的尺度，納入評分會與「數周至兩年」的持有期需求衝突；"
                            + "若要改變此決策須另走 SDD 循環。實際發現的欄位：" + c.getName());
        }
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
