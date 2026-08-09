package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.Stock;
import com.steven.assets.repository.EtfNavHistoryRepository;
import com.steven.assets.model.StockHolding;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.model.UsIndexDailyHistory;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.StockAlertRepository;
import com.steven.assets.repository.StockDividendHistoryRepository;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import com.steven.assets.repository.UsIndexDailyHistoryRepository;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.util.MarketZones;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 今日交易雷達（Requirement 43）資料組裝。
 *
 * <p>純讀 PostgreSQL／Redis；刻意不注入 MarketAnalysisService、新聞爬蟲或任何 AI client。</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TradingRadarService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final String TW_MARKET = "台股";
    private static final String US_MARKET = "美股";
    private static final String TAIEX_CODE = "0000";
    private static final String IXIC_CODE = "IXIC";
    /** 美東收盤時刻，供判斷「已完成的最近一個美股交易日」（Task 294.2）；比照 US_MARKET_COMPLETE 既有慣例。 */
    private static final LocalTime US_MARKET_CLOSE = LocalTime.of(16, 0);

    private static final String TWD = "TWD";

    /**
     * ETF 折溢價分位的回看筆數與最小樣本數（Task 264）。
     *
     * <p><b>必須用自身歷史分位而非絕對值評分</b>：不同 ETF 的常態折溢價水準差異極大（台灣債券 ETF
     * 長期存在結構性溢價），用同一組絕對門檻套全部 ETF 會系統性誤判。絕對門檻另由規則引擎的硬否決處理。</p>
     *
     * <p><b>可用時程</b>：{@code etf_nav_history} 建表於 2026-07-19 且不回填，故本因子在上線後
     * 約 3 個月內對每一檔 ETF 恆為 {@code null}（由權重重分配吸收）；絕對硬否決不受此限、即刻生效。</p>
     */
    private static final int ETF_PREMIUM_LOOKBACK_DAYS = 250;
    private static final int ETF_PREMIUM_MIN_SAMPLES = 60;

    private final TradingRadarRuleEngine ruleEngine;
    private final TechnicalIndicatorService indicatorService;
    private final DistributionAdjustedPriceService adjustedPriceService;
    /**
     * 把 OHLC 序列組成 {@code StockInput} 技術面欄位的<b>唯一擁有者</b>（Task 273 的 273.2b）。
     * 本服務與 {@code BacktestService} 共用它——回測若另寫一份，兩份會隨演進而分歧，
     * 屆時回測量到的是與線上不同的規則，且不會有任何報錯。
     */
    private final RadarInputAssembler assembler;
    private final AssetClassifier assetClassifier;
    private final TwseIndexDailyHistoryRepository twseRepo;
    /** IXIC（那斯達克綜合指數）美股大盤情境的資料來源（Task 294）；台股大盤沿用 twseRepo，兩者分表。 */
    private final UsIndexDailyHistoryRepository usIndexDailyHistoryRepo;
    private final StockPriceHistoryRepository priceHistoryRepo;
    private final StockDividendHistoryRepository dividendHistoryRepo;
    private final PriceQueryService priceQueryService;
    private final TaiexDisplayPriceService taiexDisplayPriceService;
    private final AssetSnapshotRepository snapshotRepo;
    private final StockAlertRepository alertRepo;
    private final StockRepository stockRepo;
    private final MarketDataService marketDataService;
    private final TradingRadarMarketContextService marketContextService;
    private final FundamentalAnalysisService fundamentalAnalysisService;
    private final EtfNavHistoryRepository etfNavHistoryRepo;
    private final TradingRadarSnapshotStore snapshotStore;
    private final CurrentUserContext currentUserContext;

    /** @param stale 大盤最新完成日 K 不是當前台股交易日（Task 217.1）。 */
    private record MarketState(
            TradingRadarDto.MarketSummary summary,
            TradingRadarRuleEngine.MarketRegime regime,
            boolean stale
    ) {}

    /**
     * 通知路徑用的大盤快照（Task 302）：{@link #buildMarketSnapshot} 每輪每個市場只組一次，
     * 供 {@code TradingRadarNotificationService#flushEvaluations} 批次共用，取代逐檔各自
     * 重建大盤。只帶個股評分需要的三個欄位——不像 {@link MarketState} 還帶頁面用的 DTO summary，
     * 通知路徑用不到。
     */
    public record MarketSnapshot(
            TradingRadarRuleEngine.MarketRegime regime,
            boolean stale,
            Instant decisionInstant
    ) {}

    private record Target(String code, String market, boolean held) {}

    /**
     * 當前台股交易日：今天是交易日就取今天，否則往回找最近一個交易日。
     * 用於判斷大盤資料是否停在更早的交易日；沿用既有交易日曆，不自建假日表。
     */
    private LocalDate currentTwTradingDay(Instant decisionInstant) {
        LocalDate day = decisionInstant.atZone(TAIPEI).toLocalDate();
        for (int i = 0; i < 14; i++) {
            if (marketDataService.isTradingDay(TW_MARKET, day)) return day;
            day = day.minusDays(1);
        }
        return day;
    }

    /**
     * 已完成（收盤時刻已過）的最近一個美股交易日，供 {@link #buildUsMarket} 判斷 IXIC 資料是否 stale
     * （Task 294.2）。IXIC 大盤不像台股組有 Redis 即時價可退回判斷（刻意不併入即時價，見 294.1 背景），
     * 故改直接用美東收盤時刻界定「已完成」：收盤前，今天尚不能算數，須往前一個交易日找。
     */
    private LocalDate mostRecentCompletedUsTradingDay(Instant decisionInstant) {
        ZonedDateTime nowNy = decisionInstant.atZone(NEW_YORK);
        LocalDate day = nowNy.toLocalTime().isBefore(US_MARKET_CLOSE)
                ? nowNy.toLocalDate().minusDays(1)
                : nowNy.toLocalDate();
        for (int i = 0; i < 14; i++) {
            if (marketDataService.isTradingDay(US_MARKET, day)) return day;
            day = day.minusDays(1);
        }
        return day;
    }

    @Transactional(readOnly = true)
    public TradingRadarDto.Response get() {
        TradingRadarDto.Response response = assemble(null);

        // Requirement 48：每次頁面計算把結果存為 per-owner Redis 快照供匯出（fail-soft、僅登入的 HTTP 請求）。
        try {
            if (RequestContextHolder.getRequestAttributes() != null && currentUserContext.hasUser()) {
                snapshotStore.save(currentUserContext.getEffectiveUserId(), response);
            }
        } catch (Exception e) {
            log.warn("交易雷達快照寫入失敗（不影響頁面）：{}", e.toString());
        }
        return response;
    }

    /**
     * 背景產檔前的重算（Task 260）：顯式 owner、不觸碰 request-scoped 的 CurrentUserContext，
     * 重算後 append 一筆快照供當日匯出。
     */
    @Transactional(readOnly = true)
    public TradingRadarDto.Response recomputeAndStoreForOwner(long ownerId) {
        TradingRadarDto.Response response = assemble(ownerId);
        snapshotStore.saveRecomputed(ownerId, response);
        return response;
    }

    /**
     * 今日交易雷達的資料組裝主體（Task 260 從 {@code get()} 抽出）。
     *
     * @param ownerId {@code null} 代表走 request-scoped {@code ownerFilter}（HTTP 路徑，
     *                無 owner 查詢方法）；非 null 代表顯式 owner 查詢（背景路徑，owner-scoped
     *                查詢方法），不得依賴 {@link CurrentUserContext}。
     */
    private TradingRadarDto.Response assemble(Long ownerId) {
        Instant decisionInstant = Instant.now();
        TradingRadarMarketContextService.Resolved context = marketContextService.resolve(decisionInstant);
        MarketState twMarket = buildMarket(context.market(), decisionInstant);
        // 不論本輪有沒有美股標的都計算（比照台股組現行行為），維持「大盤資料與個股清單解耦」的既有設計。
        MarketState usMarket = buildUsMarket(decisionInstant);
        Map<String, Target> targets = new LinkedHashMap<>();
        Set<String> skippedNonTw = new HashSet<>();
        loadLatestHoldings(targets, skippedNonTw, ownerId);
        loadWatchList(targets, skippedNonTw, ownerId);

        // Task 303：同一次 assemble() 內同幣別的 FxContext 只解析一次（20 檔美股原本各自重查 5 年
        // 匯率）。stream 目前循序執行，但用 ConcurrentHashMap 防未來並行化踩雷。
        Map<String, TradingRadarMarketContextService.FxContext> fxCache = new java.util.concurrent.ConcurrentHashMap<>();
        List<TradingRadarDto.StockDecision> decisions = targets.values().stream()
                .filter(t -> (TW_MARKET.equals(t.market()) || US_MARKET.equals(t.market()))
                        && !TAIEX_CODE.equals(t.code()))
                .map(t -> buildStock(t, regimeFor(t.market(), twMarket, usMarket),
                        staleFor(t.market(), twMarket, usMarket), decisionInstant, fxCache))
                .sorted(Comparator
                        .comparing(TradingRadarService::bestScore,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(TradingRadarDto.StockDecision::stockCode))
                .toList();

        return new TradingRadarDto.Response(
                TradingRadarRuleEngine.RULE_VERSION,
                decisionInstant.atZone(TAIPEI).toOffsetDateTime().toString(),
                twMarket.summary(),
                decisions,
                skippedNonTw.size(),
                context.publicInformation());
    }

    /** 依標的市場選對應的大盤組別（Task 294.3／294.6）；不得用同一個變數餵給兩種市場的股票。 */
    private TradingRadarRuleEngine.MarketRegime regimeFor(
            String market, MarketState twMarket, MarketState usMarket) {
        return US_MARKET.equals(market) ? usMarket.regime() : twMarket.regime();
    }

    private boolean staleFor(String market, MarketState twMarket, MarketState usMarket) {
        return US_MARKET.equals(market) ? usMarket.stale() : twMarket.stale();
    }

    /**
     * 背景通知評估共用同一份 V11 組裝；通知狀態仍只追蹤中期 action。
     *
     * <p>依傳入的 {@code market} 只組裝對應的那一組大盤（Task 294.6），確保背景通知評估與前景頁面
     * 對同一檔美股股票算出一致的結果——不像 {@link #assemble} 兩組都要（供其餘標的使用），
     * 這裡只評估單一標的，另一組大盤用不到。</p>
     *
     * <p>委派 {@link #buildMarketSnapshot} ＋ 四參數 overload（Task 302）：本方法每次呼叫仍各自
     * 重建一次大盤，供單檔呼叫端（如手動觸發、單檔測試）使用；批次省重算靠呼叫端（見
     * {@code TradingRadarNotificationService#flushEvaluations}）自行對每個市場只組一次
     * {@link MarketSnapshot} 後改呼叫四參數版本。</p>
     */
    @Transactional(readOnly = true)
    public TradingRadarDto.StockDecision evaluateForNotification(
            String stockCode, String market, boolean held) {
        return evaluateForNotification(stockCode, market, held, buildMarketSnapshot(market));
    }

    /**
     * 批次版（Task 302）：呼叫端已組好 {@link MarketSnapshot}（每輪每市場一次），本方法只負責
     * 組單一標的的個股決策，不再重建大盤。
     */
    @Transactional(readOnly = true)
    public TradingRadarDto.StockDecision evaluateForNotification(
            String stockCode, String market, boolean held, MarketSnapshot snapshot) {
        return buildStock(new Target(stockCode, market, held), snapshot.regime(), snapshot.stale(),
                snapshot.decisionInstant(), new java.util.HashMap<>());
    }

    /**
     * 通知路徑的大盤快照組裝入口（Task 302）：依 {@code market} 組對應的那一組大盤，供呼叫端
     * 對每輪出現的每個市場只呼叫一次，取代逐檔 {@code evaluateForNotification} 各自重建大盤的
     * 既有浪費。台股改走 bounded 的 {@link TradingRadarMarketContextService#resolveMarket}
     * （不再抓新聞，見該方法）；美股維持既有 IXIC 組裝。{@link #buildMarket}／{@link #buildUsMarket}
     * 既有 catch 保證失敗回 {@code DATA_INCOMPLETE + stale=true} 而非拋出，故本方法本身不需要
     * 額外 try/catch——呼叫端（{@code flushEvaluations} 的 {@code computeIfAbsent}）位於逐檔
     * catch 之外，不容許例外逸出。
     */
    @Transactional(readOnly = true)
    public MarketSnapshot buildMarketSnapshot(String market) {
        Instant now = Instant.now();
        MarketState marketState;
        if (US_MARKET.equals(market)) {
            marketState = buildUsMarket(now);
        } else {
            TradingRadarMarketContextService.MarketContext context = marketContextService.resolveMarket(now);
            marketState = buildMarket(context, now);
        }
        return new MarketSnapshot(marketState.regime(), marketState.stale(), now);
    }

    private MarketState buildMarket(
            TradingRadarMarketContextService.MarketContext context,
            Instant decisionInstant) {
        try {
            List<TwseIndexDailyHistory> rows = twseRepo.findTopNByOrderByTradingDateDesc(241);
            if (context.marketAsOfDate() != null) {
                rows = rows.stream()
                        .filter(r -> !r.getTradingDate().isAfter(context.marketAsOfDate()))
                        .limit(241)
                        .toList();
            }
            List<BigDecimal> closes = rows.stream().map(TwseIndexDailyHistory::getClosePoint).toList();
            LocalDate currentTradingDay = currentTwTradingDay(decisionInstant);
            LocalDate latestEodDate = rows.isEmpty() ? null : rows.get(0).getTradingDate();
            boolean todayEodPresent = latestEodDate != null && latestEodDate.equals(currentTradingDay);

            Optional<PriceQueryService.LivePrice> liveOpt = priceQueryService.getLive(TAIEX_CODE, TW_MARKET);
            boolean liveFreshToday = !todayEodPresent && liveOpt.isPresent()
                    && liveOpt.get().tradingDate() != null
                    && currentTradingDay.toString().equals(liveOpt.get().tradingDate());

            BigDecimal price;
            BigDecimal changePercent;
            if (liveFreshToday) {
                price = liveOpt.get().price();
                changePercent = closes.isEmpty() ? null : changePercent(price, closes.get(0));
            } else {
                price = closes.isEmpty() ? null : closes.get(0);
                changePercent = closes.size() >= 2 ? changePercent(closes.get(0), closes.get(1)) : null;
            }

            TechnicalIndicatorService.FullIndicators ind = indicatorService.computeAll(TAIEX_CODE, TW_MARKET);
            TradingRadarRuleEngine.Confirmation c60 = ruleEngine.confirm(closes, 60);
            TradingRadarRuleEngine.Confirmation c240 = ruleEngine.confirm(closes, 240);
            TradingRadarRuleEngine.MarketResult result = ruleEngine.evaluateMarket(
                    new TradingRadarRuleEngine.MarketInput(
                            price,
                            changePercent,
                            indicators(ind),
                            c60,
                            c240,
                            context.completedMarketChangePercent(),
                            context.marketVolumeRatio(),
                            context.marketTurnoverRatio(),
                            context.nasdaqChangePercent(),
                            context.soxChangePercent(),
                            context.usTechCompositePercent(),
                            context.usTechAvailable()));

            // stale＝「完成日 K 未到今日」且「Redis 也無今日即時價」時才成立；任一者成立即非 stale（Task 228）。
            boolean stale = !todayEodPresent && !liveFreshToday;
            TaiexDisplayPriceService.DisplayQuote display = taiexDisplayPriceService.resolve();
            String asOf = display.tradingDate();
            String liveUpdatedAt = liveFreshToday ? liveOpt.get().updatedAt() : null;
            TradingRadarDto.MarketSummary summary = new TradingRadarDto.MarketSummary(
                    result.regime().name(),
                    regimeLabel(result.regime()),
                    result.score(),
                    result.regime() != TradingRadarRuleEngine.MarketRegime.DATA_INCOMPLETE,
                    stale,
                    asOf,
                    display.price(),
                    display.changePercent(),
                    display.quoteStatus(),
                    ind.weeklyMa(),
                    ind.monthlyMa(),
                    ind.quarterlyMa(),
                    ind.annualMa(),
                    ind.k(),
                    ind.d(),
                    c60.name(),
                    c240.name(),
                    result.reasons(),
                    result.risks(),
                    liveFreshToday,
                    liveUpdatedAt,
                    toDto(ind.extended()),
                    context.marketVolumeRatio(),
                    context.marketTurnoverRatio(),
                    context.marketAsOfDate() == null ? null : context.marketAsOfDate().toString(),
                    context.nasdaqChangePercent(),
                    context.soxChangePercent(),
                    context.usTechCompositePercent(),
                    context.usTechAsOfDate() == null ? null : context.usTechAsOfDate().toString(),
                    context.usTechAvailable());
            return new MarketState(summary, result.regime(), stale);
        } catch (Exception e) {
            log.warn("今日交易雷達：大盤資料組裝失敗", e);
            return incompleteMarket("讀取大盤資料失敗，所有個股暫停產生交易訊號。");
        }
    }

    /**
     * 美股個股的大盤情境（Task 294）：用那斯達克綜合指數（IXIC）自身技術面，而非台股加權指數——
     * 美股開盤時間與台股加權指數無直接對應關係，用 TAIEX 當美股的大盤沒有意義。
     *
     * <p>與 {@link #buildMarket} 各自獨立 try/catch，互不影響（294.2／(e) 的隔離要求）：IXIC 讀取失敗
     * 只讓美股個股停止產生訊號，不得拖垮台股組。</p>
     *
     * <p>{@code MarketInput} 的後六個參數（跨市場量能／美股科技日報酬）全部傳 {@code null}／{@code false}：
     * 那三欄（nasdaqChangePercent／soxChangePercent／usTechCompositePercent）的語意是「台股股票的跨市場
     * 領先訊號」，本身即為 IXIC 走勢的一部分，若原封不動餵給「大盤即是 IXIC」的美股組會重複計分。</p>
     */
    private MarketState buildUsMarket(Instant decisionInstant) {
        try {
            List<UsIndexDailyHistory> rows =
                    usIndexDailyHistoryRepo.findTopNByIndexCodeOrderByTradingDateDesc(IXIC_CODE, 241);
            List<BigDecimal> closes = rows.stream().map(UsIndexDailyHistory::getClosePoint).toList();
            BigDecimal price = closes.isEmpty() ? null : closes.get(0);
            BigDecimal changePercent = closes.size() >= 2 ? changePercent(closes.get(0), closes.get(1)) : null;

            TechnicalIndicatorService.FullIndicators ind = indicatorService.computeAllForNasdaq();
            TradingRadarRuleEngine.Confirmation c60 = ruleEngine.confirm(closes, 60);
            TradingRadarRuleEngine.Confirmation c240 = ruleEngine.confirm(closes, 240);
            TradingRadarRuleEngine.MarketResult result = ruleEngine.evaluateMarket(
                    new TradingRadarRuleEngine.MarketInput(
                            price,
                            changePercent,
                            indicators(ind),
                            c60,
                            c240,
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            false));

            LocalDate latestEodDate = rows.isEmpty() ? null : rows.get(0).getTradingDate();
            LocalDate mostRecentCompleted = mostRecentCompletedUsTradingDay(decisionInstant);
            boolean stale = latestEodDate == null || latestEodDate.isBefore(mostRecentCompleted);

            TradingRadarDto.MarketSummary summary = new TradingRadarDto.MarketSummary(
                    result.regime().name(),
                    regimeLabel(result.regime()),
                    result.score(),
                    result.regime() != TradingRadarRuleEngine.MarketRegime.DATA_INCOMPLETE,
                    stale,
                    latestEodDate == null ? null : latestEodDate.toString(),
                    price,
                    changePercent,
                    "CLOSE_PENDING",
                    ind.weeklyMa(),
                    ind.monthlyMa(),
                    ind.quarterlyMa(),
                    ind.annualMa(),
                    ind.k(),
                    ind.d(),
                    c60.name(),
                    c240.name(),
                    result.reasons(),
                    result.risks(),
                    false,
                    null,
                    toDto(ind.extended()),
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    false);
            return new MarketState(summary, result.regime(), stale);
        } catch (Exception e) {
            log.warn("今日交易雷達：美股（IXIC）大盤資料組裝失敗", e);
            return incompleteMarket("讀取美股大盤資料失敗，美股個股暫停產生交易訊號。");
        }
    }

    private TradingRadarDto.StockDecision buildStock(
            Target target,
            TradingRadarRuleEngine.MarketRegime marketRegime,
            boolean marketStale,
            Instant decisionInstant,
            Map<String, TradingRadarMarketContextService.FxContext> fxCache) {
        Optional<Stock> stock = stockRepo.findByCodeAndMarket(target.code(), target.market());
        String name = stock.map(Stock::getName)
                .filter(n -> n != null && !n.isBlank())
                .orElse(target.code());
        String assetClass = assetClassifier.classifyStock(
                target.code(), target.market(), stock.map(Stock::getAssetClass).orElse(null));
        TradingRadarRuleEngine.InstrumentType instrumentType = AssetClassifier.BOND.equals(assetClass)
                ? TradingRadarRuleEngine.InstrumentType.BOND
                : TradingRadarRuleEngine.InstrumentType.EQUITY;
        try {
            List<StockPriceHistory> rows = priceHistoryRepo.findRecentN(target.code(), target.market(), 241);
            Optional<PriceQueryService.LivePrice> liveOpt = priceQueryService.getLive(target.code(), target.market());
            Optional<PriceQueryService.LivePrice> displayOpt =
                    priceQueryService.getDisplayPrice(target.code(), target.market());
            // price 只依賴 rows 與 liveOpt，不依賴組裝結果；因組裝需要它作為乖離／52 週位置的分子，
            // 故先於 prepareTechnicalData 求值（順序調整不改變任何取值，Task 273 的 273.2b）。
            BigDecimal price = liveOpt.map(PriceQueryService.LivePrice::price)
                    .orElseGet(() -> rows.isEmpty() ? null : rows.get(0).getClosePrice());
            RadarInputAssembler.Assembled technical = prepareTechnicalData(
                    target, rows, liveOpt, price, decisionInstant);
            List<BigDecimal> closes = technical.completedCloses();
            BigDecimal displayPrice = displayOpt.map(PriceQueryService.LivePrice::price).orElse(null);
            BigDecimal displayChangePercent = displayOpt.map(PriceQueryService.LivePrice::changePercent)
                    .orElse(null);
            if (displayChangePercent == null && displayPrice != null && closes.size() >= 2) {
                BigDecimal previous = previousCompletedClose(rows, displayOpt.orElse(null));
                displayChangePercent = changePercent(displayPrice, previous);
            }
            // 組裝結果一律取自 RadarInputAssembler，與回測共用同一份（Task 273 的 273.2b）。
            BigDecimal ruleChangePercent = technical.ruleChangePercent();
            if (ruleChangePercent == null) ruleChangePercent = displayChangePercent;

            TechnicalIndicatorService.FullIndicators ind = technical.indicators();
            TradingRadarRuleEngine.Confirmation c20 = technical.ma20Confirmation();
            TradingRadarRuleEngine.Confirmation c60 = technical.ma60Confirmation();
            TradingRadarRuleEngine.Confirmation c240 = technical.ma240Confirmation();
            String currency = underlyingCurrencyOf(stock.orElse(null), target.market());
            TradingRadarMarketContextService.FxContext fx = TWD.equals(currency)
                    ? TradingRadarMarketContextService.FxContext.EMPTY
                    : fxCache.computeIfAbsent(currency, c -> marketContextService.resolveFx(c, decisionInstant));
            BigDecimal fxPct = fx.percentile();
            BigDecimal ma60Bias = technical.ma60BiasPercent();
            BigDecimal ma240Bias = technical.ma240BiasPercent();
            BigDecimal week52Pos = technical.week52Position();
            BigDecimal etfPremiumPct = etfPremiumPct(target.code(), target.market(), decisionInstant);
            BigDecimal etfPremiumPercentile = etfPremiumPercentile(target.code(), target.market(), etfPremiumPct);
            FundamentalAnalysisService.Resolved fundamental = fundamentalAnalysisService.resolve(
                    target.code(), name, target.market(), decisionInstant);
            TradingRadarRuleEngine.StockResult result = ruleEngine.evaluateStock(
                    new TradingRadarRuleEngine.StockInput(
                            target.held(),
                            price,
                            ruleChangePercent,
                            technical.completedChangePercent(),
                            indicators(ind),
                            ind.previousK(),
                            ind.previousD(),
                            c20,
                            c60,
                            c240,
                            instrumentType,
                            marketRegime,
                            marketStale,
                            fxPct,
                            ma60Bias,
                            technical.ma60BiasPercentile(),
                            ma240Bias,
                            week52Pos,
                            technical.kdBandWidthPercent(),
                            etfPremiumPct,
                            etfPremiumPercentile,
                            ind.weeklyMa(),
                            assembler.extendedIndicators(ind.extended()),
                            technical.volumeRatio(),
                            fundamental.input()));

            List<String> reasons = new ArrayList<>();
            if (technical.distributionAdjusted()) {
                reasons.add("MA／KD、兩日確認與規則漲跌已使用還原權息／分割價，避免把配息缺口或分割跳空誤判為趨勢跌破。 ");
            }
            reasons.addAll(result.reasons());
            List<String> risks = new ArrayList<>(result.risks());
            List<String> shortReasons = new ArrayList<>();
            if (technical.distributionAdjusted()) {
                shortReasons.add("MA／KD、擴充指標與相對量已使用同一份還原權息／分割序列。 ");
            }
            shortReasons.addAll(result.shortReasons());
            List<String> shortRisks = new ArrayList<>(result.shortRisks());
            if (!TWD.equals(currency) && fx.asOfDate() == null) {
                String missingFx = "精確完成日匯率不可得，外幣債券 ETF 的匯率因子本日缺值。 ";
                risks.add(missingFx);
                shortRisks.add(missingFx);
            }

            String updatedAt = displayOpt.map(PriceQueryService.LivePrice::updatedAt).orElse(null);
            String asOf = displayOpt.map(PriceQueryService.LivePrice::tradingDate)
                    .orElseGet(() -> rows.isEmpty() ? null : rows.get(0).getTradingDate().toString());
            String quoteStatus = displayOpt.map(PriceQueryService.LivePrice::quoteStatus).orElse("CLOSE_PENDING");
            return new TradingRadarDto.StockDecision(
                    target.code(),
                    name,
                    target.market(),
                    assetClass,
                    technical.distributionAdjusted(),
                    target.held(),
                    result.action().name(),
                    actionLabel(result.action()),
                    result.score(),
                    result.counterTrend().state().name(),
                    counterTrendLabel(result.counterTrend().state()),
                    result.counterTrend().reasons(),
                    result.counterTrend().risks(),
                    result.action() != TradingRadarRuleEngine.Action.NO_TRADE,
                    displayPrice,
                    displayChangePercent,
                    quoteStatus,
                    updatedAt,
                    asOf,
                    ind.monthlyMa(),
                    ind.quarterlyMa(),
                    ind.annualMa(),
                    ind.k(),
                    ind.d(),
                    c20.name(),
                    c60.name(),
                    c240.name(),
                    fxPct,
                    currency,
                    List.copyOf(reasons),
                    List.copyOf(risks),
                    result.kdHeat().name(),
                    result.timingState().name(),
                    timingLabel(result.timingState()),
                    ma60Bias,
                    week52Pos,
                    ind.weeklyMa(),
                    etfPremiumPct,
                    etfPremiumPercentile,
                    toDto(ind.extended()),
                    result.shortAction().name(),
                    actionLabel(result.shortAction()),
                    result.shortScore(),
                    List.copyOf(shortReasons),
                    List.copyOf(shortRisks),
                    result.horizonConflict(),
                    technical.volumeRatio(),
                    fx.asOfDate() == null ? null : fx.asOfDate().toString(),
                    result.profitTakingConfirmed(),
                    fundamental.snapshot());
        } catch (Exception e) {
            log.warn("今日交易雷達：{} {} 組裝失敗", target.market(), target.code(), e);
            return incompleteStock(target, name, assetClass, "讀取個股資料失敗，該檔今日不交易。");
        }
    }

    /**
     * 組裝技術面欄位。**實際邏輯全在 {@link RadarInputAssembler}**，本方法只負責 production 專屬的
     * 「當日 live K 併入」與事件查詢；回測走同一支 assembler，故兩者不可能漂移（Task 273 的 273.2b）。
     */
    private RadarInputAssembler.Assembled prepareTechnicalData(
            Target target,
            List<StockPriceHistory> completedRows,
            Optional<PriceQueryService.LivePrice> liveOpt,
            BigDecimal price,
            Instant decisionInstant) {
        List<StockPriceHistory> combined = new ArrayList<>(completedRows);
        boolean liveAdded = liveOpt
                .filter(live -> shouldAddLiveRow(completedRows, live, decisionInstant, target.market()))
                .isPresent();
        if (liveAdded) {
            combined.add(0, liveRow(target, liveOpt.orElseThrow()));
        }
        if (combined.isEmpty()) return RadarInputAssembler.Assembled.EMPTY;

        LocalDate fromDate = combined.get(combined.size() - 1).getTradingDate();
        LocalDate toDate = combined.get(0).getTradingDate();
        return assembler.assemble(
                combined,
                dividendHistoryRepo.findAdjustmentEvents(target.code(), target.market(), fromDate, toDate),
                liveAdded,
                completedRows.size(),
                price);
    }

    /**
     * live K 是否併入序列：tradingDate 須等於「{@code market} 對應時區的今日」，且尚未等於完成列最新一筆。
     *
     * <p>「今日」必須以標的市場時區解讀 {@code decisionInstant}，不得用固定台北時區——美股在台北
     * 00:00–05:00 正是美東前一交易日下半場至收盤，此時美東日期＝台北日期−1，用台北時區比對
     * 恆失敗（Task 297，同類修法見 {@link TechnicalIndicatorService#computeAll}／Task 252）。
     * 刻意不用 {@link MarketZones#today}（內部讀系統時鐘）：本方法所有時間判斷須全部來自顯式傳入的
     * {@code decisionInstant}，維持背景重算（{@code recomputeAndStoreForOwner}）與 HTTP 路徑
     * 共用同一 {@code decisionInstant} 語意的純度契約。</p>
     *
     * <p>package-private 供同 package 測試以寫死的 {@code Instant} 直接呼叫——{@code assemble}／
     * {@code evaluateForNotification} 的 {@code decisionInstant = Instant.now()} 無 Clock 注入，
     * 服務層級測試無法控制時刻。</p>
     */
    boolean shouldAddLiveRow(
            List<StockPriceHistory> completedRows,
            PriceQueryService.LivePrice live,
            Instant decisionInstant,
            String market) {
        if (live.tradingDate() == null || live.price() == null) return false;
        LocalDate liveDate;
        try {
            liveDate = LocalDate.parse(live.tradingDate());
        } catch (Exception e) {
            return false;
        }
        LocalDate today = decisionInstant.atZone(MarketZones.resolve(market)).toLocalDate();
        return liveDate.equals(today)
                && (completedRows.isEmpty()
                    || !liveDate.equals(completedRows.get(0).getTradingDate()));
    }

    private StockPriceHistory liveRow(Target target, PriceQueryService.LivePrice live) {
        BigDecimal price = live.price();
        return StockPriceHistory.builder()
                .stockCode(target.code())
                .market(target.market())
                .tradingDate(LocalDate.parse(live.tradingDate()))
                .openPrice(live.openPrice() != null ? live.openPrice() : price)
                .highPrice(live.highPrice() != null ? live.highPrice() : price)
                .lowPrice(live.lowPrice() != null ? live.lowPrice() : price)
                .closePrice(price)
                .volume(live.volume())
                .build();
    }

    /**
     * live tradingDate 若等於最新完成日 K，previous close 應取 rows[1]；
     * 盤中 live date 尚未入庫時，前一收盤就是 rows[0]。
     */
    private BigDecimal previousCompletedClose(List<StockPriceHistory> rows, PriceQueryService.LivePrice live) {
        if (rows.isEmpty()) return null;
        // 無 live 時 current=rows[0]，以及 live 已是同一完成日 K 時，前一收盤皆為 rows[1]。
        if (live == null || (live.tradingDate() != null
                && live.tradingDate().equals(rows.get(0).getTradingDate().toString()))) {
            return rows.size() >= 2 ? rows.get(1).getClosePrice() : null;
        }
        // live 是尚未入庫的盤中價（或來源未帶日期）時，rows[0] 才是昨收。
        return rows.get(0).getClosePrice();
    }

    private void loadLatestHoldings(Map<String, Target> targets, Set<String> skippedNonTw, Long ownerId) {
        Optional<AssetSnapshot> snapshot = ownerId == null
                ? snapshotRepo.findLatestWithStocks()
                : snapshotRepo.findLatestWithStocksByOwnerUserId(ownerId);
        if (snapshot.isEmpty()) return;
        for (StockHolding holding : snapshot.get().getStocks()) {
            if (holding.getStockCode() == null || holding.getMarket() == null) continue;
            if (holding.getShares() == null || holding.getShares().signum() <= 0) continue;
            addTarget(targets, skippedNonTw, holding.getStockCode(), holding.getMarket(), true);
        }
    }

    private void loadWatchList(Map<String, Target> targets, Set<String> skippedNonTw, Long ownerId) {
        List<Object[]> rows = ownerId == null
                ? alertRepo.findDistinctStockCodeMarket()
                : alertRepo.findDistinctStockCodeMarketByOwnerUserId(ownerId);
        for (Object[] row : rows) {
            addTarget(targets, skippedNonTw, (String) row[0], (String) row[1], false);
        }
    }

    private void addTarget(Map<String, Target> targets, Set<String> skippedNonTw,
                           String rawCode, String rawMarket, boolean held) {
        if (rawCode == null || rawMarket == null) return;
        String code = rawCode.trim().toUpperCase();
        String market = rawMarket.trim();
        if (code.isEmpty() || market.isEmpty()) return;
        String key = code + '\0' + market;
        if (!TW_MARKET.equals(market) && !US_MARKET.equals(market)) {
            skippedNonTw.add(key);
            return;
        }
        if (TAIEX_CODE.equals(code)) return;
        Target existing = targets.get(key);
        targets.put(key, new Target(code, market, held || (existing != null && existing.held())));
    }

    /** 委派 {@link RadarInputAssembler}，避免同一段換算在兩處各自漂移（Task 273）。 */
    private TradingRadarRuleEngine.Indicators indicators(TechnicalIndicatorService.FullIndicators ind) {
        return assembler.indicators(ind);
    }

    /** 委派 {@link RadarInputAssembler}，避免同一段換算在兩處各自漂移（Task 273）。 */
    private BigDecimal changePercent(BigDecimal current, BigDecimal previous) {
        return assembler.changePercent(current, previous);
    }

    /**
     * 判定標的的底層資產幣別（Requirement 47）。
     *
     * <p>顯式欄位優先，null 時依 market 推斷。<b>刻意不以名稱字串比對</b>（如「含美債二字」）——
     * 那種做法在標的更名或新增時會靜默失效：匯率因子突然變 null、分數跳動但不報任何錯。</p>
     */
    private String underlyingCurrencyOf(Stock stock, String market) {
        if (stock != null && stock.getUnderlyingCurrency() != null
                && !stock.getUnderlyingCurrency().isBlank()) {
            return stock.getUnderlyingCurrency().trim().toUpperCase();
        }
        if ("美股".equals(market)) return "USD";
        if ("英股".equals(market)) return "GBP";
        return TWD;
    }

    /**
     * 現行 ETF 折溢價（%）。Redis 即時值優先，但<b>須驗 {@code navAsOf} 為最近一個交易日</b>；
     * 不新鮮則退回 {@code etf_nav_history} 最新一筆。非 ETF 或查無回 {@code null}。
     *
     * <p>驗新鮮度的理由：{@code price:etfnav:*} 的 TTL 為 96 小時，不驗日期會讓最多 4 天前的折溢價
     * 觸發硬否決。本專案剛為同類問題做過修正（大盤即時點位的日期驗證）。</p>
     *
     * <p><b>禁止由市價與淨值反推</b>（Task 259）：{@code premiumDiscountPct} 為 null 就是缺值。</p>
     */
    private BigDecimal etfPremiumPct(String code, String market, Instant decisionInstant) {
        // 美股 ETF 折溢價因子明確排除（Task 294.5）：etf_nav_history 現況已有美股列（既有 EtfNavPoller
        // 對持有／觀察的美股 ETF 逐檔打 Yahoo quoteSummary 寫入），若不加此短路，移除美股 filter 後
        // 會對美股 ETF 回傳非 null 折溢價，讓 ETF_PREMIUM_EXPENSIVE 硬否決誤套用到美股。
        // 本次不查 etf_nav_history、不查 Redis，與台股既有查詢路徑完全不重疊。
        if (US_MARKET.equals(market)) return null;
        try {
            var live = priceQueryService.getEtfNav(code, market);
            if (live.isPresent() && live.get().premiumDiscountPct() != null
                    && isFreshNav(live.get().navAsOf(), decisionInstant)) {
                return live.get().premiumDiscountPct();
            }
            List<BigDecimal> recent = etfNavHistoryRepo.findRecentPremiumPct(
                    code, market, org.springframework.data.domain.PageRequest.of(0, 1));
            return recent.isEmpty() ? null : recent.get(0);
        } catch (Exception e) {
            log.warn("ETF 折溢價取得失敗（{}／{}）：{}", code, market, e.getMessage());
            return null;
        }
    }

    /** Redis 折溢價的 navAsOf 須等於當前台股交易日，否則視為不新鮮。 */
    private boolean isFreshNav(String navAsOf, Instant decisionInstant) {
        if (navAsOf == null || navAsOf.isBlank()) return false;
        try {
            return LocalDate.parse(navAsOf.substring(0, 10)).equals(currentTwTradingDay(decisionInstant));
        } catch (Exception e) {
            return false;
        }
    }

    /** 現行折溢價在該 ETF 自身歷史分布中的百分位（0–100）；樣本不足或非 ETF 回 null。 */
    private BigDecimal etfPremiumPercentile(String code, String market, BigDecimal current) {
        if (current == null) return null;
        try {
            List<BigDecimal> history = etfNavHistoryRepo.findRecentPremiumPct(
                    code, market,
                    org.springframework.data.domain.PageRequest.of(0, ETF_PREMIUM_LOOKBACK_DAYS));
            if (history.size() < ETF_PREMIUM_MIN_SAMPLES) return null;
            long atOrBelow = history.stream().filter(v -> v.compareTo(current) <= 0).count();
            return BigDecimal.valueOf(100.0 * atOrBelow / history.size())
                    .setScale(1, RoundingMode.HALF_UP);
        } catch (Exception e) {
            log.warn("ETF 折溢價分位計算失敗（{}／{}）：{}", code, market, e.getMessage());
            return null;
        }
    }

    /**
     * 指標服務的擴充指標 → DTO（Task 281）。
     *
     * <p><b>只搬這 14 個值。</b>{@code FullIndicators} 的 MA／K／D 一律維持既有取法
     *（{@code ind.monthlyMa()} 等），不得改由這裡供給——同一列出現兩個 MA 來源就是漂移的開端。</p>
     */
    private static TradingRadarDto.ExtendedIndicators toDto(
            TechnicalIndicatorService.ExtendedIndicators e) {
        if (e == null) return null;
        return new TradingRadarDto.ExtendedIndicators(
                e.j9(), e.k3d2(), e.rsv(),
                e.ema12(), e.ema26(), e.dif(), e.macd(), e.osc(),
                e.rsi5(), e.rsi10(),
                e.bias10(), e.bias20(), e.b10b20(),
                e.wr9());
    }

    private static Integer bestScore(TradingRadarDto.StockDecision decision) {
        Integer shortScore = decision.shortScore();
        Integer mediumScore = decision.score();
        if (shortScore == null) return mediumScore;
        if (mediumScore == null) return shortScore;
        return Math.max(shortScore, mediumScore);
    }

    private MarketState incompleteMarket(String message) {
        TradingRadarDto.MarketSummary summary = new TradingRadarDto.MarketSummary(
                TradingRadarRuleEngine.MarketRegime.DATA_INCOMPLETE.name(),
                regimeLabel(TradingRadarRuleEngine.MarketRegime.DATA_INCOMPLETE),
                null,
                false,
                true,
                null,
                null, null, "CLOSE_PENDING",
                null, null, null, null, null, null,
                TradingRadarRuleEngine.Confirmation.UNAVAILABLE.name(),
                TradingRadarRuleEngine.Confirmation.UNAVAILABLE.name(),
                List.of(),
                List.of(message),
                false,
                null,
                null);
        // 讀不到大盤時保守視為 stale：買進閘門一律關閉。
        return new MarketState(summary, TradingRadarRuleEngine.MarketRegime.DATA_INCOMPLETE, true);
    }

    private TradingRadarDto.StockDecision incompleteStock(
            Target target, String name, String assetClass, String message) {
        return new TradingRadarDto.StockDecision(
                target.code(), name, target.market(), assetClass, false, target.held(),
                TradingRadarRuleEngine.Action.NO_TRADE.name(),
                actionLabel(TradingRadarRuleEngine.Action.NO_TRADE),
                null,
                TradingRadarRuleEngine.CounterTrendState.NONE.name(),
                counterTrendLabel(TradingRadarRuleEngine.CounterTrendState.NONE),
                List.of(), List.of(),
                false,
                null, null, "CLOSE_PENDING", null, null,
                null, null, null, null, null,
                TradingRadarRuleEngine.Confirmation.UNAVAILABLE.name(),
                TradingRadarRuleEngine.Confirmation.UNAVAILABLE.name(),
                TradingRadarRuleEngine.Confirmation.UNAVAILABLE.name(),
                null,
                null,
                List.of(), List.of(message),
                TradingRadarRuleEngine.KdHeat.NORMAL.name(),
                TradingRadarRuleEngine.TimingState.NEUTRAL.name(),
                timingLabel(TradingRadarRuleEngine.TimingState.NEUTRAL),
                null, null, null, null, null, null);
    }

    private String regimeLabel(TradingRadarRuleEngine.MarketRegime regime) {
        return switch (regime) {
            case RISK_ON -> "偏多／可尋找機會";
            case NEUTRAL -> "中性／等待確認";
            case RISK_OFF -> "偏空／降低風險";
            case DATA_INCOMPLETE -> "資料不足／今日不交易";
        };
    }

    /** 進場時機的顯示文案（Task 264）。 */
    public static String timingLabel(TradingRadarRuleEngine.TimingState state) {
        if (state == null) return "—";
        return switch (state) {
            case EXTREME_OVERBOUGHT -> "極端超買";
            case OVERBOUGHT -> "偏貴";
            case NEUTRAL -> "中性";
            case OVERSOLD -> "偏便宜";
            case EXTREME_OVERSOLD -> "極端超賣";
        };
    }

    public static String actionLabel(TradingRadarRuleEngine.Action action) {
        return switch (action) {
            case BUY_CANDIDATE -> "買進候選";
            case ADD_CANDIDATE -> "加碼候選";
            case TRIAL_BUY -> "分批試單";
            case HOLD -> "續抱";
            case WATCH -> "觀察";
            case HOLD_CAUTION -> "續抱但提高警戒";
            case WAIT -> "觀望";
            case REDUCE_CANDIDATE -> "減碼候選";
            case EXIT_CANDIDATE -> "出場候選";
            case AVOID -> "暫不買進";
            case NO_TRADE -> "今日不交易";
        };
    }

    public static String counterTrendLabel(TradingRadarRuleEngine.CounterTrendState state) {
        return switch (state) {
            case NONE -> "—";
            case OVERSOLD_WATCH -> "超跌觀察";
            case TRIAL_CANDIDATE -> "逆勢試單候選";
        };
    }
}
