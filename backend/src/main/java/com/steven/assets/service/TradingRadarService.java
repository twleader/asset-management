package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.Stock;
import com.steven.assets.repository.EtfNavHistoryRepository;
import com.steven.assets.repository.ExchangeRateHistoryRepository;
import com.steven.assets.model.ExchangeRateHistory;
import com.steven.assets.model.StockHolding;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.StockAlertRepository;
import com.steven.assets.repository.StockDividendHistoryRepository;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import com.steven.assets.security.CurrentUserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
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
    private static final String TW_MARKET = "台股";
    private static final String TAIEX_CODE = "0000";

    /**
     * 匯率分位的回看期（Requirement 47）。
     *
     * <p>取五年的理由：同一天（2026-07-17、USD/TWD 32.23）在不同回看期的分位差異極大——
     * 一年 99.2、三年 73.2、五年 83.6、全歷史 91.8。一年過短會使因子在趨勢行情中長期釘在
     * 極值而失去區辨力；五年（實測 1247 筆、區間 27.53–33.14）涵蓋台幣由強轉弱的完整週期，
     * 代表性足夠。調整此值須同步更新 Requirement 47 的記載。</p>
     */
    private static final int FX_LOOKBACK_YEARS = 5;
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
    private final StockPriceHistoryRepository priceHistoryRepo;
    private final StockDividendHistoryRepository dividendHistoryRepo;
    private final PriceQueryService priceQueryService;
    private final AssetSnapshotRepository snapshotRepo;
    private final StockAlertRepository alertRepo;
    private final StockRepository stockRepo;
    private final MarketDataService marketDataService;
    private final ExchangeRateHistoryRepository exchangeRateRepo;
    private final EtfNavHistoryRepository etfNavHistoryRepo;
    private final TradingRadarSnapshotStore snapshotStore;
    private final CurrentUserContext currentUserContext;

    /** @param stale 大盤最新完成日 K 不是當前台股交易日（Task 217.1）。 */
    private record MarketState(
            TradingRadarDto.MarketSummary summary,
            TradingRadarRuleEngine.MarketRegime regime,
            boolean stale
    ) {}

    private record Target(String code, String market, boolean held) {}

    /**
     * 當前台股交易日：今天是交易日就取今天，否則往回找最近一個交易日。
     * 用於判斷大盤資料是否停在更早的交易日；沿用既有交易日曆，不自建假日表。
     */
    private LocalDate currentTwTradingDay() {
        LocalDate day = LocalDate.now(TAIPEI);
        for (int i = 0; i < 14; i++) {
            if (marketDataService.isTradingDay(TW_MARKET, day)) return day;
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
        MarketState market = buildMarket();
        Map<String, Target> targets = new LinkedHashMap<>();
        Set<String> skippedNonTw = new HashSet<>();
        loadLatestHoldings(targets, skippedNonTw, ownerId);
        loadWatchList(targets, skippedNonTw, ownerId);

        List<TradingRadarDto.StockDecision> decisions = targets.values().stream()
                .filter(t -> TW_MARKET.equals(t.market()) && !TAIEX_CODE.equals(t.code()))
                .sorted(Comparator.comparing(Target::code))
                .map(t -> buildStock(t, market.regime(), market.stale()))
                .toList();

        return new TradingRadarDto.Response(
                TradingRadarRuleEngine.RULE_VERSION,
                ZonedDateTime.now(TAIPEI).toOffsetDateTime().toString(),
                market.summary(),
                decisions,
                skippedNonTw.size());
    }

    /** 背景通知評估共用同一份 V4 組裝，不依賴 HTTP owner filter。 */
    @Transactional(readOnly = true)
    public TradingRadarDto.StockDecision evaluateForNotification(
            String stockCode, String market, boolean held) {
        MarketState marketState = buildMarket();
        return buildStock(new Target(stockCode, market, held), marketState.regime(), marketState.stale());
    }

    private MarketState buildMarket() {
        try {
            List<TwseIndexDailyHistory> rows = twseRepo.findTopNByOrderByTradingDateDesc(241);
            List<BigDecimal> closes = rows.stream().map(TwseIndexDailyHistory::getClosePoint).toList();
            LocalDate currentTradingDay = currentTwTradingDay();
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
                            c240));

            // stale＝「完成日 K 未到今日」且「Redis 也無今日即時價」時才成立；任一者成立即非 stale（Task 228）。
            boolean stale = !todayEodPresent && !liveFreshToday;
            String asOf = rows.isEmpty() ? null : rows.get(0).getTradingDate().toString();
            String liveUpdatedAt = liveFreshToday ? liveOpt.get().updatedAt() : null;
            TradingRadarDto.MarketSummary summary = new TradingRadarDto.MarketSummary(
                    result.regime().name(),
                    regimeLabel(result.regime()),
                    result.score(),
                    result.regime() != TradingRadarRuleEngine.MarketRegime.DATA_INCOMPLETE,
                    stale,
                    asOf,
                    price,
                    changePercent,
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
                    toDto(ind.extended()));
            return new MarketState(summary, result.regime(), stale);
        } catch (Exception e) {
            log.warn("今日交易雷達：大盤資料組裝失敗", e);
            return incompleteMarket("讀取大盤資料失敗，所有個股暫停產生交易訊號。");
        }
    }

    private TradingRadarDto.StockDecision buildStock(
            Target target,
            TradingRadarRuleEngine.MarketRegime marketRegime,
            boolean marketStale) {
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
            // price 只依賴 rows 與 liveOpt，不依賴組裝結果；因組裝需要它作為乖離／52 週位置的分子，
            // 故先於 prepareTechnicalData 求值（順序調整不改變任何取值，Task 273 的 273.2b）。
            BigDecimal price = liveOpt.map(PriceQueryService.LivePrice::price)
                    .orElseGet(() -> rows.isEmpty() ? null : rows.get(0).getClosePrice());
            RadarInputAssembler.Assembled technical = prepareTechnicalData(target, rows, liveOpt, price);
            List<BigDecimal> closes = technical.completedCloses();
            BigDecimal displayChangePercent = liveOpt.map(PriceQueryService.LivePrice::changePercent)
                    .orElse(null);
            if (displayChangePercent == null && price != null && closes.size() >= 2) {
                BigDecimal previous = previousCompletedClose(rows, liveOpt.orElse(null));
                displayChangePercent = changePercent(price, previous);
            }
            // 組裝結果一律取自 RadarInputAssembler，與回測共用同一份（Task 273 的 273.2b）。
            BigDecimal ruleChangePercent = technical.ruleChangePercent();
            if (ruleChangePercent == null) ruleChangePercent = displayChangePercent;

            TechnicalIndicatorService.FullIndicators ind = technical.indicators();
            TradingRadarRuleEngine.Confirmation c20 = technical.ma20Confirmation();
            TradingRadarRuleEngine.Confirmation c60 = technical.ma60Confirmation();
            TradingRadarRuleEngine.Confirmation c240 = technical.ma240Confirmation();
            String currency = underlyingCurrencyOf(stock.orElse(null), target.market());
            BigDecimal fxPct = fxPercentile(stock.orElse(null), target.market());
            BigDecimal ma60Bias = technical.ma60BiasPercent();
            BigDecimal ma240Bias = technical.ma240BiasPercent();
            BigDecimal week52Pos = technical.week52Position();
            BigDecimal etfPremiumPct = etfPremiumPct(target.code(), target.market());
            BigDecimal etfPremiumPercentile = etfPremiumPercentile(target.code(), target.market(), etfPremiumPct);
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
                            ma240Bias,
                            week52Pos,
                            technical.kdBandWidthPercent(),
                            etfPremiumPct,
                            etfPremiumPercentile));

            List<String> reasons = new ArrayList<>();
            if (technical.distributionAdjusted()) {
                reasons.add("MA／KD、兩日確認與規則漲跌已使用還原權息／分割價，避免把配息缺口或分割跳空誤判為趨勢跌破。 ");
            }
            reasons.addAll(result.reasons());

            String updatedAt = liveOpt.map(PriceQueryService.LivePrice::updatedAt).orElse(null);
            String asOf = rows.isEmpty() ? null : rows.get(0).getTradingDate().toString();
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
                    price,
                    displayChangePercent,
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
                    result.risks(),
                    result.kdHeat().name(),
                    result.timingState().name(),
                    timingLabel(result.timingState()),
                    ma60Bias,
                    week52Pos,
                    ind.weeklyMa(),
                    etfPremiumPct,
                    etfPremiumPercentile,
                    toDto(ind.extended()));
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
            BigDecimal price) {
        List<StockPriceHistory> combined = new ArrayList<>(completedRows);
        boolean liveAdded = liveOpt.filter(live -> shouldAddLiveRow(completedRows, live)).isPresent();
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

    private boolean shouldAddLiveRow(
            List<StockPriceHistory> completedRows,
            PriceQueryService.LivePrice live) {
        if (live.tradingDate() == null || live.price() == null) return false;
        LocalDate liveDate;
        try {
            liveDate = LocalDate.parse(live.tradingDate());
        } catch (Exception e) {
            return false;
        }
        LocalDate today = LocalDate.now(TAIPEI);
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
        if (!TW_MARKET.equals(market)) {
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
     * 底層幣別對台幣的五年期分位（0–100）；台幣資產或資料不足時回 null 交由權重重分配吸收。
     *
     * <p>中價取 {@code (buy_rate + sell_rate) / 2}。<b>取不到當日匯率時回 null 而非沿用前值</b>——
     * 匯率在假日不變動，硬代會使分位在連假期間失真。</p>
     */
    private BigDecimal fxPercentile(Stock stock, String market) {
        String currency = underlyingCurrencyOf(stock, market);
        if (TWD.equals(currency)) return null;
        try {
            LocalDate today = LocalDate.now(TAIPEI);
            List<ExchangeRateHistory> rows = exchangeRateRepo
                    .findByCurrencyAndRateDateBetweenOrderByRateDateAsc(
                            currency, today.minusYears(FX_LOOKBACK_YEARS), today);
            if (rows == null || rows.size() < 60) return null;

            List<BigDecimal> mids = new ArrayList<>();
            BigDecimal latest = null;
            for (ExchangeRateHistory r : rows) {
                BigDecimal mid = midRate(r);
                if (mid == null) continue;
                mids.add(mid);
                latest = mid;
            }
            if (latest == null || mids.size() < 60) return null;

            final BigDecimal reference = latest;
            long atOrBelow = mids.stream().filter(m -> m.compareTo(reference) <= 0).count();
            return BigDecimal.valueOf(100.0 * atOrBelow / mids.size())
                    .setScale(1, RoundingMode.HALF_UP);
        } catch (Exception e) {
            log.warn("匯率分位計算失敗（{}／{}）：{}", currency, market, e.getMessage());
            return null;
        }
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
    private BigDecimal etfPremiumPct(String code, String market) {
        try {
            var live = priceQueryService.getEtfNav(code, market);
            if (live.isPresent() && live.get().premiumDiscountPct() != null
                    && isFreshNav(live.get().navAsOf())) {
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
    private boolean isFreshNav(String navAsOf) {
        if (navAsOf == null || navAsOf.isBlank()) return false;
        try {
            return LocalDate.parse(navAsOf.substring(0, 10)).equals(currentTwTradingDay());
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

    private BigDecimal midRate(ExchangeRateHistory row) {
        if (row == null) return null;
        BigDecimal buy = row.getBuyRate();
        BigDecimal sell = row.getSellRate();
        if (buy == null && sell == null) return null;
        if (buy == null) return sell;
        if (sell == null) return buy;
        return buy.add(sell).divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
    }

    /**
     * 指標服務的擴充指標 → DTO（Task 280）。
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

    private MarketState incompleteMarket(String message) {
        TradingRadarDto.MarketSummary summary = new TradingRadarDto.MarketSummary(
                TradingRadarRuleEngine.MarketRegime.DATA_INCOMPLETE.name(),
                regimeLabel(TradingRadarRuleEngine.MarketRegime.DATA_INCOMPLETE),
                null,
                false,
                true,
                null,
                null, null, null, null, null, null, null, null,
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
                null, null, null, null,
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
