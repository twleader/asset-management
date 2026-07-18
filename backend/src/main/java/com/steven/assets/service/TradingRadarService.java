package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.StockHolding;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.repository.StockAlertRepository;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private final TwseIndexDailyHistoryRepository twseRepo;
    private final StockPriceHistoryRepository priceHistoryRepo;
    private final PriceQueryService priceQueryService;
    private final AssetSnapshotRepository snapshotRepo;
    private final StockAlertRepository alertRepo;
    private final StockRepository stockRepo;

    private record MarketState(
            TradingRadarDto.MarketSummary summary,
            TradingRadarRuleEngine.MarketRegime regime
    ) {}

    private record Target(String code, String market, boolean held) {}

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
                .map(t -> buildStock(t, market.regime()))
                .toList();

        return new TradingRadarDto.Response(
                TradingRadarRuleEngine.RULE_VERSION,
                ZonedDateTime.now(TAIPEI).toOffsetDateTime().toString(),
                market.summary(),
                decisions,
                skippedNonTw.size());
    }

    /** 背景通知評估共用同一份 V2 組裝，不依賴 HTTP owner filter。 */
    @Transactional(readOnly = true)
    public TradingRadarDto.StockDecision evaluateForNotification(
            String stockCode, String market, boolean held) {
        MarketState marketState = buildMarket();
        return buildStock(new Target(stockCode, market, held), marketState.regime());
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

            String asOf = rows.isEmpty() ? null : rows.get(0).getTradingDate().toString();
            TradingRadarDto.MarketSummary summary = new TradingRadarDto.MarketSummary(
                    result.regime().name(),
                    regimeLabel(result.regime()),
                    result.score(),
                    result.regime() != TradingRadarRuleEngine.MarketRegime.DATA_INCOMPLETE,
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
            return new MarketState(summary, result.regime());
        } catch (Exception e) {
            log.warn("今日交易雷達：大盤資料組裝失敗", e);
            return incompleteMarket("讀取大盤資料失敗，所有個股暫停產生交易訊號。");
        }
    }

    private TradingRadarDto.StockDecision buildStock(
            Target target,
            TradingRadarRuleEngine.MarketRegime marketRegime) {
        String name = stockRepo.findByCodeAndMarket(target.code(), target.market())
                .map(s -> s.getName())
                .filter(n -> n != null && !n.isBlank())
                .orElse(target.code());
        try {
            List<StockPriceHistory> rows = priceHistoryRepo.findRecentN(target.code(), target.market(), 241);
            List<BigDecimal> closes = rows.stream().map(StockPriceHistory::getClosePrice).toList();
            Optional<PriceQueryService.LivePrice> liveOpt = priceQueryService.getLive(target.code(), target.market());
            BigDecimal price = liveOpt.map(PriceQueryService.LivePrice::price)
                    .orElseGet(() -> rows.isEmpty() ? null : rows.get(0).getClosePrice());
            BigDecimal changePercent = liveOpt.map(PriceQueryService.LivePrice::changePercent)
                    .orElse(null);
            if (changePercent == null && price != null && closes.size() >= 2) {
                BigDecimal previous = previousCompletedClose(rows, liveOpt.orElse(null));
                changePercent = changePercent(price, previous);
            }

            TechnicalIndicatorService.FullIndicators ind = indicatorService.computeAll(target.code(), target.market());
            TradingRadarRuleEngine.Confirmation c20 = ruleEngine.confirm(closes, 20);
            TradingRadarRuleEngine.Confirmation c60 = ruleEngine.confirm(closes, 60);
            TradingRadarRuleEngine.Confirmation c240 = ruleEngine.confirm(closes, 240);
            TradingRadarRuleEngine.StockResult result = ruleEngine.evaluateStock(
                    new TradingRadarRuleEngine.StockInput(
                            target.held(),
                            price,
                            changePercent,
                            indicators(ind),
                            ind.previousK(),
                            ind.previousD(),
                            c20,
                            c60,
                            c240,
                            marketRegime));

            String updatedAt = liveOpt.map(PriceQueryService.LivePrice::updatedAt).orElse(null);
            String asOf = rows.isEmpty() ? null : rows.get(0).getTradingDate().toString();
            return new TradingRadarDto.StockDecision(
                    target.code(),
                    name,
                    target.market(),
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
                    changePercent,
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
                    result.reasons(),
                    result.risks());
        } catch (Exception e) {
            log.warn("今日交易雷達：{} {} 組裝失敗", target.market(), target.code(), e);
            return incompleteStock(target, name, "讀取個股資料失敗，該檔今日不交易。");
        }
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
                null,
                null, null, null, null, null, null, null,
                TradingRadarRuleEngine.Confirmation.UNAVAILABLE.name(),
                TradingRadarRuleEngine.Confirmation.UNAVAILABLE.name(),
                List.of(),
                List.of(message));
        return new MarketState(summary, TradingRadarRuleEngine.MarketRegime.DATA_INCOMPLETE);
    }

    private TradingRadarDto.StockDecision incompleteStock(Target target, String name, String message) {
        return new TradingRadarDto.StockDecision(
                target.code(), name, target.market(), target.held(),
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
