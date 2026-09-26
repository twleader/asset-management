package com.steven.assets.service;

import com.steven.assets.model.StockAlert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Requirement 164 / Task 455：52 週位置純函式與新類型 label（不啟 Spring）。 */
class StockAlertExtendedIndicatorTest {

    private static List<double[]> bars(int n, double high, double low) {
        List<double[]> list = new ArrayList<>();
        for (int i = 0; i < n; i++) list.add(new double[]{high, low});
        return list;
    }

    @Test
    @DisplayName("52 週位置：一般值 = (現價 − 最低) ÷ (最高 − 最低) × 100")
    void week52PositionNormal() {
        assertEquals(50.0, StockAlertService.week52Position(150, bars(240, 200, 100)), 1e-9);
    }

    @Test
    @DisplayName("52 週位置：現價創新高／新低時併入區間 → 100／0")
    void week52PositionIncludesCurrentPrice() {
        assertEquals(100.0, StockAlertService.week52Position(250, bars(240, 200, 100)), 1e-9);
        assertEquals(0.0, StockAlertService.week52Position(50, bars(240, 200, 100)), 1e-9);
    }

    @Test
    @DisplayName("52 週位置：只取最後 240 筆")
    void week52PositionUsesLast240() {
        List<double[]> list = bars(1, 1000, 1);   // 最舊一筆應被排除
        list.addAll(bars(240, 200, 100));
        assertEquals(50.0, StockAlertService.week52Position(150, list), 1e-9);
    }

    @Test
    @DisplayName("52 週位置：最高＝最低、無歷史、不足 240 筆 → null")
    void week52PositionNull() {
        assertNull(StockAlertService.week52Position(100, bars(240, 100, 100)));
        assertNull(StockAlertService.week52Position(100, List.of()));
        assertNull(StockAlertService.week52Position(100, null));
        assertNull(StockAlertService.week52Position(150, bars(239, 200, 100)));
    }

    private static String label(String type, String thr) {
        return StockAlertService.buildLabel(StockAlert.builder()
                .alertType(type).threshold(new BigDecimal(thr)).build());
    }

    @Test
    @DisplayName("新類型 label 逐字照 Requirement 164")
    void labels() {
        assertEquals("RSI5 高於 80", label("RSI5_ABOVE", "80.0000"));
        assertEquals("RSI5 低於 20", label("RSI5_BELOW", "20"));
        assertEquals("BIAS10 高於 5%", label("BIAS10_ABOVE", "5.00"));
        assertEquals("BIAS10 低於 -5%", label("BIAS10_BELOW", "-5"));
        assertEquals("BIAS10 高於 2.5%", label("BIAS10_ABOVE", "2.50"));
        assertEquals("52 週位置高於 90%", label("POS52W_ABOVE", "90"));
        assertEquals("52 週位置低於 10%", label("POS52W_BELOW", "10"));
        assertEquals("W%R9 高於 80", label("WR9_ABOVE", "80"));
        assertEquals("W%R9 低於 20", label("WR9_BELOW", "20"));
        assertEquals("K 值大於 D 值", label("KD_K_GT_D", "0"));
        assertEquals("K 值小於 D 值", label("KD_K_LT_D", "0.0000"));
    }

    @Test
    @DisplayName("新類型皆標記為不做盤中補抓；既有類型不受影響")
    void extendedTypesSkipIntradayBackfill() {
        for (String t : List.of("RSI5_ABOVE", "RSI5_BELOW", "BIAS10_ABOVE", "BIAS10_BELOW", "POS52W_ABOVE",
                "POS52W_BELOW", "WR9_ABOVE", "WR9_BELOW", "KD_K_GT_D", "KD_K_LT_D")) {
            assertEquals(true, StockAlertService.isExtendedIndicatorType(t), t);
        }
        for (String t : List.of("PRICE_ABOVE", "MA_BELOW_PCT", "KD_ABOVE", "KD_D_BELOW")) {
            assertEquals(false, StockAlertService.isExtendedIndicatorType(t), t);
        }
    }
}
