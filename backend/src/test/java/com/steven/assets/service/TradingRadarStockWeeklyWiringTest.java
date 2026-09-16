package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.model.Stock;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 356.5a／356.6：<b>production 的個股路徑必須把 {@code dailyCandle} 與 {@code weekly}
 * 真的餵進 {@code StockInput}。</b>
 *
 * <p><b>本檔存在的理由是一個真的發生過的缺陷。</b>{@code TradingRadarService.buildStock} 原本只傳
 * 25 個引數（最後一個是 {@code fundamental.input()}），因而命中 Task 356 之前保留下來的
 * <b>相容建構式</b>——少傳引數不會編譯失敗、不會拋例外、不會有任何 log——兩個新欄位固定被填成
 * {@code null}。後果是 Task 356 新增的五個因子（日K 棒、週線趨勢、週線動能、週線乖離、
 * 週K 棒與量能）在正式頁面／Redis 快照／匯出／通知路徑上<b>恆為缺值</b>、權重全被重分配掉，
 * 而 {@code BacktestService} 那一側傳對了，於是線上與回測靜默分岔——正是 Task 323 修過、
 * 規格 356.13b-2 明文警告的那個病的鏡像版本。</p>
 *
 * <p>當時唯一的外部徵狀是：DTO 的 {@code dailyCandle} 有完整 OHLC、{@code completedWeeks=35}，
 * 但同一列的 {@code risks} 同時出現「最新完成日 K 棒的 OHLC 資料缺漏」與「週K 完成週不足 60 根
 * （目前 <b>0</b> 根）」。DTO 與引擎讀的是同一份 {@code Assembled}，兩者不可能同時為真——
 * 這一組矛盾就是本檔的斷言主體。</p>
 *
 * <p>{@link RadarInputAssembler}、{@link TechnicalIndicatorService} 與
 * {@link DistributionAdjustedPriceService} 一律用<b>真實物件</b>（只有底層 repository 是 mock）：
 * 用 {@code @Mock} 的 assembler 只會回 {@code null}，本檔全部斷言會變成恆真。</p>
 */
@ExtendWith(MockitoExtension.class)
class TradingRadarStockWeeklyWiringTest {

    private static final String CODE = "2330";
    private static final String TW = "台股";
    /** 2026-08-12（三）；台北 14:00 已收盤，completedSession 就是當日。 */
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 12);
    private static final Instant AFTER_CLOSE = Instant.parse("2026-08-12T06:00:00Z");

    /** 缺值揭露句的關鍵字；兩者都是「引擎收到 null」的唯一可能來源。 */
    private static final String OHLC_MISSING = "OHLC 資料缺漏";
    private static final String WEEKS_INSUFFICIENT = "週K 完成週不足";

    /**
     * 500 個連續日曆日 ≈ 71 個 ISO 週（&gt; {@link RadarInputAssembler#MIN_COMPLETED_WEEKS}）；
     * 241 列的日K 契約只有 ≈ 34 週，故長視窗是週K 因子成立的前提。
     */
    private static final int SERIES_ROWS = 500;

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
    @Mock private TradingRadarSettingsClassificationResolver settingsClassificationResolver;

    /** 台股大盤那一組（{@code computeAll("0000","台股")}）：只要不是 DATA_INCOMPLETE 即可。 */
    private static final TechnicalIndicatorService.FullIndicators TW_MARKET_IND =
            new TechnicalIndicatorService.FullIndicators(
                    BigDecimal.valueOf(15000), BigDecimal.valueOf(15000), BigDecimal.valueOf(15000),
                    BigDecimal.valueOf(60), BigDecimal.valueOf(50),
                    BigDecimal.valueOf(60), BigDecimal.valueOf(50),
                    BigDecimal.valueOf(15000), TechnicalIndicatorService.ExtendedIndicators.EMPTY, null);

    /** 每個測試各自建立一份 spy，供捕捉真正送進引擎的 {@code StockInput}。 */
    private TradingRadarRuleEngine ruleEngine;

    // ── (a) 接線本身：兩個新欄位必須真的抵達引擎 ─────────────────────────────────

    @Test
    @DisplayName("356.5a／356.6：production 的 StockInput 必須帶著 dailyCandle 與 weekly（不得命中相容建構式）")
    void productionStockInputCarriesDailyCandleAndWeekly() {
        stubBaseline();
        stubLongSeries();

        newService().assembleAt(AFTER_CLOSE);

        TradingRadarRuleEngine.StockInput input = captureStockInput();
        assertThat(input.dailyCandle())
                .as("少傳引數會靜默命中 Task 356 之前的 25 參數相容建構式，這個欄位就固定是 null")
                .isNotNull();
        assertThat(input.dailyCandle().open()).isNotNull();
        assertThat(input.dailyCandle().high()).isNotNull();
        assertThat(input.dailyCandle().low()).isNotNull();
        assertThat(input.dailyCandle().close()).isNotNull();
        assertThat(input.weekly())
                .as("週K 同上；DTO 有值而引擎收到 null 正是本缺陷的形狀")
                .isNotNull();
        assertThat(input.weekly().completedWeeks())
                .as("500 列 ≈ 71 個 ISO 週，必須跨過 60 根門檻，否則本檔驗不到任何東西")
                .isGreaterThanOrEqualTo(RadarInputAssembler.MIN_COMPLETED_WEEKS);
        assertThat(input.weekly().ma10()).as("完成週足夠時週MA10 必須算得出來").isNotNull();
        assertThat(input.weekly().k()).isNotNull();
        assertThat(input.weekly().osc()).isNotNull();
    }

    @Test
    @DisplayName("Task408：個股組裝 fail-soft 時仍保留設定頁等價分類，不以 strict profile 代替")
    void incompleteCurrentRowRetainsSettingsEquivalentClassification() {
        stubBaseline();
        Stock master = Stock.builder().code(CODE).market(TW).name("台積電").build();
        when(stockRepo.findByCodeAndMarket(CODE, TW)).thenReturn(Optional.of(master));
        var settings = new TradingRadarSettingsClassificationResolver.Resolution(
                AssetClassifier.STOCK, "RULE", AssetClassifier.INCOME, "RULE",
                null, null, null, null, null);
        when(settingsClassificationResolver.resolve(master, CODE, TW, "台積電")).thenReturn(settings);
        when(dividendEventEvidenceRepository.resolve(anyString(), anyString(), any(), anyList()))
                .thenThrow(new IllegalStateException("forced downstream failure"));

        TradingRadarDto.StockDecision row = newServiceWithSettingsResolver().assembleAt(AFTER_CLOSE).stocks().stream()
                .filter(candidate -> CODE.equals(candidate.stockCode()))
                .findFirst().orElseThrow();

        assertThat(row.dataComplete()).isFalse();
        assertThat(row.evidence()).isNotNull();
        assertThat(row.evidence().settingsClassification()).isNotNull();
        assertThat(row.evidence().settingsClassification().effectiveAssetClass()).isEqualTo(AssetClassifier.STOCK);
        assertThat(row.evidence().settingsClassification().assetClassSource()).isEqualTo("RULE");
        assertThat(row.evidence().settingsClassification().effectiveStockStyle()).isEqualTo(AssetClassifier.INCOME);
        assertThat(row.evidence().assetProfile())
                .as("incomplete row must not fabricate strict profile to stand in for settings classification")
                .isNull();
        verify(settingsClassificationResolver).resolve(master, CODE, TW, "台積電");
    }

    @Test
    @DisplayName("Task408：僅台股 0000 是大盤；非台股同碼標的仍要出現在雷達並保留設定分類")
    void nonTaiwanSameCodeZeroRemainsRadarTargetWithSettingsClassification() {
        String us = "美股";
        String sameCode = "0000";
        stubBaseline();
        Stock master = Stock.builder().code(sameCode).market(us).name("US Same Code").build();
        when(alertRepo.findDistinctStockCodeMarket()).thenReturn(List.<Object[]>of(new Object[]{sameCode, us}));
        when(stockRepo.findByCodeAndMarket(sameCode, us)).thenReturn(Optional.of(master));
        var settings = new TradingRadarSettingsClassificationResolver.Resolution(
                AssetClassifier.STOCK, "RULE", AssetClassifier.GROWTH, "RULE",
                null, null, null, null, null);
        when(settingsClassificationResolver.resolve(master, sameCode, us, "US Same Code")).thenReturn(settings);
        when(dividendEventEvidenceRepository.resolve(anyString(), anyString(), any(), anyList()))
                .thenThrow(new IllegalStateException("forced downstream failure"));

        TradingRadarDto.StockDecision row = newServiceWithSettingsResolver().assembleAt(AFTER_CLOSE).stocks().stream()
                .filter(candidate -> sameCode.equals(candidate.stockCode()))
                .findFirst().orElseThrow();

        assertThat(row.dataComplete()).isFalse();
        assertThat(row.evidence().settingsClassification()).isNotNull();
        assertThat(row.evidence().settingsClassification().effectiveAssetClass()).isEqualTo(AssetClassifier.STOCK);
        assertThat(row.evidence().settingsClassification().effectiveStockStyle()).isEqualTo(AssetClassifier.GROWTH);
        verify(settingsClassificationResolver).resolve(master, sameCode, us, "US Same Code");
    }

    // ── (b) 外部可觀察徵狀：DTO 有值卻同時揭露缺值，兩者不可能同時為真 ──────────────

    @Test
    @DisplayName("356.5d／356.6：資料完整時三軌 risks 不得出現「OHLC 資料缺漏」或「週K 完成週不足」")
    void completeDataMustNotProduceMissingCandleOrWeeklyRisks() {
        stubBaseline();
        stubLongSeries();

        TradingRadarDto.StockDecision decision = decision();

        // 先證明「資料確實完整」，否則下面的 not-contains 會是一條恆真的空斷言。
        assertThat(decision.dailyCandle()).as("DTO 必須有完整日K 棒").isNotNull();
        assertThat(decision.dailyCandle().close()).isNotNull();
        assertThat(decision.bollinger()).isNotNull();
        TradingRadarRuleEngine.BollingerInput wiredBollinger = captureStockInput().bollinger();
        assertThat(wiredBollinger).isNotNull();
        assertThat(decision.bollinger().asOfDate()).isEqualTo(decision.dailyCandle().asOfDate());
        assertThat(decision.bollinger().percentB()).isEqualByComparingTo(wiredBollinger.percentB());
        assertThat(decision.bollinger().bandWidthPercent()).isEqualByComparingTo(wiredBollinger.bandWidthPercent());
        assertThat(decision.weeklyIndicators()).isNotNull();
        assertThat(decision.weeklyIndicators().completedWeeks())
                .isGreaterThanOrEqualTo(RadarInputAssembler.MIN_COMPLETED_WEEKS);

        List<String> allRisks = new ArrayList<>();
        allRisks.addAll(decision.risks());
        allRisks.addAll(decision.shortRisks());
        allRisks.addAll(decision.swingRisks());

        assertThat(allRisks)
                .as("DTO 有完整 OHLC 卻同時說 OHLC 缺漏，只可能是引擎收到 dailyCandle==null。實得 risks=%s",
                        allRisks)
                .noneMatch(risk -> risk.contains(OHLC_MISSING));
        assertThat(allRisks)
                .as("DTO 的 completedWeeks=%s 卻同時說完成週不足，只可能是引擎收到 weekly==null。實得 risks=%s",
                        decision.weeklyIndicators().completedWeeks(), allRisks)
                .noneMatch(risk -> risk.contains(WEEKS_INSUFFICIENT));
    }

    @Test
    @DisplayName("382.1：production DTO 的 gate 診斷只能進風險，不能被誤列為中期支持訊號")
    void productionMapsGateDiagnosticsToRisksNotSupportReasons() {
        stubBaseline();
        stubLongSeries();

        TradingRadarDto.StockDecision decision = decision();
        List<String> gateDiagnostics = decision.evidence().actionGateReasons();
        List<String> supportReasons = new ArrayList<>();
        supportReasons.addAll(decision.reasons());
        supportReasons.addAll(decision.shortReasons());
        supportReasons.addAll(decision.swingReasons());
        List<String> risks = new ArrayList<>();
        risks.addAll(decision.risks());
        risks.addAll(decision.shortRisks());
        risks.addAll(decision.swingRisks());

        assertThat(gateDiagnostics)
                .as("fixture 必須真的關閉至少一項 evidence/risk gate，不能用 empty list 假裝證明 mapping")
                .isNotEmpty();
        assertThat(supportReasons)
                .as("gate 失敗是風險診斷；不得出現在任何 horizon 的支持訊號，實得=%s", supportReasons)
                .doesNotContainAnyElementsOf(gateDiagnostics);
        assertThat(risks)
                .as("audit union 的每一項都必須仍可在相對應的風險欄位找到，實得=%s", risks)
                .containsAll(gateDiagnostics);
    }

    @Test
    @DisplayName("356.6a：週K 生效時三軌 reasons 應出現週MA 位置敘述，而不是只有日線敘述")
    void weeklyFactorsProduceTheirOwnNarrative() {
        stubBaseline();
        stubLongSeries();

        TradingRadarDto.StockDecision decision = decision();

        List<String> narrative = new ArrayList<>();
        narrative.addAll(decision.reasons());
        narrative.addAll(decision.risks());
        assertThat(narrative)
                .as("週線趨勢因子成立時必有「週MA5／週MA10／週MA20」的位置敘述；實得=%s", narrative)
                .anyMatch(line -> line.contains("週MA"));
    }

    // ── (c) 反向：五個新因子確實改變分數（否則「接上了」等於沒有意義）──────────────

    @Test
    @DisplayName("356.4g 反向斷言：同一組 production 輸入，weekly／dailyCandle 強制為 null 時分數必須不同")
    void forcingTheFiveNewFactorsToMissingChangesEveryTrackScore() {
        stubBaseline();
        stubLongSeries();

        newService().assembleAt(AFTER_CLOSE);
        TradingRadarRuleEngine.StockInput wired = captureStockInput();

        // 同一組引數，只把兩個新欄位換成 null——即缺陷版本實際送進引擎的那一份。
        TradingRadarRuleEngine.StockInput starved = withoutNewFactors(wired);

        TradingRadarRuleEngine pure = new TradingRadarRuleEngine();
        TradingRadarRuleEngine.StockResult withFactors = pure.evaluateStock(wired);
        TradingRadarRuleEngine.StockResult withoutFactors = pure.evaluateStock(starved);

        assertThat(withFactors.score())
                .as("1月~6月 軌：五個新因子若真的進了加權平均，分數必然不同於缺值重分配的版本")
                .isNotEqualTo(withoutFactors.score());
        assertThat(withFactors.swingScore())
                .as("1周~1月 軌同上")
                .isNotEqualTo(withoutFactors.swingScore());
        assertThat(withFactors.shortScore())
                .as("一周軌同上")
                .isNotEqualTo(withoutFactors.shortScore());

        // 缺值那一臂必須如實揭露；有值那一臂必須完全沒有這兩句。
        assertThat(withoutFactors.risks())
                .anyMatch(risk -> risk.contains(OHLC_MISSING))
                .anyMatch(risk -> risk.contains(WEEKS_INSUFFICIENT));
        assertThat(withFactors.risks())
                .noneMatch(risk -> risk.contains(OHLC_MISSING))
                .noneMatch(risk -> risk.contains(WEEKS_INSUFFICIENT));
    }

    // ─────────────────────────────── helpers ───────────────────────────────

    /** 只換掉 {@code dailyCandle}／{@code weekly} 兩欄，其餘 24 欄逐位沿用。 */
    private static TradingRadarRuleEngine.StockInput withoutNewFactors(
            TradingRadarRuleEngine.StockInput in) {
        return new TradingRadarRuleEngine.StockInput(
                in.held(), in.price(), in.changePercent(), in.completedChangePercent(),
                in.indicators(), in.previousK(), in.previousD(),
                in.ma20Confirmation(), in.ma60Confirmation(), in.ma240Confirmation(),
                in.instrumentType(), in.marketRegime(), in.marketStale(), in.fxPercentile(),
                in.ma60BiasPercent(), in.ma60BiasPercentile(), in.ma240BiasPercent(),
                in.week52Position(), in.kdBandWidthPercent(), in.etfPremiumPct(),
                in.etfPremiumPercentile(), in.weeklyMa(), in.extendedIndicators(),
                in.volumeRatio(), in.fundamental(), null, null);
    }

    private TradingRadarRuleEngine.StockInput captureStockInput() {
        ArgumentCaptor<TradingRadarRuleEngine.StockInput> captor =
                ArgumentCaptor.forClass(TradingRadarRuleEngine.StockInput.class);
        verify(ruleEngine, times(1)).evaluateStock(captor.capture());
        return captor.getValue();
    }

    private TradingRadarDto.StockDecision decision() {
        return newService().assembleAt(AFTER_CLOSE).stocks().stream()
                .filter(row -> CODE.equals(row.stockCode()))
                .findFirst().orElseThrow();
    }

    private TradingRadarService newService() {
        ruleEngine = Mockito.spy(new TradingRadarRuleEngine());
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

    private TradingRadarService newServiceWithSettingsResolver() {
        ruleEngine = Mockito.spy(new TradingRadarRuleEngine());
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
                treasuryYieldService,
                null,
                null,
                null,
                null,
                null,
                settingsClassificationResolver);
    }

    private void stubLongSeries() {
        when(priceHistoryRepo.findRecentN(CODE, TW, 500)).thenReturn(twStockRows(
                TODAY, SERIES_ROWS,
                Set.of(TODAY, TODAY.minusDays(1), TODAY.minusDays(2), TODAY.minusDays(3))));
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
     * 降序（新到舊）台股日 K，形狀比照 {@code TradingRadarDistributionDisclosureTest}：
     * 最近 4 個交易日帶 provenance，其餘 {@code close_source} 為 null。價格溫和震盪
     * （單日變動 &lt; 5%），避免踩到 {@link DistributionAdjustedPriceService} 的分割偵測；
     * {@code open}／{@code high}／{@code low} 逐列俱全且全幅為正，故日K 棒三分量必定算得出來。
     *
     * <p><b>最新完成日（{@code i == 0}）刻意做成極端偏空的 K 棒</b>（開＝最高、收貼近最低、
     * 幾乎沒有下影線），三分量因此都接近 {@code −1}。理由是分數是<b>四捨五入後的整數</b>：
     * 一周軌在五個新因子上的權重合計只有 {@code 0.16}，用中性 K 棒的話「有值」與「強制 null」
     * 兩臂會落在同一個整數上，那條反向斷言就會變成偽陰性（看似沒接上也照樣綠燈）。
     * 其餘 499 列維持中性形狀，故本設定只放大日K 棒那一項的可觀測性，不影響任何既有序列語意。</p>
     */
    private static List<StockPriceHistory> twStockRows(
            LocalDate newest, int count, Set<LocalDate> verifiedDates) {
        List<StockPriceHistory> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            LocalDate date = newest.minusDays(i);
            BigDecimal close = BigDecimal.valueOf(100 + (i % 20) * 0.25)
                    .setScale(2, RoundingMode.HALF_UP);
            boolean latestCompleted = i == 0;
            BigDecimal open = latestCompleted ? close.add(BigDecimal.valueOf(1.00)) : close;
            BigDecimal high = latestCompleted
                    ? close.add(BigDecimal.valueOf(1.00)) : close.add(BigDecimal.valueOf(0.5));
            BigDecimal low = latestCompleted
                    ? close.subtract(BigDecimal.valueOf(0.05)) : close.subtract(BigDecimal.valueOf(0.5));
            rows.add(StockPriceHistory.builder()
                    .stockCode(CODE)
                    .market(TW)
                    .tradingDate(date)
                    .openPrice(open)
                    .highPrice(high)
                    .lowPrice(low)
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
}
