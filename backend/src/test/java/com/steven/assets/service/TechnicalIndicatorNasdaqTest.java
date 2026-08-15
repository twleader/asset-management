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
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * TechnicalIndicatorService.computeAllForNasdaq()（Task 294.1）：IXIC 大盤情境的 MA／KD 計算。
 *
 * <p>原本只驗證「序列不足以撐滿視窗時對應欄位回 null、不擲例外」這一條（驗證段落 (b)），
 * 並附帶確認本方法刻意<b>不併入即時價</b>——與 {@code computeAllForTaiex()} 不同，IXIC 沒有
 * 對應的 Redis 即時報價來源（背景段落）。</p>
 *
 * <p>Task 336 起另加兩條，鎖住 {@code nasdaqSimpleMa} 的 BigDecimal 精確路徑：一條是
 * 2018-07-17 的真實邊界迴歸案例（double 累加會與 BFF／匯出那兩份精確路徑差 0.01），一條是
 * MA5／20／60／240 四視窗與獨立精確參考實作的等值性質測試。</p>
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

    /** 由逐字給定的收盤價（字串，保留原始 scale）建 desc 列；closes[0] 為最新一筆。 */
    private List<UsIndexDailyHistory> descRowsOf(LocalDate latest, String... closes) {
        List<UsIndexDailyHistory> rows = new ArrayList<>();
        for (int i = 0; i < closes.length; i++) {
            UsIndexDailyHistory h = new UsIndexDailyHistory();
            h.setIndexCode("IXIC");
            h.setTradingDate(latest.minusDays(i));
            BigDecimal close = new BigDecimal(closes[i]);
            h.setClosePoint(close);
            h.setHighPoint(close.add(BigDecimal.ONE));
            h.setLowPoint(close.subtract(BigDecimal.ONE));
            rows.add(h);
        }
        return rows;
    }

    /**
     * 決定性的 4 位小數收盤序列（比照 {@code us_index_daily_history.close_point} 的
     * {@code numeric(14,4)}——實測 2559 筆 IXIC 日線有 87.8% 帶滿 4 位小數，非名目位數）；
     * i=0 為最新一筆。
     */
    private List<UsIndexDailyHistory> descRows4dp(int count, LocalDate latest) {
        List<UsIndexDailyHistory> rows = new ArrayList<>();
        long seed = 20180717L;
        for (int i = 0; i < count; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            long wiggle = Math.floorMod(seed >> 17, 5_000_000L);      // 0.0000 ~ 499.9999
            BigDecimal close = BigDecimal.valueOf(75_000_000L + wiggle, 4);  // 7500.0000 ~ 7999.9999
            UsIndexDailyHistory h = new UsIndexDailyHistory();
            h.setIndexCode("IXIC");
            h.setTradingDate(latest.minusDays(i));
            h.setClosePoint(close);
            h.setHighPoint(close.add(BigDecimal.ONE));
            h.setLowPoint(close.subtract(BigDecimal.ONE));
            rows.add(h);
        }
        return rows;
    }

    /**
     * 獨立的精確參考實作，與 {@code MarketIndexChartService.movingAverage}／
     * {@code ExcelExportService.indexMaAt} 同式：BigDecimal 精確加總，只在最後捨入一次。
     */
    private static BigDecimal exactMa(List<UsIndexDailyHistory> desc, int window) {
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < window; i++) sum = sum.add(desc.get(i).getClosePoint());
        return sum.divide(BigDecimal.valueOf(window), 2, RoundingMode.HALF_UP);
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

    /**
     * Task 336 (a)：2018-07-17 的真實邊界迴歸案例。
     *
     * <p>這 20 筆是 {@code us_index_daily_history} 中
     * {@code index_code='IXIC' AND trading_date <= '2018-07-17'} 的最近 20 筆，
     * 精確和 {@code 153353.5000}、精確商 {@code 7667.675}，HALF_UP 後為 <b>7667.68</b>。
     * 舊的 double 累加（新→舊）得到 {@code 153353.49999999997} → {@code 7667.674999999998}
     * → 向下捨入為 {@code 7667.67}，與 BFF／匯出那兩份精確路徑差 0.01。</p>
     *
     * <p><b>順序與數值都不可替換。</b>紅燈的必要條件不是「精確和等於 153353.5」，而是
     * 「這 20 個具體值、以新→舊順序做 double 累加後嚴格小於精確和」——同一批反轉成舊→新會得到
     * {@code 153353.50000000003}（恆綠），任何構造出來的等值序列（如 {@code [7667.675]×20}）亦然。</p>
     *
     * <p>斷言一律用 {@code compareTo}：{@code BigDecimal.equals} 連 scale 都比，
     * {@code 7667.68} 與 {@code 7667.6800} 會判不相等而產生假紅燈。</p>
     */
    @Test
    void MA20在精確商恰為x_xx5時必須向上捨入而非double累加的向下捨入() {
        // 2018-07-17 起往回 20 個交易日的 IXIC 真實收盤，新→舊（mock 的 findTopN... 本就回 desc）。
        List<UsIndexDailyHistory> desc = descRowsOf(LocalDate.of(2018, 7, 17),
                "7855.1201", "7805.7202", "7825.9800", "7823.9199", "7716.6099",
                "7759.2002", "7756.2002", "7688.3901", "7586.4302", "7502.6699",
                "7567.6899", "7510.2998", "7503.6802", "7445.0801", "7561.6299",
                "7532.0098", "7692.8198", "7712.9502", "7781.5098", "7725.5898");
        when(usIndexDailyHistoryRepo.findTopNByIndexCodeOrderByTradingDateDesc("IXIC", 240))
                .thenReturn(desc);

        TechnicalIndicatorService.FullIndicators result = service().computeAllForNasdaq();

        BigDecimal expected = new BigDecimal("7667.68");
        assertEquals(0, expected.compareTo(result.monthlyMa()),
                "精確和 153353.5000 / 20 = 7667.675，HALF_UP 須為 7667.68；"
                        + "收到 7667.67 表示仍走 double 累加（Task 336）。實際值＝" + result.monthlyMa());
        // 同一組輸入下，精確參考實作必須逐位吻合。
        assertEquals(0, exactMa(desc, 20).compareTo(result.monthlyMa()));
    }

    /**
     * Task 336 (b)：MA5／20／60／240 四個視窗都必須與獨立的精確參考實作逐位相同。
     *
     * <p>{@code FullIndicators} 的欄位名與視窗對應為
     * {@code weeklyMa}=MA5／{@code monthlyMa}=MA20／{@code quarterlyMa}=MA60／{@code annualMa}=MA240。</p>
     */
    @Test
    void 四個視窗的MA皆與獨立精確參考實作逐位相同() {
        List<UsIndexDailyHistory> desc = descRows4dp(240, LocalDate.of(2026, 8, 14));
        when(usIndexDailyHistoryRepo.findTopNByIndexCodeOrderByTradingDateDesc("IXIC", 240))
                .thenReturn(desc);

        TechnicalIndicatorService.FullIndicators result = service().computeAllForNasdaq();

        assertEquals(0, exactMa(desc, 5).compareTo(result.weeklyMa()),
                "MA5 與精確路徑不符：精確＝" + exactMa(desc, 5) + "，實際＝" + result.weeklyMa());
        assertEquals(0, exactMa(desc, 20).compareTo(result.monthlyMa()),
                "MA20 與精確路徑不符：精確＝" + exactMa(desc, 20) + "，實際＝" + result.monthlyMa());
        assertEquals(0, exactMa(desc, 60).compareTo(result.quarterlyMa()),
                "MA60 與精確路徑不符：精確＝" + exactMa(desc, 60) + "，實際＝" + result.quarterlyMa());
        assertEquals(0, exactMa(desc, 240).compareTo(result.annualMa()),
                "MA240 與精確路徑不符：精確＝" + exactMa(desc, 240) + "，實際＝" + result.annualMa());
    }
}
