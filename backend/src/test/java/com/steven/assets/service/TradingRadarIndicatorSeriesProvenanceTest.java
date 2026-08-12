package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.EtfNavHistoryRepository;
import com.steven.assets.repository.StockAlertRepository;
import com.steven.assets.repository.StockDividendHistoryRepository;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import com.steven.assets.repository.UsIndexDailyHistoryRepository;
import com.steven.assets.security.CurrentUserContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Task 319 服務層迴歸：{@code close_source} provenance 白名單只界定 verified 收盤，
 * <b>不得</b>用來裁掉餵給 {@link RadarInputAssembler} 的技術序列。
 *
 * <p>修正前的線上症狀是台股分頁 21 檔全數「今日不交易」：Task 290 引入 {@code close_source} 時
 * 明定既有列一律維持 null、禁止 migration 猜來源，而 {@code RadarObservationResolver} 又拿同一份
 * 白名單過濾整條歷史序列，於是每檔只剩最近幾個對帳過的交易日進得了 MA240，指標全 null 後
 * 規則引擎落入資料不足分支。</p>
 *
 * <p><b>本檔刻意用真的 {@link TechnicalIndicatorService#computeFromSeries} 與真的
 * {@link DistributionAdjustedPriceService}</b>（只有大盤那組 {@code computeAll} 走 mock）：
 * 指標若改成 mock 回傳固定值，序列被裁到剩 4 根時 MA240 照樣非 null，本迴歸就驗不到東西。</p>
 *
 * <p><b>{@code findRecentN} 在此是 mock，319.4 的「取數 250」一根都不生效</b>——序列長度完全由
 * stub 回傳的 list 決定，故每個 fixture 的列數都必須自己備足剔除餘裕（見各測試註解），
 * 否則剔除後只剩 240 根、{@code ma240Confirmation} 回 {@code UNAVAILABLE}，
 * 測試會以「資料不足」這個與本任務無關的誤導方向紅燈。</p>
 */
@ExtendWith(MockitoExtension.class)
class TradingRadarIndicatorSeriesProvenanceTest {

    private static final String CODE = "2330";
    private static final String TW = "台股";
    private static final String INSUFFICIENT_DATA_RISK =
            "個股必要的 MA20／60／240、KD、241 根完成日 K 或大盤資料不足，今日不交易。";

    /** 2026-08-12（三）。 */
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 12);
    /** 台北 14:00，台股收盤（13:30）之後 → {@code completedSession} 就是當日。 */
    private static final Instant AFTER_CLOSE = Instant.parse("2026-08-12T06:00:00Z");
    /** 台北 11:00，盤中 → {@code completedSession} 退到前一交易日 2026-08-11。 */
    private static final Instant INTRADAY = Instant.parse("2026-08-12T03:00:00Z");

    @Mock private TechnicalIndicatorService marketIndicatorService;
    @Mock private AssetClassifier assetClassifier;
    @Mock private TwseIndexDailyHistoryRepository twseRepo;
    @Mock private UsIndexDailyHistoryRepository usIndexDailyHistoryRepo;
    @Mock private StockPriceHistoryRepository priceHistoryRepo;
    @Mock private StockDividendHistoryRepository dividendHistoryRepo;
    @Mock private PriceQueryService priceQueryService;
    @Mock private TaiexDisplayPriceService taiexDisplayPriceService;
    @Mock private AssetSnapshotRepository snapshotRepo;
    @Mock private StockAlertRepository alertRepo;
    @Mock private StockRepository stockRepo;
    @Mock private MarketDataService marketDataService;
    @Mock private TradingRadarMarketContextService marketContextService;
    @Mock private FundamentalAnalysisService fundamentalAnalysisService;
    @Mock private EtfNavHistoryRepository etfNavHistoryRepo;
    @Mock private TradingRadarSnapshotStore snapshotStore;
    @Mock private CurrentUserContext currentUserContext;
    @Mock private DividendEventEvidenceRepository dividendEventEvidenceRepository;
    @Mock private TreasuryYieldService treasuryYieldService;

    /** 台股大盤那一組（{@code computeAll("0000","台股")}）：只要不是 DATA_INCOMPLETE 即可。 */
    private static final TechnicalIndicatorService.FullIndicators TW_MARKET_IND =
            new TechnicalIndicatorService.FullIndicators(
                    BigDecimal.valueOf(15000), BigDecimal.valueOf(15000), BigDecimal.valueOf(15000),
                    BigDecimal.valueOf(60), BigDecimal.valueOf(50),
                    BigDecimal.valueOf(60), BigDecimal.valueOf(50),
                    BigDecimal.valueOf(15000), TechnicalIndicatorService.ExtendedIndicators.EMPTY);

    private TradingRadarService newService() {
        TradingRadarRuleEngine ruleEngine = new TradingRadarRuleEngine();
        DistributionAdjustedPriceService adjustedPriceService = new DistributionAdjustedPriceService();
        // 真的指標服務：computeFromSeries 是純函數，四個依賴在這條路徑上完全不會被碰到。
        TechnicalIndicatorService seriesIndicatorService = new TechnicalIndicatorService(
                priceHistoryRepo, priceQueryService, twseRepo, usIndexDailyHistoryRepo);
        return new TradingRadarService(
                ruleEngine,
                marketIndicatorService,
                adjustedPriceService,
                new RadarInputAssembler(seriesIndicatorService, adjustedPriceService, ruleEngine),
                assetClassifier,
                twseRepo,
                usIndexDailyHistoryRepo,
                priceHistoryRepo,
                dividendHistoryRepo,
                priceQueryService,
                taiexDisplayPriceService,
                snapshotRepo,
                alertRepo,
                stockRepo,
                marketDataService,
                marketContextService,
                fundamentalAnalysisService,
                etfNavHistoryRepo,
                snapshotStore,
                currentUserContext,
                dividendEventEvidenceRepository,
                treasuryYieldService);
    }

    private void stubBaseline() {
        lenient().when(marketDataService.isTradingDay(anyString(), any(LocalDate.class))).thenReturn(true);
        lenient().when(marketContextService.resolve(any())).thenReturn(
                new TradingRadarMarketContextService.Resolved(
                        TradingRadarMarketContextService.MarketContext.EMPTY, List.of()));
        lenient().when(fundamentalAnalysisService.resolve(any(), any(), any(), any()))
                .thenReturn(FundamentalAnalysisService.Resolved.unavailable(false));
        lenient().when(taiexDisplayPriceService.resolve()).thenReturn(
                new TaiexDisplayPriceService.DisplayQuote(
                        null, null, null, null, null, null, null, null, true, "CLOSE_PENDING"));
        lenient().when(snapshotRepo.findLatestWithStocks()).thenReturn(Optional.empty());
        lenient().when(alertRepo.findDistinctStockCodeMarket())
                .thenReturn(List.<Object[]>of(new Object[]{CODE, TW}));
        lenient().when(priceQueryService.getLive(anyString(), anyString())).thenReturn(Optional.empty());
        lenient().when(priceQueryService.getDisplayPrice(anyString(), anyString()))
                .thenReturn(Optional.empty());
        lenient().when(usIndexDailyHistoryRepo.findTopNByIndexCodeOrderByTradingDateDesc(anyString(), anyInt()))
                .thenReturn(List.of());
        // Task 323：buildUsMarket() 改由 resolveMarketFromRows 取得 IXIC 量能 context；
        // 本檔只驗證台股個股序列，美股組回 EMPTY 即可。
        lenient().when(marketContextService.resolveMarketFromRows(
                        anyString(), any(), anyList(), anyList()))
                .thenReturn(TradingRadarMarketContextService.MarketContext.EMPTY);
        // 大盤必須是可用的（非 DATA_INCOMPLETE），否則個股會因「大盤資料不足」落入同一個
        // NO_TRADE 分支，測試就分不出是本任務的 bug 還是大盤沒 stub。
        lenient().when(twseRepo.findTopNByOrderByTradingDateDesc(241)).thenReturn(twseRows());
        lenient().when(marketIndicatorService.computeAll("0000", TW)).thenReturn(TW_MARKET_IND);
    }

    // ─────────────────────── 主案例：null 來源的歷史列必須留在技術序列 ───────────────────────

    @Test
    void 台股歷史列closeSource全為null時技術指標仍算得出來且不落入資料不足分支() {
        stubBaseline();
        // 242 根：最近 4 個交易日帶 TWSE_MI_INDEX（＝線上實測的形狀，四天官方對帳），
        // 其餘 238 根 close_source 為 null。最新一根即 completedSession 且已驗證，故無剔除，
        // 序列截斷後為 241 根，剛好滿足 confirm(closes, 240) 的 241 根需求。
        when(priceHistoryRepo.findRecentN(CODE, TW, 250)).thenReturn(twStockRows(
                TODAY, 242, Set.of(TODAY, TODAY.minusDays(1), TODAY.minusDays(2), TODAY.minusDays(3))));

        TradingRadarDto.StockDecision decision = decisionAt(AFTER_CLOSE);

        assertNotNull(decision.monthlyMa(), "MA20 不得因歷史列缺 close_source 而消失");
        assertNotNull(decision.quarterlyMa(), "MA60 不得因歷史列缺 close_source 而消失");
        assertNotNull(decision.annualMa(), "MA240 不得因歷史列缺 close_source 而消失");
        assertNotNull(decision.kValue());
        assertNotNull(decision.dValue());
        assertFalse(decision.risks().contains(INSUFFICIENT_DATA_RISK),
                "歷史根數足夠時不得輸出資料不足風險文案，實際 risks=" + decision.risks());
        assertNotEquals("UNAVAILABLE", decision.annualConfirmation());
        assertTrue(decision.dataComplete());
        assertNotEquals("NO_TRADE", decision.action());
        // verified 語意不變：accepted price 仍取當日已對帳的那一列。
        assertEquals("VERIFIED_CLOSE", decision.quoteStatus());
        assertEquals(TODAY.toString(), decision.asOfDate());
    }

    // ─────────────── off-by-one 專屬：兩個剔除來源各一，都必須有名額可遞補 ───────────────

    @Test
    void completedSession當日列未驗證被剔除一根後仍算得出年線兩日確認() {
        stubBaseline();
        // 剔除來源 (1)：completedSession 當日 DB 列存在但 close_source 為 null（＝盤後對帳前的
        // 13:32 誤寫列），該根不得進技術序列。242 − 1 = 241，正好夠 confirm(closes, 240)。
        // 只斷言五個指標欄位會漏掉這個 bug——含 live 的序列照樣算得出 MA／KD，
        // 唯一露餡的是 ma240Confirmation 退回 UNAVAILABLE 後的 NO_TRADE。
        when(priceHistoryRepo.findRecentN(CODE, TW, 250)).thenReturn(twStockRows(TODAY, 242, Set.of()));
        when(priceQueryService.getLive(CODE, TW)).thenReturn(Optional.of(live(TODAY, "101")));

        TradingRadarDto.StockDecision decision = decisionAt(AFTER_CLOSE);

        assertNotEquals("UNAVAILABLE", decision.annualConfirmation(),
                "當日未驗證列被剔除後仍須有 241 根完成收盤，年線兩日確認不得退回 UNAVAILABLE");
        assertFalse(decision.risks().contains(INSUFFICIENT_DATA_RISK),
                "實際 risks=" + decision.risks());
        assertTrue(decision.dataComplete());
        assertNotEquals("NO_TRADE", decision.action());
        assertNotNull(decision.annualMa());
        assertEquals("LIVE", decision.quoteStatus(), "當日沒有可信完成列時，現價只能來自 Redis live");
    }

    @Test
    void 盤中未來列與前一交易日未驗證列同時剔除兩根後仍算得出年線兩日確認() {
        stubBaseline();
        // 兩個剔除來源同時發生：
        //   (1) 盤中 completedSession 為 2026-08-11，DB 已有 08-12 當日列 → 未來列，剔除 1 根；
        //   (2) 08-11（completedSession 當日）close_source 為 null → provenance 剔除 1 根。
        // 243 − 2 = 241。findRecentN 沒有日期上界，正是這一路吃掉 LIMIT 名額的來源。
        when(priceHistoryRepo.findRecentN(CODE, TW, 250)).thenReturn(twStockRows(TODAY, 243, Set.of()));
        when(priceQueryService.getLive(CODE, TW)).thenReturn(Optional.of(live(TODAY, "101")));

        TradingRadarDto.StockDecision decision = decisionAt(INTRADAY);

        assertNotEquals("UNAVAILABLE", decision.annualConfirmation(),
                "兩個剔除來源同時發生時仍須有 241 根完成收盤（這正是 250 取數緩衝存在的理由）");
        assertFalse(decision.risks().contains(INSUFFICIENT_DATA_RISK),
                "實際 risks=" + decision.risks());
        assertTrue(decision.dataComplete());
        assertNotEquals("NO_TRADE", decision.action());
        assertNotNull(decision.annualMa());
        assertEquals(TODAY.toString(), decision.asOfDate(),
                "盤中 accepted price 走 live，as-of 為當日");
    }

    // ─────────────────────────────── helpers ───────────────────────────────

    private TradingRadarDto.StockDecision decisionAt(Instant decisionInstant) {
        return newService().assembleAt(decisionInstant).stocks().stream()
                .filter(row -> CODE.equals(row.stockCode()))
                .findFirst().orElseThrow();
    }

    /**
     * 降序（新到舊）台股日 K。價格溫和震盪（單日變動 &lt; 5%），避免踩到
     * {@link DistributionAdjustedPriceService} 的分割偵測而讓序列被還原改寫。
     *
     * @param verifiedDates 這些日期的列帶 {@code TWSE_MI_INDEX}，其餘一律 {@code close_source=null}
     *                      （Task 290：既有列不得回填來源）。
     */
    private static List<StockPriceHistory> twStockRows(
            LocalDate newest, int count, Set<LocalDate> verifiedDates) {
        List<StockPriceHistory> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            LocalDate date = newest.minusDays(i);
            BigDecimal close = BigDecimal.valueOf(100 + (i % 20) * 0.25)
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            rows.add(StockPriceHistory.builder()
                    .stockCode(CODE)
                    .market(TW)
                    .tradingDate(date)
                    .openPrice(close)
                    .highPrice(close.add(BigDecimal.valueOf(0.5)))
                    .lowPrice(close.subtract(BigDecimal.valueOf(0.5)))
                    .closePrice(close)
                    .volume(1_000_000L + i)
                    .closeSource(verifiedDates.contains(date) ? "TWSE_MI_INDEX" : null)
                    .build());
        }
        return rows;
    }

    /** 241 根台股加權指數收盤，讓大盤那一組拿得到 confirm(60)／confirm(240)。 */
    private static List<TwseIndexDailyHistory> twseRows() {
        List<TwseIndexDailyHistory> rows = new ArrayList<>();
        for (int i = 0; i < 241; i++) {
            TwseIndexDailyHistory row = new TwseIndexDailyHistory();
            row.setTradingDate(TODAY.minusDays(i));
            row.setClosePoint(BigDecimal.valueOf(20000 - i * 5));
            rows.add(row);
        }
        return rows;
    }

    private static PriceQueryService.LivePrice live(LocalDate tradingDate, String price) {
        return new PriceQueryService.LivePrice(
                CODE, null, TW, new BigDecimal(price), null, null, null,
                null, null, null, null, null, null,
                tradingDate.toString(), tradingDate + "T05:30:00Z", false, "REDIS", "LIVE");
    }
}
