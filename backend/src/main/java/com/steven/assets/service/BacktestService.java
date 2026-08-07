package com.steven.assets.service;

import com.steven.assets.dto.BacktestDto;
import com.steven.assets.model.EtfNavHistory;
import com.steven.assets.model.ExchangeRateHistory;
import com.steven.assets.model.Stock;
import com.steven.assets.model.StockDividendHistory;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.model.UsIndexDailyHistory;
import com.steven.assets.repository.EtfNavHistoryRepository;
import com.steven.assets.repository.ExchangeRateHistoryRepository;
import com.steven.assets.repository.StockDividendHistoryRepository;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.StockRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import com.steven.assets.repository.UsIndexDailyHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 交易雷達規則回測框架（Task 273／291）。
 *
 * <p>V11 以 production 共用的組裝器、規則引擎、市場 context 與基本面 as-of resolver，
 * 稽核短期與中期訊號；
 * 本服務只量測規則輸出，不在回測端另寫第二套判定。</p>
 *
 * <p><b>做法</b>：把歷史序列逐日切成「截至 t 的 241 筆視窗」，餵給
 * {@link RadarInputAssembler}（production 的同一支組裝）再餵給同一支規則引擎，
 * 量測每條述詞成立後 5／20／60／120 個交易日的前瞻報酬分布，並與同標的同期間的
 * 無條件分布（基準）比較。</p>
 *
 * <p><b>輸出一律是「歷史上此條件成立後的報酬分布」</b>，不是預測（273.8.1）。
 * 刻意不輸出 p 值與信賴區間——樣本嚴重自相關，古典檢定前提不成立（273.6.4）。</p>
 *
 * <h3>前視偏誤的處理</h3>
 * <ul>
 *   <li><b>全期統計量是真正的來源</b>：52 週高低一律只取自截至 t 的 241 筆視窗，
 *       由 {@code RadarInputAssembler} 負責，本服務不另外掃全序列取 max／min。</li>
 *   <li><b>還原權息的切片不構成前視偏誤</b>：back-adjustment 對切片只造成一個與索引無關的
 *       常數倍率，而引擎的全部價格導出輸入皆為尺度不變量（比較、比值、相對位置）。
 *       故不需逐 t 重錨，也不得宣稱重錨修正了前視偏誤（273.3.2）。</li>
 *   <li><b>大盤 regime 逐日重算</b>，不得用最新一日套用到全部歷史。</li>
 *   <li><b>匯率分位以「截至 t 的五年視窗」重算</b>，不得沿用 production 以今日為錨的算法。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BacktestService {

    private static final String TW_MARKET = "台股";
    private static final String TAIEX_CODE = "0000";
    private static final String TWD = "TWD";
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    /** 需要 240 根完成日 K 的因子在此之前不可得；這些日子 production 會回 NO_TRADE，一律排除。 */
    private static final int WARMUP = RadarInputAssembler.FULL_WINDOW;
    /** production 取數視窗（240 根完成日 K ＋ 當日）。回測無 live K，仍取 241 筆使兩日確認可算。 */
    private static final int WINDOW = 241;
    private static final List<Integer> DEFAULT_HORIZONS = List.of(5, 20, 60, 120);
    /** 低於此樣本數的格子一律標記為樣本不足，其數字不得用於決策（273.6.3）。 */
    private static final int MIN_SAMPLES = 30;
    /** 波動度的回看筆數。 */
    private static final int SIGMA_WINDOW = 60;
    private static final BigDecimal DOWNSIDE = BigDecimal.valueOf(-10);

    private final TradingRadarRuleEngine ruleEngine;
    private final RadarInputAssembler assembler;
    private final AssetClassifier assetClassifier;
    private final StockPriceHistoryRepository priceHistoryRepo;
    private final StockDividendHistoryRepository dividendHistoryRepo;
    private final TwseIndexDailyHistoryRepository twseRepo;
    private final UsIndexDailyHistoryRepository usIndexRepo;
    private final ExchangeRateHistoryRepository exchangeRateRepo;
    private final EtfNavHistoryRepository etfNavHistoryRepo;
    private final StockRepository stockRepo;
    private final DistributionAdjustedPriceService adjustedPriceService;
    private final TradingRadarMarketContextService marketContextService;
    private final FundamentalAnalysisService fundamentalAnalysisService;

    /** 單一交易日的觀察值：述詞判定所需的一切，全部來自截至該日的資料。 */
    private record Obs(
            LocalDate date,
            int index,
            Integer score,
            Integer shortScore,
            TradingRadarRuleEngine.Action actionHeld,
            TradingRadarRuleEngine.Action actionNotHeld,
            TradingRadarRuleEngine.Action shortActionHeld,
            TradingRadarRuleEngine.Action shortActionNotHeld,
            TradingRadarRuleEngine.TimingState timing,
            TradingRadarRuleEngine.KdHeat kdHeat,
            boolean kdDeadCross,
            boolean longTermBroken,
            boolean profitTakingConfirmed,
            BigDecimal ma60BiasPercent,
            BigDecimal k,
            BigDecimal d,
            BigDecimal sigma,
            BigDecimal volumeRatio,
            boolean etfPremiumAvailable,
            /** 該標的是否為 ETF（折溢價否決只對 ETF 生效，非 ETF 不該掛 caveat）。 */
            boolean etfLike,
            TradingRadarRuleEngine.FundamentalInput fundamental
    ) {
        TradingRadarRuleEngine.Action action(boolean held) { return held ? actionHeld : actionNotHeld; }
        TradingRadarRuleEngine.Action shortAction(boolean held) {
            return held ? shortActionHeld : shortActionNotHeld;
        }
    }

    /** 同幣別的歷史列與逐訊號日解析結果；避免每檔重複查詢與重算。 */
    private record FxSeries(
            List<ExchangeRateHistory> rows,
            Map<LocalDate, TradingRadarMarketContextService.FxContext> resolved
    ) {}

    /** 具名述詞。{@code held} 由呼叫端帶入，因為述詞 6／7 的動作依 held 而不同。 */
    private record NamedPredicate(String name, java.util.function.BiPredicate<Obs, Boolean> test) {}

    /** 單一標的的回測產物。 */
    private record CodeRun(
            BacktestDto.CodeCoverage coverage,
            List<Obs> observations,
            /** 全序列的還原收盤價，供前瞻報酬（含 t+h，故取自全序列而非截至 t 的子序列）。 */
            List<BigDecimal> adjustedCloses
    ) {}

    // ─────────────────────────── 對外入口 ───────────────────────────

    public BacktestDto.Response run(BacktestDto.Request request) {
        BacktestDto.Request req = request == null
                ? new BacktestDto.Request(null, null, null, null, null, null)
                : request;
        List<Integer> horizons = (req.horizons() == null || req.horizons().isEmpty())
                ? DEFAULT_HORIZONS
                : req.horizons().stream().filter(h -> h != null && h > 0).sorted().distinct().toList();
        LocalDate from = parseDate(req.from());
        LocalDate to = parseDate(req.to());

        List<String> codes = resolveCodes(req.codes());
        Map<LocalDate, TradingRadarRuleEngine.MarketRegime> regimes = buildMarketRegimes();
        Map<String, FxSeries> fxSeriesByCurrency = new HashMap<>();

        List<CodeRun> runs = new ArrayList<>();
        List<String> failedCodes = new ArrayList<>();
        for (String code : codes) {
            try {
                CodeRun run = runOne(code, from, to, regimes, fxSeriesByCurrency);
                if (run != null) runs.add(run);
            } catch (Exception e) {
                // 失敗標的必須在輸出中可見：否則「68 檔的統計」與「本來就只有 68 檔」無法區分，
                // 結果不可獨立複驗（273.7.1）。
                log.warn("回測 {} 失敗，略過該標的", code, e);
                failedCodes.add(code);
            }
        }

        List<NamedPredicate> predicates = buildPredicates(req.thresholds(), req.predicates());
        List<BacktestDto.PredicateStat> stats = new ArrayList<>();
        List<BacktestDto.PairedStat> paired = new ArrayList<>();
        for (NamedPredicate p : predicates) {
            for (boolean held : new boolean[]{true, false}) {
                for (int h : horizons) {
                    stats.add(pooled(p, held, h, runs));
                    paired.add(pairedByCode(p, held, h, runs));
                }
            }
        }

        String dataFrom = runs.stream().map(r -> r.coverage().dataFrom())
                .filter(java.util.Objects::nonNull).min(String::compareTo).orElse(null);
        String dataTo = runs.stream().map(r -> r.coverage().dataTo())
                .filter(java.util.Objects::nonNull).max(String::compareTo).orElse(null);

        return new BacktestDto.Response(
                TradingRadarRuleEngine.RULE_VERSION,
                dataFrom,
                dataTo,
                runs.size(),
                horizons,
                runs.stream().map(CodeRun::coverage).toList(),
                stats,
                paired,
                List.copyOf(failedCodes),
                notes(runs, failedCodes));
    }

    // ─────────────────────────── 單一標的 ───────────────────────────

    private CodeRun runOne(
            String code,
            LocalDate from,
            LocalDate to,
            Map<LocalDate, TradingRadarRuleEngine.MarketRegime> regimes,
            Map<String, FxSeries> fxCache) {

        List<StockPriceHistory> raw =
                priceHistoryRepo.findAllByStockCodeAndMarketOrderByTradingDateAsc(code, TW_MARKET);
        if (raw == null || raw.isEmpty()) return null;

        // close_price <= 0 的髒列一律剔除：單列即產生 bias = −100% 的假訊號並污染 MA60。
        // 這是既有的資料品質問題（實測台股 175 列），本框架只剔除與揭露，不修資料。
        List<StockPriceHistory> rows = raw.stream()
                .filter(r -> r.getClosePrice() != null && r.getClosePrice().signum() > 0)
                .toList();
        int nonPositive = raw.size() - rows.size();
        if (rows.size() <= WARMUP) {
            return new CodeRun(coverage(code, rows, nonPositive, rows.size(), 0, 0, "EQUITY"),
                    List.of(), List.of());
        }

        Optional<Stock> stock = stockRepo.findByCodeAndMarket(code, TW_MARKET);
        String assetClass = assetClassifier.classifyStock(
                code, TW_MARKET, stock.map(Stock::getAssetClass).orElse(null));
        TradingRadarRuleEngine.InstrumentType instrumentType = AssetClassifier.BOND.equals(assetClass)
                ? TradingRadarRuleEngine.InstrumentType.BOND
                : TradingRadarRuleEngine.InstrumentType.EQUITY;

        LocalDate seriesFrom = rows.get(0).getTradingDate();
        LocalDate seriesTo = rows.get(rows.size() - 1).getTradingDate();
        List<StockDividendHistory> allEvents =
                dividendHistoryRepo.findAdjustmentEvents(code, TW_MARKET, seriesFrom, seriesTo);

        // 前瞻報酬用的還原序列：一次算完整段（含 t+h）。切片與全段只差一個常數倍率，
        // 而報酬率是比值，故錨點差異不影響結果（273.3.2 的推導）。
        List<BigDecimal> adjustedCloses = fullAdjustedCloses(rows, allEvents);
        List<BigDecimal> returns = dailyReturns(adjustedCloses);

        Map<LocalDate, BigDecimal> premiumByDate = premiumByDate(code);
        // ETF 判定採資料驅動＋既有 fallback（台股 00 開頭為 ETF），與專案既有方向一致。
        // 只有 ETF 才會被折溢價否決影響，故只有 ETF 缺值才需要在統計上掛 caveat。
        boolean etfLike = !premiumByDate.isEmpty() || code.startsWith("00");
        String currency = underlyingCurrency(stock.orElse(null));
        FxSeries fxSeries = TWD.equals(currency) ? null : fxSeries(currency, fxCache);

        // 基本面 observation 每檔只查一次；各訊號日的 as-of/revision collapse 由 resolver 在記憶體完成。
        List<java.time.Instant> fundamentalInstants = rows.stream().skip(WARMUP)
                .map(StockPriceHistory::getTradingDate)
                .filter(date -> from == null || !date.isBefore(from))
                .filter(date -> to == null || !date.isAfter(to))
                .map(this::signalInstant)
                .toList();
        Map<java.time.Instant, TradingRadarRuleEngine.FundamentalInput> fundamentalByInstant =
                fundamentalAnalysisService.resolveInputsForBacktest(code, TW_MARKET, fundamentalInstants);

        List<Obs> observations = new ArrayList<>();
        int warmupExcluded = 0;
        int etfPremiumDays = 0;

        for (int t = 0; t < rows.size(); t++) {
            if (t < WARMUP) { warmupExcluded++; continue; }
            LocalDate date = rows.get(t).getTradingDate();
            if (from != null && date.isBefore(from)) continue;
            if (to != null && date.isAfter(to)) continue;

            // 截至 t 的 241 筆視窗，降序（新到舊）——與 production 的 findRecentN(…, 241) 同形狀。
            int lo = Math.max(0, t - WINDOW + 1);
            List<StockPriceHistory> windowDesc = new ArrayList<>(rows.subList(lo, t + 1));
            Collections.reverse(windowDesc);

            List<StockDividendHistory> events = eventsWithin(
                    allEvents, windowDesc.get(windowDesc.size() - 1).getTradingDate(), date);

            // 視窗最新一筆的還原收盤價恆等於其原始收盤價（該筆的 scale 必為 1），故直接取原始值。
            BigDecimal price = windowDesc.get(0).getClosePrice();
            RadarInputAssembler.Assembled a =
                    assembler.assemble(windowDesc, events, false, windowDesc.size(), price);

            BigDecimal premium = premiumByDate.get(date);
            if (premium != null) etfPremiumDays++;

            TradingRadarRuleEngine.MarketRegime regime = regimes.getOrDefault(
                    date, TradingRadarRuleEngine.MarketRegime.DATA_INCOMPLETE);

            BigDecimal fxPct = fxSeries == null ? null : fxAt(currency, fxSeries, date).percentile();
            java.time.Instant decisionInstant = signalInstant(date);
            TradingRadarRuleEngine.FundamentalInput fundamental = fundamentalByInstant.getOrDefault(
                    decisionInstant, FundamentalAnalysisService.Resolved.unavailable(true).input());

            TradingRadarRuleEngine.StockResult held = evaluate(
                    a, true, price, instrumentType, regime, fxPct, premium, fundamental);
            TradingRadarRuleEngine.StockResult free = evaluate(
                    a, false, price, instrumentType, regime, fxPct, premium, fundamental);

            observations.add(new Obs(
                    date, t,
                    held.score(),
                    held.shortScore(),
                    held.action(), free.action(),
                    held.shortAction(), free.shortAction(),
                    held.timingState(), held.kdHeat(),
                    held.kdDeadCross(), held.longTermBroken(),
                    held.profitTakingConfirmed(),
                    a.ma60BiasPercent(),
                    a.indicators().k(), a.indicators().d(),
                    sigmaAt(returns, t),
                    a.volumeRatio(),
                    premium != null,
                    etfLike,
                    fundamental));
        }

        return new CodeRun(
                coverage(code, rows, nonPositive, warmupExcluded, observations.size(),
                        etfPremiumDays, instrumentType.name()),
                observations,
                adjustedCloses);
    }

    /** 以組裝結果 ＋ 環境欄位呼叫同一支引擎。**述詞判定一律由引擎產生，此處不複製任何判定邏輯。** */
    private TradingRadarRuleEngine.StockResult evaluate(
            RadarInputAssembler.Assembled a,
            boolean held,
            BigDecimal price,
            TradingRadarRuleEngine.InstrumentType instrumentType,
            TradingRadarRuleEngine.MarketRegime regime,
            BigDecimal fxPct,
            BigDecimal premium,
            TradingRadarRuleEngine.FundamentalInput fundamental) {
        return ruleEngine.evaluateStock(new TradingRadarRuleEngine.StockInput(
                held,
                price,
                a.ruleChangePercent(),
                a.completedChangePercent(),
                assembler.indicators(a.indicators()),
                a.indicators().previousK(),
                a.indicators().previousD(),
                a.ma20Confirmation(),
                a.ma60Confirmation(),
                a.ma240Confirmation(),
                instrumentType,
                regime,
                false,                 // 歷史日一律非 stale：完成日 K 就是當日的最終值
                fxPct,
                a.ma60BiasPercent(),
                a.ma240BiasPercent(),
                a.week52Position(),
                a.kdBandWidthPercent(),
                premium,
                null,                  // 折溢價自身歷史分位在既有回測資料結構下不可得
                a.indicators().weeklyMa(),
                assembler.extendedIndicators(a.indicators().extended()),
                a.volumeRatio(),
                fundamental));
    }

    // ─────────────────────────── 述詞 ───────────────────────────

    private List<NamedPredicate> buildPredicates(
            Map<String, List<BigDecimal>> thresholds, List<String> composed) {
        Map<String, java.util.function.BiPredicate<Obs, Boolean>> base = basePredicates();
        List<NamedPredicate> out = new ArrayList<>();
        base.forEach((name, test) -> out.add(new NamedPredicate(name, test)));

        if (thresholds != null) {
            thresholds.forEach((key, candidates) -> {
                if (candidates == null) return;
                for (BigDecimal c : candidates) {
                    if (c == null) continue;
                    NamedPredicate p = sweepPredicate(key, c);
                    if (p != null) out.add(p);
                }
            });
        }
        if (composed != null) {
            for (String expr : composed) {
                if (expr == null || expr.isBlank()) continue;
                String[] parts = expr.split("\\+");
                List<java.util.function.BiPredicate<Obs, Boolean>> ps = new ArrayList<>();
                boolean ok = true;
                for (String part : parts) {
                    var t = base.get(part.trim());
                    if (t == null) { ok = false; break; }
                    ps.add(t);
                }
                if (!ok) { log.warn("回測：略過無法解析的組合述詞 {}", expr); continue; }
                out.add(new NamedPredicate(expr.trim(),
                        (o, h) -> ps.stream().allMatch(x -> x.test(o, h))));
            }
        }
        return out;
    }

    /** V11 內建述詞。全部由引擎輸出組成，不在回測端複製動作門檻。 */
    private Map<String, java.util.function.BiPredicate<Obs, Boolean>> basePredicates() {
        Map<String, java.util.function.BiPredicate<Obs, Boolean>> m = new LinkedHashMap<>();
        for (TradingRadarRuleEngine.TimingState s : TradingRadarRuleEngine.TimingState.values()) {
            m.put("TIMING_" + s.name(), (o, h) -> o.timing() == s);
        }
        m.put("PROFIT_TAKING_CONFIRMED", (o, h) -> o.profitTakingConfirmed());
        m.put("SHORT_BUY", (o, h) -> isBuy(o.shortAction(h)));
        m.put("MEDIUM_BUY", (o, h) -> isBuy(o.action(h)));
        m.put("SHORT_PROFIT_TAKING", (o, h) -> o.profitTakingConfirmed() && isSell(o.shortAction(h)));
        m.put("MEDIUM_PROFIT_TAKING", (o, h) -> o.profitTakingConfirmed() && isSell(o.action(h)));
        // 極端超賣保護只統計「原分數本會落入賣出組，但實際被改成中性組」的日子。
        m.put("SHORT_EXTREME_OVERSOLD_PROTECTED", (o, h) ->
                o.timing() == TradingRadarRuleEngine.TimingState.EXTREME_OVERSOLD
                        && o.shortScore() != null && o.shortScore() < 40
                        && isNeutral(o.shortAction(h)));
        m.put("MEDIUM_EXTREME_OVERSOLD_PROTECTED", (o, h) ->
                o.timing() == TradingRadarRuleEngine.TimingState.EXTREME_OVERSOLD
                        && o.score() != null && o.score() < 40
                        && isNeutral(o.action(h)));
        m.put("LONG_TERM_BROKEN_OVERSOLD_PROTECTED", (o, h) ->
                o.timing() == TradingRadarRuleEngine.TimingState.EXTREME_OVERSOLD
                        && o.longTermBroken() && isNeutral(o.action(h)));
        m.put("KD_OVERHEATED", (o, h) -> o.kdHeat() == TradingRadarRuleEngine.KdHeat.OVERHEATED);
        m.put("BUY_GATE", (o, h) -> isBuy(o.action(h)));
        m.put("TRIAL_BUY", (o, h) -> o.action(h) == TradingRadarRuleEngine.Action.TRIAL_BUY);
        m.put("SCORE_GTE_75", (o, h) -> o.score() != null && o.score() >= 75);
        m.put("SCORE_55_74", (o, h) -> o.score() != null && o.score() >= 55 && o.score() < 75);
        m.put("SCORE_40_54", (o, h) -> o.score() != null && o.score() >= 40 && o.score() < 55);
        m.put("SCORE_25_39", (o, h) -> o.score() != null && o.score() >= 25 && o.score() < 40);
        m.put("SCORE_LT_25", (o, h) -> o.score() != null && o.score() < 25);
        m.put("SCORE_LT_40", (o, h) -> o.score() != null && o.score() < 40);
        return m;
    }

    private boolean isBuy(TradingRadarRuleEngine.Action action) {
        return action == TradingRadarRuleEngine.Action.BUY_CANDIDATE
                || action == TradingRadarRuleEngine.Action.ADD_CANDIDATE
                || action == TradingRadarRuleEngine.Action.TRIAL_BUY;
    }

    private boolean isSell(TradingRadarRuleEngine.Action action) {
        return action == TradingRadarRuleEngine.Action.REDUCE_CANDIDATE
                || action == TradingRadarRuleEngine.Action.EXIT_CANDIDATE
                || action == TradingRadarRuleEngine.Action.AVOID;
    }

    private boolean isNeutral(TradingRadarRuleEngine.Action action) {
        return action == TradingRadarRuleEngine.Action.HOLD
                || action == TradingRadarRuleEngine.Action.WATCH
                || action == TradingRadarRuleEngine.Action.HOLD_CAUTION
                || action == TradingRadarRuleEngine.Action.WAIT;
    }

    /**
     * 門檻掃描（273.4b.1）。**刻意在述詞層而非引擎層套用候選值**——引擎的門檻是常數，
     * 掃描要回答的是「若門檻改成 X，哪些日子會成立」，用同一組已算好的底層量即可，
     * 不需要（也不應該）為了掃描去改引擎。
     */
    private NamedPredicate sweepPredicate(String key, BigDecimal c) {
        String suffix = "@" + c.stripTrailingZeros().toPlainString();
        return switch (key) {
            case "biasSigma" -> new NamedPredicate("BIAS_SIGMA_OVER" + suffix, (o, h) -> {
                BigDecimal nb = normalizedBias(o);
                return nb != null && nb.abs().compareTo(c) > 0;
            });
            case "sigmaFloor" -> new NamedPredicate("SIGMA_BELOW_FLOOR" + suffix, (o, h) ->
                    o.sigma() != null && o.sigma().compareTo(c) < 0);
            case "kdOverheat" -> new NamedPredicate("KD_AVG_OVER" + suffix, (o, h) -> {
                BigDecimal avg = kdAvg(o);
                return avg != null && avg.compareTo(c) > 0;
            });
            case "kdOversold" -> new NamedPredicate("KD_AVG_UNDER" + suffix, (o, h) -> {
                BigDecimal avg = kdAvg(o);
                return avg != null && avg.compareTo(c) < 0;
            });
            case "volumeRatio" -> new NamedPredicate("VOLUME_RATIO_OVER" + suffix, (o, h) ->
                    o.volumeRatio() != null && o.volumeRatio().compareTo(c) > 0);
            default -> {
                log.warn("回測：不支援的門檻掃描 key {}", key);
                yield null;
            }
        };
    }

    /** `bias ÷ (σ × 100)`：bias 是百分比、σ 是比例，相除前必須把 σ 乘以 100 才對齊量綱。 */
    private BigDecimal normalizedBias(Obs o) {
        if (o.ma60BiasPercent() == null || o.sigma() == null || o.sigma().signum() <= 0) return null;
        return o.ma60BiasPercent().divide(
                o.sigma().multiply(BigDecimal.valueOf(100)), 8, RoundingMode.HALF_UP);
    }

    private BigDecimal kdAvg(Obs o) {
        if (o.k() == null || o.d() == null) return null;
        return o.k().add(o.d()).divide(BigDecimal.valueOf(2), 4, RoundingMode.HALF_UP);
    }

    // ─────────────────────────── 統計 ───────────────────────────

    /** 全樣本合併口徑。 */
    private BacktestDto.PredicateStat pooled(
            NamedPredicate p, boolean held, int horizon, List<CodeRun> runs) {
        List<BigDecimal> signal = new ArrayList<>();
        List<BigDecimal> baseline = new ArrayList<>();
        int insufficient = 0;
        boolean anyEtfWithoutPremium = false;   // 只在「是 ETF 且該日無折溢價」時才成立

        for (CodeRun run : runs) {
            for (Obs o : run.observations()) {
                BigDecimal fwd = forwardReturn(run.adjustedCloses(), o.index(), horizon);
                boolean hit = p.test().test(o, held);
                if (fwd == null) {
                    if (hit) insufficient++;
                    continue;
                }
                baseline.add(fwd);
                if (hit) {
                    signal.add(fwd);
                    if (o.etfLike() && !o.etfPremiumAvailable()) anyEtfWithoutPremium = true;
                }
            }
        }

        String caveat = null;
        // 受 ETF 折溢價否決影響的述詞：buyGate（硬否決）、OVERBOUGHT／EXTREME_OVERBOUGHT（參與 timingOf）、
        // 以及 TRIAL_BUY（qualifiesForTrialBuy 同樣檢查溢價）。
        // 注意 "TIMING_EXTREME_OVERBOUGHT".startsWith("TIMING_OVERBOUGHT") 為 false，不可用 startsWith 判。
        boolean premiumSensitive = "BUY_GATE".equals(p.name())
                || "SHORT_BUY".equals(p.name())
                || "MEDIUM_BUY".equals(p.name())
                || "TRIAL_BUY".equals(p.name())
                || p.name().endsWith("OVERBOUGHT");
        if (anyEtfWithoutPremium && premiumSensitive) {
            caveat = "本次量測未含 ETF 折溢價否決（etf_nav_history 在回測期間結構性缺值），"
                    + "不得直接當成 production 行為的證據。";
        }

        return new BacktestDto.PredicateStat(
                p.name(), held, horizon,
                signal.size(), signal.size() < MIN_SAMPLES, insufficient,
                mean(signal), percentile(signal, 50), ratioAbove(signal, BigDecimal.ZERO),
                ratioAtOrBelow(signal, DOWNSIDE),
                percentile(signal, 5), percentile(signal, 25),
                percentile(signal, 75), percentile(signal, 95),
                baseline.size(), mean(baseline), percentile(baseline, 50),
                ratioAbove(baseline, BigDecimal.ZERO), ratioAtOrBelow(baseline, DOWNSIDE),
                diff(mean(signal), mean(baseline)),
                caveat);
    }

    /** 逐標的配對比較口徑：每檔各自算「訊號組 − 基準」，再對各檔的差額取統計量。 */
    private BacktestDto.PairedStat pairedByCode(
            NamedPredicate p, boolean held, int horizon, List<CodeRun> runs) {
        List<BigDecimal> diffs = new ArrayList<>();
        int positive = 0;
        for (CodeRun run : runs) {
            List<BigDecimal> signal = new ArrayList<>();
            List<BigDecimal> baseline = new ArrayList<>();
            for (Obs o : run.observations()) {
                BigDecimal fwd = forwardReturn(run.adjustedCloses(), o.index(), horizon);
                if (fwd == null) continue;
                baseline.add(fwd);
                if (p.test().test(o, held)) signal.add(fwd);
            }
            if (signal.isEmpty() || baseline.isEmpty()) continue;
            BigDecimal d = diff(mean(signal), mean(baseline));
            if (d == null) continue;
            diffs.add(d);
            if (d.signum() > 0) positive++;
        }
        return new BacktestDto.PairedStat(
                p.name(), held, horizon, diffs.size(),
                mean(diffs), percentile(diffs, 50), positive);
    }

    /**
     * 前瞻報酬 ＝ {@code adjClose[t+h] / adjClose[t] − 1}（%）。
     *
     * <p>{@code t+h} 越界時回 {@code null}（該樣本不計入此 horizon，但仍計入較短的 horizon），
     * <b>不得以最後一日充數</b>。</p>
     */
    private BigDecimal forwardReturn(List<BigDecimal> adjustedCloses, int t, int horizon) {
        int target = t + horizon;
        if (target >= adjustedCloses.size()) return null;
        BigDecimal now = adjustedCloses.get(t);
        BigDecimal later = adjustedCloses.get(target);
        if (now == null || later == null || now.signum() <= 0) return null;
        return later.divide(now, 10, RoundingMode.HALF_UP)
                .subtract(BigDecimal.ONE)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }

    // ─────────────────────────── 逐日環境 ───────────────────────────

    /** 大盤 regime 逐日重算，並以該台股日 14:00 截斷美股科技與大盤量能資料。 */
    private Map<LocalDate, TradingRadarRuleEngine.MarketRegime> buildMarketRegimes() {
        List<TwseIndexDailyHistory> asc = twseRepo.findAllByOrderByTradingDateAsc();
        Map<LocalDate, TradingRadarRuleEngine.MarketRegime> out = new HashMap<>();
        if (asc == null || asc.isEmpty()) return out;
        List<UsIndexDailyHistory> usRows = new ArrayList<>();
        usRows.addAll(usIndexRepo.findByIndexCodeOrderByTradingDateAsc("IXIC"));
        usRows.addAll(usIndexRepo.findByIndexCodeOrderByTradingDateAsc("SOX"));

        List<StockPriceHistory> asRows = asc.stream()
                .filter(r -> r.getClosePoint() != null && r.getClosePoint().signum() > 0)
                .map(r -> StockPriceHistory.builder()
                        .stockCode(TAIEX_CODE).market(TW_MARKET)
                        .tradingDate(r.getTradingDate())
                        .openPrice(r.getOpenPoint()).highPrice(r.getHighPoint())
                        .lowPrice(r.getLowPoint()).closePrice(r.getClosePoint())
                        .build())
                .toList();

        for (int t = WARMUP; t < asRows.size(); t++) {
            List<StockPriceHistory> windowDesc = new ArrayList<>(
                    asRows.subList(Math.max(0, t - WINDOW + 1), t + 1));
            Collections.reverse(windowDesc);
            // 大盤無除權息事件，events 傳空 list；assemble 會原樣回傳序列。
            RadarInputAssembler.Assembled a = assembler.assemble(
                    windowDesc, List.of(), false, windowDesc.size(),
                    windowDesc.get(0).getClosePrice());
            LocalDate signalDate = asRows.get(t).getTradingDate();
            TradingRadarMarketContextService.MarketContext context =
                    marketContextService.resolveMarketFromRows(signalInstant(signalDate), asc, usRows);
            TradingRadarRuleEngine.MarketResult r = ruleEngine.evaluateMarket(
                    new TradingRadarRuleEngine.MarketInput(
                            windowDesc.get(0).getClosePrice(),
                            a.ruleChangePercent(),
                            assembler.indicators(a.indicators()),
                            a.ma60Confirmation(),
                            a.ma240Confirmation(),
                            context.completedMarketChangePercent(),
                            context.marketVolumeRatio(),
                            context.marketTurnoverRatio(),
                            context.nasdaqChangePercent(),
                            context.soxChangePercent(),
                            context.usTechCompositePercent(),
                            context.usTechAvailable()));
            out.put(signalDate, r.regime());
        }
        return out;
    }

    /** 歷史訊號固定在台北 14:00，當日 17:00 匯率必然仍不可見。 */
    private java.time.Instant signalInstant(LocalDate date) {
        return date.atTime(LocalTime.of(14, 0)).atZone(TAIPEI).toInstant();
    }

    private BigDecimal sigmaAt(List<BigDecimal> returns, int t) {
        if (t < SIGMA_WINDOW) return null;
        List<BigDecimal> w = returns.subList(t - SIGMA_WINDOW + 1, t + 1);
        List<BigDecimal> valid = w.stream().filter(java.util.Objects::nonNull).toList();
        if (valid.size() < SIGMA_WINDOW) return null;
        BigDecimal m = mean(valid);
        if (m == null) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal r : valid) {
            BigDecimal dv = r.subtract(m);
            sum = sum.add(dv.multiply(dv));
        }
        double var = sum.doubleValue() / (valid.size() - 1);
        if (var <= 0) return null;
        return BigDecimal.valueOf(Math.sqrt(var)).setScale(8, RoundingMode.HALF_UP);
    }

    // ─────────────────────────── 資料準備 ───────────────────────────

    private List<String> resolveCodes(List<String> requested) {
        if (requested != null && !requested.isEmpty()) {
            return requested.stream().filter(c -> c != null && !c.isBlank())
                    .map(String::trim).filter(c -> !TAIEX_CODE.equals(c)).distinct().toList();
        }
        List<String> all = priceHistoryRepo.findDistinctStockCodesByMarket(TW_MARKET);
        return all == null ? List.of()
                : all.stream().filter(c -> !TAIEX_CODE.equals(c)).sorted().toList();
    }

    /** 全序列一次還原，供前瞻報酬。與逐 t 視窗的還原只差常數倍率，報酬率不受影響。 */
    private List<BigDecimal> fullAdjustedCloses(
            List<StockPriceHistory> rowsAsc, List<StockDividendHistory> events) {
        List<StockPriceHistory> desc = new ArrayList<>(rowsAsc);
        Collections.reverse(desc);
        // 還原失敗一律往外拋——**不得**靜默改用原始收盤價。用原始價會讓除息缺口變成假跌幅、
        // 月配息債券 ETF 的 240 日報酬被系統性低估，而輸出看起來完全正常（273.5 明訂「不得用原始收盤價」）。
        // 拋出後由 run() 的 per-code catch 丟棄該標的並記入 failedCodes，與逐 t 視窗路徑
        //（RadarInputAssembler 內無 try/catch）的行為一致。
        List<StockPriceHistory> out = adjustedPriceService.adjust(desc, events).rowsDesc();
        List<BigDecimal> asc = new ArrayList<>(out.size());
        for (int i = out.size() - 1; i >= 0; i--) asc.add(out.get(i).getClosePrice());
        return asc;
    }

    private List<BigDecimal> dailyReturns(List<BigDecimal> closes) {
        List<BigDecimal> out = new ArrayList<>(closes.size());
        out.add(null);
        for (int i = 1; i < closes.size(); i++) {
            BigDecimal prev = closes.get(i - 1);
            BigDecimal cur = closes.get(i);
            out.add(prev == null || cur == null || prev.signum() <= 0
                    ? null
                    : cur.divide(prev, 10, RoundingMode.HALF_UP).subtract(BigDecimal.ONE));
        }
        return out;
    }

    private List<StockDividendHistory> eventsWithin(
            List<StockDividendHistory> all, LocalDate from, LocalDate to) {
        if (all == null || all.isEmpty()) return List.of();
        return all.stream()
                .filter(e -> e.getExDividendDate() != null
                        && !e.getExDividendDate().isBefore(from)
                        && !e.getExDividendDate().isAfter(to))
                .toList();
    }

    private Map<LocalDate, BigDecimal> premiumByDate(String code) {
        List<EtfNavHistory> rows = etfNavHistoryRepo
                .findByStockCodeAndMarketOrderByNavDateAsc(code, TW_MARKET);
        Map<LocalDate, BigDecimal> out = new HashMap<>();
        if (rows == null) return out;
        for (EtfNavHistory r : rows) {
            if (r.getNavDate() != null && r.getPremiumDiscountPct() != null) {
                out.put(r.getNavDate(), r.getPremiumDiscountPct());
            }
        }
        return out;
    }

    private String underlyingCurrency(Stock stock) {
        if (stock != null && stock.getUnderlyingCurrency() != null
                && !stock.getUnderlyingCurrency().isBlank()) {
            return stock.getUnderlyingCurrency().trim().toUpperCase();
        }
        return TWD;
    }

    private FxSeries fxSeries(String currency, Map<String, FxSeries> cache) {
        return cache.computeIfAbsent(currency, key -> {
            List<ExchangeRateHistory> rows = exchangeRateRepo.findByCurrencyOrderByRateDateAsc(key);
            return new FxSeries(rows == null ? List.of() : List.copyOf(rows), new HashMap<>());
        });
    }

    private TradingRadarMarketContextService.FxContext fxAt(
            String currency, FxSeries series, LocalDate signalDate) {
        return series.resolved().computeIfAbsent(signalDate,
                date -> marketContextService.resolveFxFromRows(currency, signalInstant(date), series.rows()));
    }

    private BacktestDto.CodeCoverage coverage(
            String code, List<StockPriceHistory> rows, int nonPositive,
            int warmupExcluded, int evaluated, int etfPremiumDays, String instrumentType) {
        return new BacktestDto.CodeCoverage(
                code, TW_MARKET, instrumentType,
                rows.isEmpty() ? null : rows.get(0).getTradingDate().toString(),
                rows.isEmpty() ? null : rows.get(rows.size() - 1).getTradingDate().toString(),
                rows.size(), nonPositive, warmupExcluded, evaluated, etfPremiumDays);
    }

    private List<String> notes(List<CodeRun> runs, List<String> failedCodes) {
        int etfPremiumMissing = (int) runs.stream()
                .filter(r -> r.coverage().etfPremiumDays() == 0)
                .filter(r -> r.coverage().code().startsWith("00"))
                .count();
        int dirty = runs.stream().mapToInt(r -> r.coverage().nonPositiveRows()).sum();
        List<String> out = new ArrayList<>(List.of(
                "本輸出為「歷史上此條件成立後的報酬分布」，不是預測。",
                "刻意不輸出 p 值與信賴區間：樣本嚴重自相關（連續多日同一訊號成立），古典檢定前提不成立。",
                "已剔除 close_price <= 0 的髒列共 " + dirty + " 列（既有資料品質問題，另立任務處理）。",
                "有 " + etfPremiumMissing + " 檔 ETF 在整段回測期間完全沒有折溢價資料；"
                        + "這些標的的 buyGate 與 OVERBOUGHT 統計未含折溢價否決，不得直接當成 production 行為的證據。",
                "volumeRatio 與 production 共用 20 日中位數，且已按股票股利／分割的股數因子還原成交量。",
                "n < " + MIN_SAMPLES + " 的格子已標記 sampleInsufficient，其數字不得用於決策。"));
        out.addAll(fundamentalCoverageNotes(runs));
        if (!failedCodes.isEmpty()) {
            out.add("有 " + failedCodes.size() + " 檔標的因讀取或還原失敗而未納入統計："
                    + String.join("、", failedCodes) + "。codeCount 已排除它們。");
        }
        return out;
    }

    /** 明示列出各基本面因子的有效訊號日與標的數，零覆蓋不得被誤解為無效。 */
    private List<String> fundamentalCoverageNotes(List<CodeRun> runs) {
        record Metric(String label, java.util.function.Function<TradingRadarRuleEngine.FundamentalInput, Double> value) {}
        List<Metric> metrics = List.of(
                new Metric("EPS 年增", TradingRadarRuleEngine.FundamentalInput::epsContribution),
                new Metric("近似 ROE", TradingRadarRuleEngine.FundamentalInput::roeContribution),
                new Metric("近三月營收年增", TradingRadarRuleEngine.FundamentalInput::revenueContribution),
                new Metric("PE 自身分位／可信虧損", TradingRadarRuleEngine.FundamentalInput::peContribution),
                new Metric("產業營收年增", TradingRadarRuleEngine.FundamentalInput::industryContribution));
        List<String> notes = new ArrayList<>();
        for (Metric metric : metrics) {
            int days = 0;
            int codes = 0;
            for (CodeRun run : runs) {
                int codeDays = 0;
                for (Obs observation : run.observations()) {
                    TradingRadarRuleEngine.FundamentalInput input = observation.fundamental();
                    if (input != null && input.applicable() && metric.value().apply(input) != null) codeDays++;
                }
                days += codeDays;
                if (codeDays > 0) codes++;
            }
            notes.add("基本面回測覆蓋—" + metric.label() + "：" + days + " 標的日／" + codes
                    + " 標的；0 覆蓋只代表當時尚無 as-of observation，不代表因子有效或無效。");
        }
        return notes;
    }

    // ─────────────────────────── CSV ───────────────────────────

    /**
     * 統計結果的 CSV 形式，供離線分析。欄位與 {@link BacktestDto.PredicateStat} 一一對應。
     *
     * <p>格式產生放在 service：本專案既有慣例一律如此（{@code ExcelExportService}、
     * {@code TradingCalendarExportService}、{@code AlertChartRenderer}），
     * controller 只負責包 {@code ResponseEntity}。</p>
     */
    public String toCsv(BacktestDto.Request request) {
        BacktestDto.Response r = run(request);
        StringBuilder sb = new StringBuilder();
        sb.append("predicate,held,horizon,n,sampleInsufficient,insufficientForward,")
          .append("mean,median,winRate,downsideRisk,p5,p25,p75,p95,")
          .append("baselineN,baselineMean,baselineMedian,baselineWinRate,baselineDownsideRisk,diffMean\n");
        for (BacktestDto.PredicateStat s : r.results()) {
            sb.append(csvCell(s.predicate())).append(',').append(s.held()).append(',').append(s.horizon())
              .append(',').append(s.n()).append(',').append(s.sampleInsufficient())
              .append(',').append(s.insufficientForward())
              .append(',').append(csvNum(s.mean())).append(',').append(csvNum(s.median()))
              .append(',').append(csvNum(s.winRate())).append(',').append(csvNum(s.downsideRisk()))
              .append(',').append(csvNum(s.p5())).append(',').append(csvNum(s.p25()))
              .append(',').append(csvNum(s.p75())).append(',').append(csvNum(s.p95()))
              .append(',').append(s.baselineN()).append(',').append(csvNum(s.baselineMean()))
              .append(',').append(csvNum(s.baselineMedian())).append(',').append(csvNum(s.baselineWinRate()))
              .append(',').append(csvNum(s.baselineDownsideRisk())).append(',').append(csvNum(s.diffMean()))
              .append('\n');
        }
        return sb.toString();
    }

    private String csvNum(BigDecimal v) { return v == null ? "" : v.toPlainString(); }

    private String csvCell(String v) {
        if (v == null) return "";
        return v.contains(",") || v.contains("\"") ? '"' + v.replace("\"", "\"\"") + '"' : v;
    }

    // ─────────────────────────── 統計小工具 ───────────────────────────

    private BigDecimal mean(List<BigDecimal> xs) {
        if (xs == null || xs.isEmpty()) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (BigDecimal x : xs) sum = sum.add(x);
        return sum.divide(BigDecimal.valueOf(xs.size()), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal percentile(List<BigDecimal> xs, int p) {
        if (xs == null || xs.isEmpty()) return null;
        List<BigDecimal> s = new ArrayList<>(xs);
        Collections.sort(s);
        double idx = (p / 100.0) * (s.size() - 1);
        int lo = (int) Math.floor(idx);
        int hi = (int) Math.ceil(idx);
        if (lo == hi) return s.get(lo).setScale(4, RoundingMode.HALF_UP);
        BigDecimal w = BigDecimal.valueOf(idx - lo);
        return s.get(lo).add(s.get(hi).subtract(s.get(lo)).multiply(w))
                .setScale(4, RoundingMode.HALF_UP);
    }

    private BigDecimal ratioAbove(List<BigDecimal> xs, BigDecimal bound) {
        if (xs == null || xs.isEmpty()) return null;
        long c = xs.stream().filter(x -> x.compareTo(bound) > 0).count();
        return BigDecimal.valueOf(100.0 * c / xs.size()).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal ratioAtOrBelow(List<BigDecimal> xs, BigDecimal bound) {
        if (xs == null || xs.isEmpty()) return null;
        long c = xs.stream().filter(x -> x.compareTo(bound) <= 0).count();
        return BigDecimal.valueOf(100.0 * c / xs.size()).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal diff(BigDecimal a, BigDecimal b) {
        return a == null || b == null ? null : a.subtract(b);
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (Exception e) {
            log.warn("回測：無法解析日期 {}，忽略該界限", s);
            return null;
        }
    }
}
