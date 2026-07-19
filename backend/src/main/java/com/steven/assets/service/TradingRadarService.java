package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.Stock;
import com.steven.assets.model.StockHolding;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.StockAlertRepository;
import com.steven.assets.repository.StockDividendHistoryRepository;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private final TradingRadarRuleEngine ruleEngine;
    private final TechnicalIndicatorService indicatorService;
    private final DistributionAdjustedPriceService adjustedPriceService;
    private final AssetClassifier assetClassifier;
    private final TwseIndexDailyHistoryRepository twseRepo;
    private final StockPriceHistoryRepository priceHistoryRepo;
    private final StockDividendHistoryRepository dividendHistoryRepo;
    private final PriceQueryService priceQueryService;
    private final AssetSnapshotRepository snapshotRepo;
    private final StockAlertRepository alertRepo;
    private final StockRepository stockRepo;
    private final MarketDataService marketDataService;

    /** @param stale 大盤最新完成日 K 不是當前台股交易日（Task 217.1）。 */
    private record MarketState(
            TradingRadarDto.MarketSummary summary,
            TradingRadarRuleEngine.MarketRegime regime,
            boolean stale
    ) {}

    private record Target(String code, String market, boolean held) {}

    private record TechnicalData(
            TechnicalIndicatorService.FullIndicators indicators,
            List<BigDecimal> completedCloses,
            BigDecimal previousAdjustedClose,
            BigDecimal completedChangePercent,
            boolean distributionAdjusted
    ) {}

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
        MarketState market = buildMarket();
        Map<String, Target> targets = new LinkedHashMap<>();
        Set<String> skippedNonTw = new HashSet<>();
        loadLatestHoldings(targets, skippedNonTw);
        loadWatchList(targets, skippedNonTw);

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
            BigDecimal price = rows.isEmpty() ? null : rows.get(0).getClosePoint();
            BigDecimal changePercent = closes.size() >= 2 ? changePercent(closes.get(0), closes.get(1)) : null;
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

            // 大盤只有完成日資料（0000 被排除於即時抓價之外），停在更早的交易日即為 stale（Task 217.1）。
            boolean stale = rows.isEmpty()
                    || !rows.get(0).getTradingDate().equals(currentTwTradingDay());
            String asOf = rows.isEmpty() ? null : rows.get(0).getTradingDate().toString();
            TradingRadarDto.MarketSummary summary = new TradingRadarDto.MarketSummary(
                    result.regime().name(),
                    regimeLabel(result.regime()),
                    result.score(),
                    result.regime() != TradingRadarRuleEngine.MarketRegime.DATA_INCOMPLETE,
                    stale,
                    asOf,
                    price,
                    changePercent,
                    ind.monthlyMa(),
                    ind.quarterlyMa(),
                    ind.annualMa(),
                    ind.k(),
                    ind.d(),
                    c60.name(),
                    c240.name(),
                    result.reasons(),
                    result.risks());
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
            TechnicalData technical = prepareTechnicalData(target, rows, liveOpt);
            List<BigDecimal> closes = technical.completedCloses();
            BigDecimal price = liveOpt.map(PriceQueryService.LivePrice::price)
                    .orElseGet(() -> rows.isEmpty() ? null : rows.get(0).getClosePrice());
            BigDecimal displayChangePercent = liveOpt.map(PriceQueryService.LivePrice::changePercent)
                    .orElse(null);
            if (displayChangePercent == null && price != null && closes.size() >= 2) {
                BigDecimal previous = previousCompletedClose(rows, liveOpt.orElse(null));
                displayChangePercent = changePercent(price, previous);
            }
            BigDecimal ruleChangePercent = changePercent(price, technical.previousAdjustedClose());
            if (ruleChangePercent == null) ruleChangePercent = displayChangePercent;

            TechnicalIndicatorService.FullIndicators ind = technical.indicators();
            TradingRadarRuleEngine.Confirmation c20 = ruleEngine.confirm(closes, 20);
            TradingRadarRuleEngine.Confirmation c60 = ruleEngine.confirm(closes, 60);
            TradingRadarRuleEngine.Confirmation c240 = ruleEngine.confirm(closes, 240);
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
                            marketStale));

            List<String> reasons = new ArrayList<>();
            if (technical.distributionAdjusted()) {
                reasons.add("MA／KD、兩日確認與規則漲跌已使用還原權息價，避免把配息缺口誤判為趨勢跌破。 ");
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
                    List.copyOf(reasons),
                    result.risks());
        } catch (Exception e) {
            log.warn("今日交易雷達：{} {} 組裝失敗", target.market(), target.code(), e);
            return incompleteStock(target, name, assetClass, "讀取個股資料失敗，該檔今日不交易。");
        }
    }

    private TechnicalData prepareTechnicalData(
            Target target,
            List<StockPriceHistory> completedRows,
            Optional<PriceQueryService.LivePrice> liveOpt) {
        List<StockPriceHistory> combined = new ArrayList<>(completedRows);
        boolean liveAdded = liveOpt.filter(live -> shouldAddLiveRow(completedRows, live)).isPresent();
        if (liveAdded) {
            combined.add(0, liveRow(target, liveOpt.orElseThrow()));
        }
        if (combined.isEmpty()) {
            return new TechnicalData(
                    TechnicalIndicatorService.FullIndicators.EMPTY, List.of(), null, null, false);
        }

        LocalDate fromDate = combined.get(combined.size() - 1).getTradingDate();
        LocalDate toDate = combined.get(0).getTradingDate();
        DistributionAdjustedPriceService.Adjustment adjustment = adjustedPriceService.adjust(
                combined,
                dividendHistoryRepo.findAdjustmentEvents(
                        target.code(), target.market(), fromDate, toDate));
        List<StockPriceHistory> adjustedRows = adjustment.rowsDesc();

        int completedStart = liveAdded ? 1 : 0;
        int completedEnd = Math.min(completedStart + completedRows.size(), adjustedRows.size());
        List<BigDecimal> completedCloses = adjustedRows.subList(completedStart, completedEnd).stream()
                .map(StockPriceHistory::getClosePrice)
                .toList();

        int indicatorRows = Math.min(adjustedRows.size(), liveAdded ? 241 : 240);
        TechnicalIndicatorService.FullIndicators indicators = indicatorService.computeFromSeries(
                adjustedRows.subList(0, indicatorRows));
        BigDecimal previousAdjustedClose = adjustedRows.size() >= 2
                ? adjustedRows.get(1).getClosePrice()
                : null;
        // 最近一根完成日 K 相對前一根的漲跌幅（還原後價基），供逆勢「停止續跌」判定（Task 217.3）。
        int firstCompleted = liveAdded ? 1 : 0;
        BigDecimal completedChangePercent = adjustedRows.size() >= firstCompleted + 2
                ? changePercent(adjustedRows.get(firstCompleted).getClosePrice(),
                                adjustedRows.get(firstCompleted + 1).getClosePrice())
                : null;
        return new TechnicalData(
                indicators, completedCloses, previousAdjustedClose,
                completedChangePercent, adjustment.adjusted());
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

    private void loadLatestHoldings(Map<String, Target> targets, Set<String> skippedNonTw) {
        Optional<AssetSnapshot> snapshot = snapshotRepo.findLatestWithStocks();
        if (snapshot.isEmpty()) return;
        for (StockHolding holding : snapshot.get().getStocks()) {
            if (holding.getStockCode() == null || holding.getMarket() == null) continue;
            if (holding.getShares() == null || holding.getShares().signum() <= 0) continue;
            addTarget(targets, skippedNonTw, holding.getStockCode(), holding.getMarket(), true);
        }
    }

    private void loadWatchList(Map<String, Target> targets, Set<String> skippedNonTw) {
        for (Object[] row : alertRepo.findDistinctStockCodeMarket()) {
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

    private TradingRadarRuleEngine.Indicators indicators(TechnicalIndicatorService.FullIndicators ind) {
        return new TradingRadarRuleEngine.Indicators(
                ind.monthlyMa(), ind.quarterlyMa(), ind.annualMa(), ind.k(), ind.d());
    }

    private BigDecimal changePercent(BigDecimal current, BigDecimal previous) {
        if (current == null || previous == null || previous.signum() == 0) return null;
        return current.subtract(previous)
                .divide(previous, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private MarketState incompleteMarket(String message) {
        TradingRadarDto.MarketSummary summary = new TradingRadarDto.MarketSummary(
                TradingRadarRuleEngine.MarketRegime.DATA_INCOMPLETE.name(),
                regimeLabel(TradingRadarRuleEngine.MarketRegime.DATA_INCOMPLETE),
                null,
                false,
                true,
                null,
                null, null, null, null, null, null, null,
                TradingRadarRuleEngine.Confirmation.UNAVAILABLE.name(),
                TradingRadarRuleEngine.Confirmation.UNAVAILABLE.name(),
                List.of(),
                List.of(message));
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
                List.of(), List.of(message));
    }

    private String regimeLabel(TradingRadarRuleEngine.MarketRegime regime) {
        return switch (regime) {
            case RISK_ON -> "偏多／可尋找機會";
            case NEUTRAL -> "中性／等待確認";
            case RISK_OFF -> "偏空／降低風險";
            case DATA_INCOMPLETE -> "資料不足／今日不交易";
        };
    }

    public static String actionLabel(TradingRadarRuleEngine.Action action) {
        return switch (action) {
            case BUY_CANDIDATE -> "買進候選";
            case ADD_CANDIDATE -> "加碼候選";
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
