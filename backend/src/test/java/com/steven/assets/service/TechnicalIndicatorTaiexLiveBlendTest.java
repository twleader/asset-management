package com.steven.assets.service;

import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TechnicalIndicatorService.computeAll("0000","台股") 對大盤即時點位的融合邏輯（Task 228，
 * Requirement 43 修訂 V6）：完成日序列未到今日時比照一般個股既有作法暫加一筆 Redis 即時價。
 */
@ExtendWith(MockitoExtension.class)
class TechnicalIndicatorTaiexLiveBlendTest {

    @Mock private StockPriceHistoryRepository historyRepo;
    @Mock private PriceQueryService priceQuery;
    @Mock private TwseIndexDailyHistoryRepository twseDailyRepo;

    private TechnicalIndicatorService service() {
        return new TechnicalIndicatorService(historyRepo, priceQuery, twseDailyRepo);
    }

    /** 240 筆「由新到舊」完成日收盤：closePoint = base+i，i=0 為最新一筆。 */
    private List<TwseIndexDailyHistory> descRows(LocalDate latest, int base) {
        List<TwseIndexDailyHistory> rows = new ArrayList<>();
        for (int i = 0; i < 240; i++) {
            TwseIndexDailyHistory h = new TwseIndexDailyHistory();
            h.setTradingDate(latest.minusDays(i));
            h.setClosePoint(BigDecimal.valueOf(base + i));
            rows.add(h);
        }
        return rows;
    }

    private PriceQueryService.LivePrice liveOn(LocalDate tradingDate, BigDecimal price) {
        return new PriceQueryService.LivePrice(
                "0000", "台股大盤", "台股", price, null, null, null,
                null, null, null, null, null, null,
                tradingDate.toString(), "2026-07-20T10:30:00", false, "TWSE指數(5m)");
    }

    @Test
    void completedKNotToday_liveFreshToday_blendsIntoMa20() {
        LocalDate today = LocalDate.now();
        List<TwseIndexDailyHistory> rows = descRows(today.minusDays(1), 100);
        when(twseDailyRepo.findTopNByOrderByTradingDateDesc(240)).thenReturn(rows);
        when(priceQuery.getLive("0000", "台股")).thenReturn(Optional.of(liveOn(today, BigDecimal.valueOf(500))));

        TechnicalIndicatorService.FullIndicators ind = service().computeAll("0000", "台股");

        // MA20 = (live 500 + 前 19 筆完成日收盤 100..118) 的平均，與 taiexSimpleMa 相同的 scale(2, HALF_UP)。
        double sum = 500.0;
        for (int i = 0; i < 19; i++) sum += 100 + i;
        BigDecimal expectedMa20 = BigDecimal.valueOf(sum / 20).setScale(2, RoundingMode.HALF_UP);
        assertEquals(expectedMa20, ind.monthlyMa());
    }

    @Test
    void completedKAlreadyToday_doesNotDoubleCountLive() {
        LocalDate today = LocalDate.now();
        List<TwseIndexDailyHistory> rows = descRows(today, 100);
        when(twseDailyRepo.findTopNByOrderByTradingDateDesc(240)).thenReturn(rows);

        TechnicalIndicatorService.FullIndicators ind = service().computeAll("0000", "台股");

        double sum = 0;
        for (int i = 0; i < 20; i++) sum += 100 + i;
        BigDecimal expectedMa20 = BigDecimal.valueOf(sum / 20).setScale(2, RoundingMode.HALF_UP);
        assertEquals(expectedMa20, ind.monthlyMa());
        verify(priceQuery, never()).getLive("0000", "台股");
    }

    @Test
    void completedKNotToday_liveMissing_fallsBackToPureCompletedSeries() {
        LocalDate today = LocalDate.now();
        List<TwseIndexDailyHistory> rows = descRows(today.minusDays(1), 100);
        when(twseDailyRepo.findTopNByOrderByTradingDateDesc(240)).thenReturn(rows);
        when(priceQuery.getLive("0000", "台股")).thenReturn(Optional.empty());

        TechnicalIndicatorService.FullIndicators ind = service().computeAll("0000", "台股");

        double sum = 0;
        for (int i = 0; i < 20; i++) sum += 100 + i;
        BigDecimal expectedMa20 = BigDecimal.valueOf(sum / 20).setScale(2, RoundingMode.HALF_UP);
        assertEquals(expectedMa20, ind.monthlyMa());
    }
}
