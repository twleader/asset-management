package com.steven.assets.service;

import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.model.UsIndexDailyHistory;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import com.steven.assets.repository.UsIndexDailyHistoryRepository;
import com.steven.assets.util.MarketZones;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

/**
 * Task 304：computeAllForTaiex()／computeAllForNasdaq() 的當期／前一期 KD 改走單趟
 * {@code kdSeriesAsc}（taiexKd／nasdaqKd 已刪除）後，兩條入口路徑同源的機械判準——
 * 同一份指數日線 fixture，一邊走 {@code computeAll("0000","台股")} / {@code computeAllForNasdaq()}
 * 的既有映射（{@code toRow}），一邊由本測試直接映射成 {@link StockPriceHistory} 後餵
 * {@code computeFromSeries()}，兩者的 k/d/previousK/previousD 必須逐位相等（{@code assertEquals}
 * 用 {@link BigDecimal#equals}，連 scale 都要一致）。若映射漏抄 high／low 或索引錯位，
 * 兩條路徑會產生不同值，此測試才抓得到——單純斷言「非 null」或「自洽」都測不到這類錯誤。
 */
@ExtendWith(MockitoExtension.class)
class TechnicalIndicatorIndexKdCrossCheckTest {

    @Mock private StockPriceHistoryRepository historyRepo;
    @Mock private PriceQueryService priceQuery;
    @Mock private TwseIndexDailyHistoryRepository twseDailyRepo;
    @Mock private UsIndexDailyHistoryRepository usIndexDailyHistoryRepo;

    private TechnicalIndicatorService service() {
        return new TechnicalIndicatorService(historyRepo, priceQuery, twseDailyRepo, usIndexDailyHistoryRepo);
    }

    /** 鋸齒收盤（i=0 為最新一筆，desc），避免 RSV 貼在 0/100 讓比對失去意義。 */
    private static BigDecimal zigzagClose(double base, int i) {
        return BigDecimal.valueOf(base + (i % 7) * 1.3 - (i % 3) * 0.6);
    }

    @Test
    void computeAllForTaiex的KD與同fixture映射後computeFromSeries逐位相等() {
        // 最新一筆＝今日，避開 computeAllForTaiex() 的今日 live 併入分支，確保兩路徑吃到完全相同的 20 筆。
        LocalDate today = LocalDate.now(MarketZones.TW_ZONE);
        int n = 20;
        List<TwseIndexDailyHistory> desc = new ArrayList<>();
        List<StockPriceHistory> mappedDesc = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            LocalDate date = today.minusDays(i);
            BigDecimal close = zigzagClose(20000, i);
            BigDecimal high = close.add(BigDecimal.valueOf(15));
            BigDecimal low = close.subtract(BigDecimal.valueOf(11));

            TwseIndexDailyHistory h = new TwseIndexDailyHistory();
            h.setTradingDate(date);
            h.setClosePoint(close);
            h.setHighPoint(high);
            h.setLowPoint(low);
            desc.add(h);

            mappedDesc.add(StockPriceHistory.builder()
                    .stockCode("0000").market("台股").tradingDate(date)
                    .closePrice(close).highPrice(high).lowPrice(low)
                    .build());
        }
        when(twseDailyRepo.findTopNByOrderByTradingDateDesc(240)).thenReturn(desc);

        TechnicalIndicatorService svc = service();
        TechnicalIndicatorService.FullIndicators viaComputeAll = svc.computeAll("0000", "台股");
        TechnicalIndicatorService.FullIndicators viaMappedSeries = svc.computeFromSeries(mappedDesc);

        assertNotNull(viaComputeAll.k(), "20 筆足以撐滿 KD 的 9 日視窗，不應為 null");
        assertNotNull(viaComputeAll.previousK());
        assertEquals(viaMappedSeries.k(), viaComputeAll.k());
        assertEquals(viaMappedSeries.d(), viaComputeAll.d());
        assertEquals(viaMappedSeries.previousK(), viaComputeAll.previousK());
        assertEquals(viaMappedSeries.previousD(), viaComputeAll.previousD());
    }

    @Test
    void computeAllForNasdaq的KD與同fixture映射後computeFromSeries逐位相等() {
        // IXIC 路徑不併入即時價（見 computeAllForNasdaq() javadoc），日期可任意固定，不必是今日。
        LocalDate latest = LocalDate.of(2026, 8, 7);
        int n = 20;
        List<UsIndexDailyHistory> desc = new ArrayList<>();
        List<StockPriceHistory> mappedDesc = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            LocalDate date = latest.minusDays(i);
            BigDecimal close = zigzagClose(18000, i);
            BigDecimal high = close.add(BigDecimal.valueOf(20));
            BigDecimal low = close.subtract(BigDecimal.valueOf(17));

            UsIndexDailyHistory h = new UsIndexDailyHistory();
            h.setIndexCode("IXIC");
            h.setTradingDate(date);
            h.setClosePoint(close);
            h.setHighPoint(high);
            h.setLowPoint(low);
            desc.add(h);

            mappedDesc.add(StockPriceHistory.builder()
                    .stockCode("IXIC").market("美股").tradingDate(date)
                    .closePrice(close).highPrice(high).lowPrice(low)
                    .build());
        }
        when(usIndexDailyHistoryRepo.findTopNByIndexCodeOrderByTradingDateDesc("IXIC", 240))
                .thenReturn(desc);

        TechnicalIndicatorService svc = service();
        TechnicalIndicatorService.FullIndicators viaComputeAllForNasdaq = svc.computeAllForNasdaq();
        TechnicalIndicatorService.FullIndicators viaMappedSeries = svc.computeFromSeries(mappedDesc);

        assertNotNull(viaComputeAllForNasdaq.k(), "20 筆足以撐滿 KD 的 9 日視窗，不應為 null");
        assertNotNull(viaComputeAllForNasdaq.previousK());
        assertEquals(viaMappedSeries.k(), viaComputeAllForNasdaq.k());
        assertEquals(viaMappedSeries.d(), viaComputeAllForNasdaq.d());
        assertEquals(viaMappedSeries.previousK(), viaComputeAllForNasdaq.previousK());
        assertEquals(viaMappedSeries.previousD(), viaComputeAllForNasdaq.previousD());
    }
}
