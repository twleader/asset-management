package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.model.StockDividendHistory;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Task 356.1b-2：還原權息揭露句必須<b>三軌都有</b>，不得只有既有兩軌。
 *
 * <p>這三句的出處只有一個地方——{@code TradingRadarService.buildStock}。規則引擎的
 * {@code evaluateStock} <b>不產生任何還原字串</b>（全檔「還原」只出現在 Javadoc），因為
 * 「這批序列有沒有真的被還原」是 {@code RadarInputAssembler} 依事件日與日K 契約視窗判定的
 * {@code distributionAdjusted}，引擎根本收不到。故三軌的揭露句一律由 service 自己加上。</p>
 *
 * <p>建構方式照抄同目錄 {@link TradingRadarIndicatorSeriesProvenanceTest}：只有大盤那組
 * {@code computeAll} 走 mock，個股序列的指標與還原都用<b>真的</b>
 * {@link TechnicalIndicatorService}／{@link DistributionAdjustedPriceService}——還原若改成
 * mock，{@code distributionAdjusted} 就成了測試自己餵的常數，本檔要驗的東西會整個落空。</p>
 */
@ExtendWith(MockitoExtension.class)
class TradingRadarDistributionDisclosureTest {

    private static final String CODE = "2330";
    private static final String TW = "台股";
    /** 2026-08-12（三）；台北 14:00 已收盤，completedSession 就是當日。 */
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 12);
    private static final Instant AFTER_CLOSE = Instant.parse("2026-08-12T06:00:00Z");
    /** 揭露句的共同關鍵字；三軌各自的措辭不同，但都必須寫出「還原權息」。 */
    private static final String DISCLOSURE = "還原權息";

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
                    BigDecimal.valueOf(15000), TechnicalIndicatorService.ExtendedIndicators.EMPTY, null);

    @Test
    @DisplayName("356.1b-2 還原權息時三軌 reasons 各有一句揭露（swing 不得獨缺）")
    void allThreeHorizonsCarryTheDistributionDisclosureWhenPricesWereAdjusted() {
        stubBaseline();
        when(priceHistoryRepo.findRecentN(CODE, TW, 500)).thenReturn(twStockRows(
                TODAY, 242, Set.of(TODAY, TODAY.minusDays(1), TODAY.minusDays(2), TODAY.minusDays(3))));
        // 事件日落在日K 契約視窗（241 列）之內 → 視窗內確實有列被縮放 → distributionAdjusted 為真。
        when(dividendHistoryRepo.findAdjustmentEvents(eq(CODE), eq(TW), any(), any()))
                .thenReturn(List.of(exDividend(TODAY.minusDays(100))));

        TradingRadarDto.StockDecision decision = decision();

        assertThat(decision.distributionAdjusted())
                .as("事件落在契約視窗內時必須為真，否則本測試驗不到任何東西")
                .isTrue();
        assertThat(disclosuresIn(decision.reasons()))
                .as("1月~6月 軌，實得 reasons=%s", decision.reasons()).hasSize(1);
        assertThat(disclosuresIn(decision.shortReasons()))
                .as("一周軌，實得 shortReasons=%s", decision.shortReasons()).hasSize(1);
        assertThat(disclosuresIn(decision.swingReasons()))
                .as("1周~1月 軌不得是三軌中唯一沒有揭露的那一軌，實得 swingReasons=%s",
                        decision.swingReasons())
                .hasSize(1);
        assertThat(disclosuresIn(decision.swingReasons()).get(0))
                .as("swing 的揭露句要寫出自己那一軌的持有期，不得直接複製另外兩軌的句子")
                .contains("1周~1月");
    }

    @Test
    @DisplayName("356.1b-2 沒有還原時三軌都不得出現揭露句")
    void noHorizonClaimsAdjustedPricesWhenNothingWasAdjusted() {
        stubBaseline();
        // findAdjustmentEvents 未 stub → Mockito 回空 list，等同「這段區間查無事件」。
        when(priceHistoryRepo.findRecentN(CODE, TW, 500)).thenReturn(twStockRows(
                TODAY, 242, Set.of(TODAY, TODAY.minusDays(1), TODAY.minusDays(2), TODAY.minusDays(3))));

        TradingRadarDto.StockDecision decision = decision();

        assertThat(decision.distributionAdjusted()).isFalse();
        assertThat(disclosuresIn(decision.reasons())).isEmpty();
        assertThat(disclosuresIn(decision.shortReasons())).isEmpty();
        assertThat(disclosuresIn(decision.swingReasons()))
                .as("沒有任何一列被縮放時宣稱「已使用還原權息序列」是假陳述")
                .isEmpty();
    }

    // ─────────────────────────────── helpers ───────────────────────────────

    private static List<String> disclosuresIn(List<String> lines) {
        return lines.stream().filter(line -> line.contains(DISCLOSURE)).toList();
    }

    private TradingRadarDto.StockDecision decision() {
        return newService().assembleAt(AFTER_CLOSE).stocks().stream()
                .filter(row -> CODE.equals(row.stockCode()))
                .findFirst().orElseThrow();
    }

    private TradingRadarService newService() {
        TradingRadarRuleEngine ruleEngine = new TradingRadarRuleEngine();
        DistributionAdjustedPriceService adjustedPriceService = new DistributionAdjustedPriceService();
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
        lenient().when(marketContextService.resolveMarketFromRows(
                        anyString(), any(), anyList(), anyList()))
                .thenReturn(TradingRadarMarketContextService.MarketContext.EMPTY);
        lenient().when(fundamentalAnalysisService.resolve(any(), any(), any(), any()))
                .thenReturn(FundamentalAnalysisService.Resolved.unavailable(false));
        lenient().when(taiexDisplayPriceService.resolve()).thenReturn(
                new TaiexDisplayPriceService.DisplayQuote(
                        null, null, null, null, null, null, null, null, true, "CLOSE_PENDING"));
        lenient().when(snapshotRepo.findLatestWithStocks()).thenReturn(Optional.empty());
        lenient().when(snapshotRepo.findLatestWithStocksByOwnerUserId(anyLong())).thenReturn(Optional.empty());
        lenient().when(alertRepo.findDistinctStockCodeMarket())
                .thenReturn(List.<Object[]>of(new Object[]{CODE, TW}));
        lenient().when(priceQueryService.getLive(anyString(), anyString())).thenReturn(Optional.empty());
        lenient().when(priceQueryService.getDisplayPrice(anyString(), anyString()))
                .thenReturn(Optional.empty());
        lenient().when(usIndexDailyHistoryRepo.findTopNByIndexCodeOrderByTradingDateDesc(anyString(), anyInt()))
                .thenReturn(List.of());
        lenient().when(twseRepo.findTopNByOrderByTradingDateDesc(500)).thenReturn(twseRows());
        lenient().when(marketIndicatorService.computeAll("0000", TW)).thenReturn(TW_MARKET_IND);
    }

    /**
     * 降序（新到舊）台股日 K，形狀比照 {@link TradingRadarIndicatorSeriesProvenanceTest}：
     * 242 根，最近 4 個交易日帶 provenance，其餘 {@code close_source} 為 null。價格溫和震盪
     * （單日變動 &lt; 5%），避免踩到 {@link DistributionAdjustedPriceService} 的分割偵測。
     */
    private static List<StockPriceHistory> twStockRows(
            LocalDate newest, int count, Set<LocalDate> verifiedDates) {
        List<StockPriceHistory> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            LocalDate date = newest.minusDays(i);
            BigDecimal close = BigDecimal.valueOf(100 + (i % 20) * 0.25)
                    .setScale(2, RoundingMode.HALF_UP);
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

    private static StockDividendHistory exDividend(LocalDate exDate) {
        StockDividendHistory event = new StockDividendHistory();
        event.setStockCode(CODE);
        event.setMarket(TW);
        event.setExDividendDate(exDate);
        event.setCashDividend(new BigDecimal("2.00"));
        return event;
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
}
