package com.steven.assets.service;

import com.steven.assets.model.UsIndexDailyHistory;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import com.steven.assets.repository.UsIndexDailyHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * TechnicalIndicatorService.computeAllForNasdaq()（Task 294.1）：IXIC 大盤情境的 MA／KD 計算。
 *
 * <p>本檔只驗證「序列不足以撐滿視窗時對應欄位回 null、不擲例外」這一條（驗證段落 (b)），
 * 並附帶確認本方法刻意<b>不併入即時價</b>——與 {@code computeAllForTaiex()} 不同，IXIC 沒有
 * 對應的 Redis 即時報價來源（背景段落）。</p>
 */
@ExtendWith(MockitoExtension.class)
class TechnicalIndicatorNasdaqTest {

    @Mock private StockPriceHistoryRepository historyRepo;
    @Mock private PriceQueryService priceQuery;
    @Mock private TwseIndexDailyHistoryRepository twseDailyRepo;
    @Mock private UsIndexDailyHistoryRepository usIndexDailyHistoryRepo;

    private TechnicalIndicatorService service() {
        return new TechnicalIndicatorService(historyRepo, priceQuery, twseDailyRepo, usIndexDailyHistoryRepo);
    }

    /** 240 筆上限「由新到舊」IXIC 收盤：closePoint = base+i，i=0 為最新一筆。 */
    private List<UsIndexDailyHistory> descRows(int count, LocalDate latest, double base) {
        List<UsIndexDailyHistory> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            UsIndexDailyHistory h = new UsIndexDailyHistory();
            h.setIndexCode("IXIC");
            h.setTradingDate(latest.minusDays(i));
            BigDecimal close = BigDecimal.valueOf(base + i);
            h.setClosePoint(close);
            h.setHighPoint(close.add(BigDecimal.ONE));
            h.setLowPoint(close.subtract(BigDecimal.ONE));
            rows.add(h);
        }
        return rows;
    }

    @Test
    void 序列全空時回EMPTY而非擲例外() {
        when(usIndexDailyHistoryRepo.findTopNByIndexCodeOrderByTradingDateDesc("IXIC", 240))
                .thenReturn(List.of());

        TechnicalIndicatorService.FullIndicators result = service().computeAllForNasdaq();

        assertNull(result.monthlyMa());
        assertNull(result.quarterlyMa());
        assertNull(result.annualMa());
        assertNull(result.k());
        assertNull(result.d());
    }

    @Test
    void 序列不足60筆時季線與MA60確認回null但月線與KD正常算出() {
        LocalDate latest = LocalDate.of(2026, 8, 7);
        // 30 筆：撐得住 MA5／MA20／KD（需 >= 9），撐不住 MA60／MA240。
        when(usIndexDailyHistoryRepo.findTopNByIndexCodeOrderByTradingDateDesc("IXIC", 240))
                .thenReturn(descRows(30, latest, 18000));

        TechnicalIndicatorService.FullIndicators result = service().computeAllForNasdaq();

        assertNotNull(result.monthlyMa(), "30 筆足以撐滿 MA20（映射為 monthlyMa 欄位）");
        assertNull(result.quarterlyMa(), "30 筆不足以撐滿 MA60（映射為 quarterlyMa 欄位），須回 null 而非擲例外");
        assertNull(result.annualMa(), "30 筆不足以撐滿 MA240（映射為 annualMa 欄位），須回 null");
        assertNotNull(result.k(), "30 筆足以撐滿 KD 視窗（9 筆），當期 KD 應正常算出");
        assertNotNull(result.d());
        assertNotNull(result.previousK());
        assertNotNull(result.previousD());
    }

    @Test
    void 序列不足9筆時KD亦回null而非擲例外() {
        LocalDate latest = LocalDate.of(2026, 8, 7);
        // 5 筆：撐得住週線 MA5，但撐不住 KD（需 >= 9）與 MA20/60/240。
        when(usIndexDailyHistoryRepo.findTopNByIndexCodeOrderByTradingDateDesc("IXIC", 240))
                .thenReturn(descRows(5, latest, 18000));

        TechnicalIndicatorService.FullIndicators result = service().computeAllForNasdaq();

        assertNotNull(result.weeklyMa(), "5 筆足以撐滿週線 MA5");
        assertNull(result.monthlyMa());
        assertNull(result.quarterlyMa());
        assertNull(result.annualMa());
        assertNull(result.k(), "5 筆不足以撐滿 KD 的 9 日視窗，須回 null 而非擲例外");
        assertNull(result.d());
    }

    @Test
    void 序列充足時MA240與KD皆正常算出且完全不查詢即時價() {
        LocalDate latest = LocalDate.of(2026, 8, 7);
        when(usIndexDailyHistoryRepo.findTopNByIndexCodeOrderByTradingDateDesc("IXIC", 240))
                .thenReturn(descRows(240, latest, 18000));

        TechnicalIndicatorService.FullIndicators result = service().computeAllForNasdaq();

        assertNotNull(result.monthlyMa());
        assertNotNull(result.quarterlyMa());
        assertNotNull(result.annualMa());
        assertNotNull(result.k());
        assertNotNull(result.d());

        // 背景段落：computeAllForNasdaq() 刻意不併入即時價（IXIC 無對應 Redis 即時報價來源）。
        verifyNoInteractions(priceQuery);
    }

    @Test
    void 呼叫的是IndexCodeIXIC而非個股股票代碼查詢() {
        when(usIndexDailyHistoryRepo.findTopNByIndexCodeOrderByTradingDateDesc(anyString(), anyInt()))
                .thenReturn(List.of());

        service().computeAllForNasdaq();

        org.mockito.Mockito.verify(usIndexDailyHistoryRepo)
                .findTopNByIndexCodeOrderByTradingDateDesc("IXIC", 240);
        verifyNoInteractions(historyRepo);
        verifyNoInteractions(twseDailyRepo);
    }
}
