package com.steven.assets.service;

import com.steven.assets.model.StockDividendHistory;
import com.steven.assets.model.StockPriceHistory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.time.LocalDate;

/**
 * 把「降序 OHLC 序列 ＋ 除權息／分割事件」組裝成 {@link TradingRadarRuleEngine.StockInput} 的技術面欄位。
 *
 * <p><b>存在的唯一理由是「production 與回測必須共用同一份組裝」</b>（Task 273 的 273.2b）。
 * {@code TradingRadarRuleEngine} 本身是純函數、不會漂移；真正會漂移的是這一段——它帶了大量
 * 會改變判定的細節：241 筆取數視窗、先還原再算指標的順序、{@code completedCloses} 的位移、
 * {@code indicatorRows} 的 240／241 分歧、52 週高低的缺值語意（不足 240 筆一律 null）、
 * 9 日帶寬只取前 9 筆、規則漲跌幅用還原後的前收。</p>
 *
 * <p>若回測端另寫一份，兩份會隨 production 演進而分歧，屆時回測量到的是一組<b>與線上不同的規則</b>，
 * 結論全部作廢且不會有任何報錯。故本類別是這段邏輯的<b>唯一擁有者</b>，
 * {@code TradingRadarService} 與 {@code BacktestService} 一律呼叫它，不得各自實作。</p>
 *
 * <p><b>純計算</b>：不注入 repository、不讀系統時間、不碰網路。事件與序列由呼叫端備妥後傳入，
 * 使同一組輸入恆得同一組輸出（回測可重現性的前提）。</p>
 */
@Component
@RequiredArgsConstructor
public class RadarInputAssembler {

    /** 需要 240 根完成日 K 才成立的因子（年線、年線確認、52 週位置）的最小視窗。 */
    public static final int FULL_WINDOW = 240;
    /** 9 日高低帶寬度的取樣筆數（窄幅 KD 失效判定，Task 264）。 */
    private static final int KD_BAND_ROWS = 9;
    /** 相對量分母取先前 20 個正成交量完成日，至少 10 筆才採用（Task 291）。 */
    private static final int VOLUME_LOOKBACK = 20;
    private static final int VOLUME_MIN_SAMPLES = 10;
    /** 季線乖離分位逐日回算所用的均線視窗天數；與 {@code quarterlyMa} 同義但為獨立計算（Task 299）。 */
    private static final int MA60_WINDOW = 60;
    /**
     * 季線乖離自身分位的最少有效觀測數（Task 299）。
     *
     * <p><b>判斷性取值，無回測依據</b>：240 個交易日（近一年）扣掉 60 日暖機後最多約 181 筆觀測，
     * 120 筆略多於半年，用以避免樣本過少時分位失去統計意義；未曾以回測校準此數字。</p>
     */
    private static final int BIAS_PCT_MIN_SAMPLES = 120;
    /**
     * 週K 指標成立的最少完成週根數（Task 356.3d）。
     *
     * <p>完成週不足此數時<b>整組</b>週K 指標為 {@code null}——不是部分算、不是以短序列硬算。
     * 下界來自週MACD：OSC 要到第 34 根完成週才有第一個值（EMA26 以 26 根 SMA 作 seed
     * → 第 26 根才有 DIF；signal 以 9 根 DIF 的 SMA 作 seed → 第 34 根才有 MACD），
     * 60 根留了 26 根的 EMA 收斂餘裕。</p>
     *
     * <p><b>此數字為判斷性取值、無回測依據</b>，不得於任何文案宣稱它能提高準確度。</p>
     */
    public static final int MIN_COMPLETED_WEEKS = 60;

    private final TechnicalIndicatorService indicatorService;
    private final DistributionAdjustedPriceService adjustedPriceService;
    private final TradingRadarRuleEngine ruleEngine;

    /** 還原完成日收盤序列的 realized volatility observation（ratio 口徑，不年化）。 */
    public record VolatilityObservation(
            BigDecimal returnStdDev60Ratio,
            LocalDate asOfDate,
            String source,
            String missingReason
    ) {
        public boolean available() {
            return returnStdDev60Ratio != null && returnStdDev60Ratio.signum() > 0;
        }

        public static VolatilityObservation unavailable(LocalDate asOfDate, String reason) {
            return new VolatilityObservation(null, asOfDate,
                    "DISTRIBUTION_ADJUSTED_COMPLETED_CLOSES", reason);
        }
    }

    /**
     * 組裝結果。除 {@code adjustedRowsDesc} 外，各欄位與 {@code StockInput} 的同名參數一一對應。
     *
     * @param adjustedRowsDesc     還原後的完整序列（降序）。回測用它算前瞻報酬，production 不使用。
     * @param week52High           還原序列前 240（含 live 則 241）筆的最高價；不足 240 筆為 null。
     * @param week52Low            同上的最低價。
     * @param kdBandWidthPercent   還原序列前 9 筆的高低帶寬度（%）。
     * @param ruleChangePercent    規則內部用的單日漲跌幅（還原價基）；前收缺值時為 null，
     *                             呼叫端自行決定是否 fallback 至市場報價漲跌幅。
     * @param volumeRatio          最新完成日還原成交量 ÷ 之前 20 個正成交量日中位數；分母排除最新日。
     * @param ma60BiasPercentile   {@code ma60BiasPercent} 在自身近一年分布中的分位（0–100），
     *                             供極端超買／超賣的分位路徑使用（Task 299）；有效觀測不足
     *                             {@link #BIAS_PCT_MIN_SAMPLES} 筆或 {@code ma60BiasPercent} 為 null 時為 null。
     * @param dailyCandle          最新完成日的還原 OHLC（Task 356.5a）；as-of 日與
     *                             {@code volatility60().asOfDate()} 同源（皆為
     *                             {@code adjustedRows.get(firstCompleted)} 的交易日）。
     * @param weekly               週K 因子輸入（Task 356.6）；完全沒有完成週時為 null，
     *                             完成週不足 {@link #MIN_COMPLETED_WEEKS} 時各指標欄為 null 但
     *                             {@code completedWeeks} 仍如實回報，供揭露文案寫出「目前 N 根」。
     * @param weeklyBarsDesc       實際進入週K 指標序列的完成週（降序，新到舊）：已排除進行中週，
     *                             也已排除 {@code high}／{@code low} 缺值的週（Task 356.3c-2）。
     * @param weeklyIndicators     週K 的 {@link TechnicalIndicatorService.FullIndicators}，是
     *                             {@code WeeklyIndicators.dif}／{@code macd} 純揭露欄的<b>唯一來源</b>
     *                             （Task 356.4h）；與 {@code weekly.osc()} 出自<b>同一次</b>
     *                             {@code computeFromSeries(週K 序列)} 呼叫，不得為了揭露再算第二次。
     */
    public record Assembled(
            TechnicalIndicatorService.FullIndicators indicators,
            List<StockPriceHistory> adjustedRowsDesc,
            boolean distributionAdjusted,
            List<BigDecimal> completedCloses,
            BigDecimal previousAdjustedClose,
            BigDecimal completedChangePercent,
            BigDecimal week52High,
            BigDecimal week52Low,
            BigDecimal kdBandWidthPercent,
            TradingRadarRuleEngine.Confirmation ma20Confirmation,
            TradingRadarRuleEngine.Confirmation ma60Confirmation,
            TradingRadarRuleEngine.Confirmation ma240Confirmation,
            BigDecimal ma60BiasPercent,
            BigDecimal ma60BiasPercentile,
            BigDecimal ma240BiasPercent,
            BigDecimal week52Position,
            BigDecimal ruleChangePercent,
            BigDecimal volumeRatio,
            VolatilityObservation volatility60,
            TradingRadarRuleEngine.CandleInput dailyCandle,
            TradingRadarRuleEngine.WeeklyInput weekly,
            List<WeeklyBarAggregator.WeeklyBar> weeklyBarsDesc,
            TechnicalIndicatorService.FullIndicators weeklyIndicators,
            /**
             * Weekly uses a longer actual input window than the daily 241-row
             * contract, so its raw/adjusted price basis is deliberately
             * independent of {@link #distributionAdjusted()}.
             */
            boolean weeklyDistributionAdjusted,
            TradingRadarRuleEngine.BollingerInput bollinger
    ) {
        /** Previous canonical shape, retained for old fixtures/snapshots with no Bollinger observation. */
        public Assembled(
            TechnicalIndicatorService.FullIndicators indicators,
            List<StockPriceHistory> adjustedRowsDesc,
            boolean distributionAdjusted,
            List<BigDecimal> completedCloses,
            BigDecimal previousAdjustedClose,
            BigDecimal completedChangePercent,
            BigDecimal week52High,
            BigDecimal week52Low,
            BigDecimal kdBandWidthPercent,
            TradingRadarRuleEngine.Confirmation ma20Confirmation,
            TradingRadarRuleEngine.Confirmation ma60Confirmation,
            TradingRadarRuleEngine.Confirmation ma240Confirmation,
            BigDecimal ma60BiasPercent,
            BigDecimal ma60BiasPercentile,
            BigDecimal ma240BiasPercent,
            BigDecimal week52Position,
            BigDecimal ruleChangePercent,
            BigDecimal volumeRatio,
            VolatilityObservation volatility60,
            TradingRadarRuleEngine.CandleInput dailyCandle,
            TradingRadarRuleEngine.WeeklyInput weekly,
            List<WeeklyBarAggregator.WeeklyBar> weeklyBarsDesc,
            TechnicalIndicatorService.FullIndicators weeklyIndicators,

            boolean weeklyDistributionAdjusted
) {
            this(indicators, adjustedRowsDesc, distributionAdjusted, completedCloses, previousAdjustedClose, completedChangePercent, week52High, week52Low, kdBandWidthPercent, ma20Confirmation, ma60Confirmation, ma240Confirmation, ma60BiasPercent, ma60BiasPercentile, ma240BiasPercent, week52Position, ruleChangePercent, volumeRatio, volatility60, dailyCandle, weekly, weeklyBarsDesc, weeklyIndicators, weeklyDistributionAdjusted, null);
        }

        /** t274 primitive 的欄位捷徑；正式 normalized action 尚未在此任務啟用。 */
        public BigDecimal returnStdDev60Ratio() {
            return volatility60 == null ? null : volatility60.returnStdDev60Ratio();
        }

        public static final Assembled EMPTY = new Assembled(
                TechnicalIndicatorService.FullIndicators.EMPTY, List.of(), false, List.of(),
                null, null, null, null, null,
                TradingRadarRuleEngine.Confirmation.UNAVAILABLE,
                TradingRadarRuleEngine.Confirmation.UNAVAILABLE,
                TradingRadarRuleEngine.Confirmation.UNAVAILABLE,
                null, null, null, null, null, null,
                VolatilityObservation.unavailable(null, "沒有可用的 adjusted completed-price 序列"),
                null, null, List.of(), TechnicalIndicatorService.FullIndicators.EMPTY, false);

        /** Compatibility shape before Task408 added the weekly price-basis flag. */
        public Assembled(
                TechnicalIndicatorService.FullIndicators indicators,
                List<StockPriceHistory> adjustedRowsDesc,
                boolean distributionAdjusted,
                List<BigDecimal> completedCloses,
                BigDecimal previousAdjustedClose,
                BigDecimal completedChangePercent,
                BigDecimal week52High,
                BigDecimal week52Low,
                BigDecimal kdBandWidthPercent,
                TradingRadarRuleEngine.Confirmation ma20Confirmation,
                TradingRadarRuleEngine.Confirmation ma60Confirmation,
                TradingRadarRuleEngine.Confirmation ma240Confirmation,
                BigDecimal ma60BiasPercent,
                BigDecimal ma60BiasPercentile,
                BigDecimal ma240BiasPercent,
                BigDecimal week52Position,
                BigDecimal ruleChangePercent,
                BigDecimal volumeRatio,
                VolatilityObservation volatility60,
                TradingRadarRuleEngine.CandleInput dailyCandle,
                TradingRadarRuleEngine.WeeklyInput weekly,
                List<WeeklyBarAggregator.WeeklyBar> weeklyBarsDesc,
                TechnicalIndicatorService.FullIndicators weeklyIndicators) {
            this(indicators, adjustedRowsDesc, distributionAdjusted, completedCloses,
                    previousAdjustedClose, completedChangePercent, week52High, week52Low,
                    kdBandWidthPercent, ma20Confirmation, ma60Confirmation, ma240Confirmation,
                    ma60BiasPercent, ma60BiasPercentile, ma240BiasPercent, week52Position,
                    ruleChangePercent, volumeRatio, volatility60, dailyCandle, weekly,
                    weeklyBarsDesc, weeklyIndicators, false);
        }
    }

    /**
     * Price-basis preparation intentionally stops before any MA/KD/RSI/MACD
     * formula.  Task408 can therefore bind a fresh LOCAL snapshot to this
     * exact basis and reuse it without calling {@code computeFromSeries}.
     */
    public record Prepared(
            List<StockPriceHistory> adjustedRowsDesc,
            List<StockPriceHistory> dailyContractRowsDesc,
            boolean distributionAdjusted,
            boolean weeklyDistributionAdjusted,
            List<BigDecimal> completedCloses,
            BigDecimal previousAdjustedClose,
            int firstCompletedIndex,
            int indicatorRows,
            BigDecimal price,
            List<WeeklyBarAggregator.WeeklyBar> weeklyBarsDesc,
            List<StockPriceHistory> weeklySeriesDesc
    ) {
        public static final Prepared EMPTY = new Prepared(List.of(), List.of(), false, false, List.of(), null,
                0, 0, null, List.of(), List.of());

        public boolean empty() { return adjustedRowsDesc == null || adjustedRowsDesc.isEmpty(); }
    }

    /**
     * @param combinedDesc      降序（新到舊）的 OHLC 序列；production 會在最前面插入當日 live K。
     *                          Task 356.4a 起這是<b>長視窗</b>（約 500 列），供週K 聚合取得
     *                          足夠的完成週；日K 路徑一律由 {@code dailyContractRows} 界定。
     * @param events            該序列日期區間內的除權息事件（分割由 adjust 自行以序列偵測）。
     * @param liveAdded         {@code combinedDesc} 的第 0 筆是否為尚未收盤的 live K。
     * @param completedRowCount 完成日 K 的筆數（用於切出 {@code completedCloses}）。
     *                          <b>仍以日K 契約的完成列數傳入</b>（上限 241），不得因為
     *                          {@code combinedDesc} 改成長序列就順手改傳長序列長度（Task 356.4d-3）。
     * @param dailyContractRows 日K 契約的<b>完成列</b>數（{@code RadarObservationResolver
     *                          .INDICATOR_SERIES_MAX_ROWS}，即 241）。
     *                          <b>⚠ live K 不計入</b>：實際視窗長度是
     *                          {@code dailyContractRows + (liveAdded ? 1 : 0)}。
     *                          寫成 {@code min(size, 241)} 會讓盤中 {@code ma60BiasPercentile}
     *                          的觀測數由 182 掉成 181（{@code i} 由 {@code firstCompleted=1} 跑到
     *                          {@code lastStart=242−60=182}），分位改值 → {@code TimingState} 改變
     *                          → {@code action} 改變，正好破壞這個參數原本要保護的不變式。
     *                          收盤後（{@code liveAdded=false}、{@code n=241}、{@code firstCompleted=0}）
     *                          觀測數同樣是 182，兩種情形必須都是 182。
     * @param price             規則所用的現價。production 傳 live 價、回測傳該日還原收盤價。
     */
    public Assembled assemble(
            List<StockPriceHistory> combinedDesc,
            List<StockDividendHistory> events,
            boolean liveAdded,
            int completedRowCount,
            int dailyContractRows,
            BigDecimal price) {

        return calculate(prepare(combinedDesc, events, liveAdded, completedRowCount, dailyContractRows, price));
    }

    /** Builds the immutable raw/adjusted price basis without indicator formulas. */
    public Prepared prepare(
            List<StockPriceHistory> combinedDesc,
            List<StockDividendHistory> events,
            boolean liveAdded,
            int completedRowCount,
            int dailyContractRows,
            BigDecimal price) {

        if (combinedDesc == null || combinedDesc.isEmpty()) return Prepared.EMPTY;

        // Task 356.4c：長視窗只查一次、只還原一次，日K 與週K 共用這一份還原結果。
        // 不得改為兩次查詢或兩次還原——兩份長度不同的序列各自跑分割偵測啟發式，結果可能分歧，
        // 會讓日K 與週K 在同一筆決策中用上兩種價基（既有鐵則明文禁止）。
        DistributionAdjustedPriceService.Adjustment adjustment =
                adjustedPriceService.adjust(new ArrayList<>(combinedDesc), events);
        List<StockPriceHistory> adjustedRows = adjustment.rowsDesc();
        // 日K 契約視窗：完成列 + live K（若有）。凡是「沒有自帶列數上限」的既有計算都必須吃這一份，
        // 否則會被長視窗靜默改值且不會有任何編譯錯誤或執行期例外。
        List<StockPriceHistory> dailyContractRowsDesc = adjustedRows.subList(0,
                Math.min(adjustedRows.size(),
                        Math.max(0, dailyContractRows) + (liveAdded ? 1 : 0)));

        int completedStart = liveAdded ? 1 : 0;
        int completedEnd = Math.min(completedStart + completedRowCount, adjustedRows.size());
        List<BigDecimal> completedCloses = completedEnd <= completedStart
                ? List.of()
                : adjustedRows.subList(completedStart, completedEnd).stream()
                        .map(StockPriceHistory::getClosePrice)
                        .toList();

        int indicatorRows = Math.min(adjustedRows.size(), liveAdded ? FULL_WINDOW + 1 : FULL_WINDOW);

        BigDecimal previousAdjustedClose = adjustedRows.size() >= 2
                ? adjustedRows.get(1).getClosePrice()
                : null;

        // firstCompleted is retained in Prepared because live/in-progress bars
        // are part of the binding but never count as a completed technical bar.
        int firstCompleted = liveAdded ? 1 : 0;

        // ── Task 356.2／356.3：週K 聚合與週K 指標（長視窗才有足夠完成週） ───────────────
        WeeklyBarAggregator.Aggregation weeklyAggregation = WeeklyBarAggregator.aggregate(adjustedRows);
        List<WeeklyBarAggregator.WeeklyBar> weeklyBarsDesc =
                weeklyIndicatorBars(weeklyAggregation.completedDesc());
        List<StockPriceHistory> weeklySeriesDesc = weeklySeriesDesc(weeklyBarsDesc);
        return new Prepared(List.copyOf(adjustedRows), List.copyOf(dailyContractRowsDesc),
                distributionAdjusted(adjustment, dailyContractRowsDesc), weeklyDistributionAdjusted(adjustment, weeklyBarsDesc),
                List.copyOf(completedCloses), previousAdjustedClose, firstCompleted, indicatorRows, price,
                List.copyOf(weeklyBarsDesc), List.copyOf(weeklySeriesDesc));
    }

    /** Runs normal local formulas after {@link #prepare}; callers may inject a fresh LOCAL snapshot. */
    public Assembled calculate(Prepared prepared) {
        return calculate(prepared, null, null, null);
    }

    /**
     * Rebuilds non-formula radar derivatives while reusing a validated LOCAL
     * technical snapshot.  Null overrides mean calculate locally; non-null
     * overrides (including EMPTY) are authoritative cache values.
     */
    public Assembled calculate(
            Prepared prepared,
            TechnicalIndicatorService.FullIndicators cachedIndicators,
            TradingRadarRuleEngine.WeeklyInput cachedWeekly,
            TechnicalIndicatorService.FullIndicators cachedWeeklyIndicators) {
        if (prepared == null || prepared.empty()) return Assembled.EMPTY;
        List<StockPriceHistory> adjustedRows = prepared.adjustedRowsDesc();
        int firstCompleted = prepared.firstCompletedIndex();
        TechnicalIndicatorService.FullIndicators indicators = cachedIndicators == null
                ? nonNull(indicatorService.computeFromSeries(adjustedRows.subList(0, prepared.indicatorRows())))
                : nonNull(cachedIndicators);
        BigDecimal completedChangePercent = prepared.completedCloses().size() >= 2
                ? changePercent(prepared.completedCloses().get(0), prepared.completedCloses().get(1)) : null;
        List<StockPriceHistory> window = adjustedRows.subList(0, prepared.indicatorRows());
        BigDecimal week52High = window.size() >= FULL_WINDOW ? maxHigh(window) : null;
        BigDecimal week52Low = window.size() >= FULL_WINDOW ? minLow(window) : null;
        BigDecimal kdBandWidthPercent = bandWidthPercent(
                adjustedRows.subList(0, Math.min(adjustedRows.size(), KD_BAND_ROWS)));
        BigDecimal ma60Bias = biasPercent(prepared.price(), indicators.quarterlyMa());
        BigDecimal ma60BiasPct = ma60BiasPercentile(prepared.dailyContractRowsDesc(), firstCompleted, ma60Bias);
        LocalDate volatilityAsOf = adjustedRows.size() > firstCompleted
                ? adjustedRows.get(firstCompleted).getTradingDate() : null;
        VolatilityObservation volatility60 = returnStdDev60Ratio(prepared.completedCloses(), volatilityAsOf);
        TechnicalIndicatorService.FullIndicators weeklyIndicators = cachedWeeklyIndicators == null
                ? prepared.weeklyBarsDesc().size() < MIN_COMPLETED_WEEKS
                        ? TechnicalIndicatorService.FullIndicators.EMPTY
                        : nonNull(indicatorService.computeFromSeries(prepared.weeklySeriesDesc()))
                : nonNull(cachedWeeklyIndicators);
        TradingRadarRuleEngine.WeeklyInput weekly = cachedWeekly == null
                ? weeklyInput(prepared.weeklyBarsDesc(), prepared.weeklySeriesDesc(), weeklyIndicators, prepared.price())
                : cachedWeekly;
        return new Assembled(
                indicators,
                adjustedRows,
                prepared.distributionAdjusted(),
                prepared.completedCloses(),
                prepared.previousAdjustedClose(),
                completedChangePercent,
                week52High,
                week52Low,
                kdBandWidthPercent,
                ruleEngine.confirm(prepared.completedCloses(), 20),
                ruleEngine.confirm(prepared.completedCloses(), 60),
                ruleEngine.confirm(prepared.completedCloses(), FULL_WINDOW),
                ma60Bias,
                ma60BiasPct,
                biasPercent(prepared.price(), indicators.annualMa()),
                week52Position(prepared.price(), week52High, week52Low),
                changePercent(prepared.price(), prepared.previousAdjustedClose()),
                volumeRatio(prepared.dailyContractRowsDesc(), firstCompleted),
                volatility60,
                candleAt(adjustedRows, firstCompleted),
                weekly,
                prepared.weeklyBarsDesc(),
                weeklyIndicators,
                prepared.weeklyDistributionAdjusted(),
                prepared.completedCloses().size() < 20 ? null
                        : completedBollinger(prepared.dailyContractRowsDesc(), firstCompleted));
    }

    /** Price-only calculation, including fresh-local cache hits: never consumes provider BB values. */
    public static TradingRadarRuleEngine.BollingerInput completedBollinger(
            List<StockPriceHistory> rowsDesc, int firstCompleted) {
        final int period = 20;
        if (rowsDesc == null || firstCompleted < 0 || rowsDesc.size() - firstCompleted < period) return null;
        MathContext context = MathContext.DECIMAL128;
        BigDecimal sum = BigDecimal.ZERO;
        LocalDate previousDate = null;
        for (int i = firstCompleted; i < firstCompleted + period; i++) {
            StockPriceHistory row = rowsDesc.get(i);
            if (row == null || row.getTradingDate() == null || row.getClosePrice() == null
                    || row.getClosePrice().signum() <= 0 || !Double.isFinite(row.getClosePrice().doubleValue())
                    || previousDate != null && !row.getTradingDate().isBefore(previousDate)) return null;
            previousDate = row.getTradingDate();
            sum = sum.add(row.getClosePrice(), context);
        }
        BigDecimal middle = sum.divide(BigDecimal.valueOf(period), context);
        BigDecimal squared = BigDecimal.ZERO;
        for (int i = firstCompleted; i < firstCompleted + period; i++) {
            BigDecimal deviation = rowsDesc.get(i).getClosePrice().subtract(middle, context);
            squared = squared.add(deviation.multiply(deviation, context), context);
        }
        BigDecimal sigma = squared.divide(BigDecimal.valueOf(period), context).sqrt(context);
        BigDecimal upper = middle.add(sigma.multiply(BigDecimal.valueOf(2), context), context);
        BigDecimal lower = middle.subtract(sigma.multiply(BigDecimal.valueOf(2), context), context);
        BigDecimal range = upper.subtract(lower, context);
        BigDecimal width = range.divide(middle, context).multiply(BigDecimal.valueOf(100), context);
        BigDecimal position = range.signum() == 0 ? null
                : rowsDesc.get(firstCompleted).getClosePrice().subtract(lower, context).divide(range, context);
        if (!Double.isFinite(middle.doubleValue()) || !Double.isFinite(upper.doubleValue())
                || !Double.isFinite(lower.doubleValue()) || !Double.isFinite(width.doubleValue())
                || position != null && !Double.isFinite(position.doubleValue())) return null;
        return new TradingRadarRuleEngine.BollingerInput(rowsDesc.get(firstCompleted).getTradingDate(), period, 2,
                bollingerScale(middle), bollingerScale(upper), bollingerScale(lower),
                position == null ? null : bollingerScale(position), bollingerScale(width));
    }

    private static BigDecimal bollingerScale(BigDecimal value) {
        return value.setScale(8, RoundingMode.HALF_UP);
    }

    /**
     * 大盤週K 的組裝結果（Task 356.10b）。
     *
     * @param weekly     餵給 {@code MarketInput.weekly()} 的因子輸入；完全沒有完成週時為 null。
     * @param barsDesc   實際進入指標序列的完成週（降序）；供 DTO 取最新完成週的成交量。
     * @param indicators 週K 的 {@link TechnicalIndicatorService.FullIndicators}，是
     *                   {@code WeeklyIndicators.dif}／{@code macd} 純揭露欄的唯一來源。
     */
    public record MarketWeekly(
            TradingRadarRuleEngine.WeeklyInput weekly,
            List<WeeklyBarAggregator.WeeklyBar> barsDesc,
            TechnicalIndicatorService.FullIndicators indicators
    ) {
        public static final MarketWeekly EMPTY =
                new MarketWeekly(null, List.of(), TechnicalIndicatorService.FullIndicators.EMPTY);
    }

    /**
     * 大盤（台股 TAIEX／美股 IXIC）的週K 聚合與週K 指標（Task 356.10b／356.10c）。
     *
     * <p><b>兩個市場共用這一支</b>：呼叫端先把各自的來源列（{@code twse_index_daily_history} 的
     * {@code open_point}／{@code high_point}／{@code low_point}／{@code close_point}／
     * {@code trade_volume}，或 {@code us_index_daily_history} 的同義欄）映射成中性的
     * {@link StockPriceHistory} OHLCV 形狀，再交給本方法；<b>不得為每個來源各寫一份聚合</b>。</p>
     *
     * <p><b>必須餵長清單（約 500 列）</b>：日K 契約的 241 列 ≈ 48 個 ISO 週 &lt;
     * {@link #MIN_COMPLETED_WEEKS}，餵契約列等於讓大盤週K 因子永遠缺值，
     * 而畫面只會多一則「不採計」風險句、沒有任何錯誤訊息。</p>
     *
     * <p>個股走的是 {@link #assemble} 內同一組私有 helper（{@code weeklyIndicatorBars}／
     * {@code weeklySeriesDesc}／{@code weeklyInput}），故大盤與個股的週界、丟棄規則、
     * 最少樣本與精度<b>逐項相同</b>。大盤序列不做還原（指數無除權息），
     * 這與個股的價基差異是既有且刻意的。</p>
     *
     * @param rowsDesc 降序（新到舊）的中性 OHLCV 長序列。
     * @param price    大盤現價，供 {@code bias10}／{@code bias20} 以「現價 vs 週均線」求值
     *                 （與日K 路徑的 {@code ma60BiasPercent} 同一慣例）。
     */
    public MarketWeekly marketWeekly(List<StockPriceHistory> rowsDesc, BigDecimal price) {
        if (rowsDesc == null || rowsDesc.isEmpty()) return MarketWeekly.EMPTY;
        WeeklyBarAggregator.Aggregation aggregation = WeeklyBarAggregator.aggregate(rowsDesc);
        List<WeeklyBarAggregator.WeeklyBar> barsDesc = weeklyIndicatorBars(aggregation.completedDesc());
        List<StockPriceHistory> seriesDesc = weeklySeriesDesc(barsDesc);
        TechnicalIndicatorService.FullIndicators indicators =
                barsDesc.size() < MIN_COMPLETED_WEEKS
                        ? TechnicalIndicatorService.FullIndicators.EMPTY
                        : nonNull(indicatorService.computeFromSeries(seriesDesc));
        return new MarketWeekly(
                weeklyInput(barsDesc, seriesDesc, indicators, price), barsDesc, indicators);
    }

    /**
     * 序列是否真的被還原過——判準是「<b>存在事件日期嚴格晚於日K 契約視窗最舊一列的交易日</b>」
     * （Task 356.4e），不是 {@code Adjustment.adjusted()}。
     *
     * <p>{@code priceScale(i) = cumulative(i) / finalPriceGrowth}：若視窗內最舊一列的日期已晚於
     * （或等於）全部事件日，視窗內每一列的 {@code cumulative} 都等於 {@code final}、
     * {@code priceScale} 恆為 1，還原後的價格在<b>數值上</b>與原始價相同。此時宣稱
     * 「MA／KD 已使用還原權息價」是對使用者的假陳述——實際上什麼都沒還原。</p>
     *
     * <p>「嚴格晚於」不是筆誤：事件日恰等於最舊一列時，該事件在該列即已計入 {@code cumulative}，
     * 視窗內仍無任何一列被縮放。擴窗（250 → 500）之後若沿用「{@code adjusted()} 為真即為真」，
     * 除息日落在 12–24 個月前的標的會由 {@code false} 翻 {@code true}，
     * {@code reasons}／{@code shortReasons} 因此各多一則還原揭露句、
     * {@code StockDecision.distributionAdjusted}（OpenAPI {@code required} 欄）也跟著翻面。</p>
     */
    private static boolean distributionAdjusted(
            DistributionAdjustedPriceService.Adjustment adjustment,
            List<StockPriceHistory> dailyContractRowsDesc) {
        if (adjustment == null || !adjustment.adjusted() || dailyContractRowsDesc.isEmpty()) return false;
        LocalDate oldest = dailyContractRowsDesc.get(dailyContractRowsDesc.size() - 1).getTradingDate();
        if (oldest == null) return adjustment.adjusted();
        return adjustment.appliedEventDates().stream()
                .anyMatch(date -> date != null && date.isAfter(oldest));
    }

    /** Same evidence rule, evaluated against the much longer weekly input window. */
    private static boolean weeklyDistributionAdjusted(
            DistributionAdjustedPriceService.Adjustment adjustment,
            List<WeeklyBarAggregator.WeeklyBar> weeklyBarsDesc) {
        if (adjustment == null || !adjustment.adjusted() || weeklyBarsDesc == null || weeklyBarsDesc.isEmpty()) {
            return false;
        }
        LocalDate oldest = weeklyBarsDesc.get(weeklyBarsDesc.size() - 1).weekEndDate();
        if (oldest == null) return adjustment.adjusted();
        return adjustment.appliedEventDates().stream()
                .anyMatch(date -> date != null && date.isAfter(oldest));
    }

    /** 指定索引那一根還原 K 棒的 OHLC；索引越界時回 null。 */
    private static TradingRadarRuleEngine.CandleInput candleAt(
            List<StockPriceHistory> adjustedRowsDesc, int index) {
        if (adjustedRowsDesc == null || index < 0 || index >= adjustedRowsDesc.size()) return null;
        StockPriceHistory row = adjustedRowsDesc.get(index);
        return new TradingRadarRuleEngine.CandleInput(
                row.getOpenPrice(), row.getHighPrice(), row.getLowPrice(), row.getClosePrice());
    }

    /**
     * 進入週K 指標序列的完成週（Task 356.3c-2）：{@code high}／{@code low} 為 null 的週<b>整根丟棄</b>。
     *
     * <p>理由：{@code TechnicalIndicatorService} 的 KD 與 DI 價基 MACD 內部都有「{@code high}／
     * {@code low} 缺值時 fallback 用 {@code close}」的既有慣例（其 Javadoc 自述是為了 {@code 0000}
     * 大盤舊資料）。若把 {@code high}／{@code low} 為 null 的週K 直接丟進 {@code computeFromSeries}，
     * 那條 fallback 會<b>靜默</b>把它變成一根收盤價＝最高＝最低的退化偽K，與
     * {@code WeeklyBarAggregator} 明訂的「不得以 close 冒充 open／high／low」完全相反且無任何揭露。
     * {@code stock_price_history} 與 {@code twse_index_daily_history} 的高低欄都是 nullable，
     * 舊列確實有 null，這不是理論風險。</p>
     */
    private static List<WeeklyBarAggregator.WeeklyBar> weeklyIndicatorBars(
            List<WeeklyBarAggregator.WeeklyBar> completedDesc) {
        if (completedDesc == null || completedDesc.isEmpty()) return List.of();
        return completedDesc.stream()
                .filter(bar -> bar != null && bar.high() != null && bar.low() != null)
                .toList();
    }

    /** 週K → {@link StockPriceHistory} 的中性映射，供既有 {@code computeFromSeries} 直接取用。 */
    private static List<StockPriceHistory> weeklySeriesDesc(
            List<WeeklyBarAggregator.WeeklyBar> barsDesc) {
        return barsDesc.stream()
                .map(bar -> StockPriceHistory.builder()
                        .tradingDate(bar.weekEndDate())
                        .openPrice(bar.open())
                        .highPrice(bar.high())
                        .lowPrice(bar.low())
                        .closePrice(bar.close())
                        .volume(bar.volume())
                        .build())
                .toList();
    }

    /**
     * 週K 因子輸入（Task 356.3c／356.3d／356.3e／356.3f）。
     *
     * <p>指標一律取自<b>同一次</b> {@code computeFromSeries(週K 序列)}——換序列不換公式，
     * 禁止為週K 另寫一份 KD／MACD／RSI／SMA。{@code bias10}／{@code bias20} 例外：一律以
     * {@code biasPercent(現價, 週MA10／週MA20)} 另算，<b>不得</b>取用
     * {@code ExtendedIndicators.bias10}／{@code bias20}——後者的分子是序列最新一根的收盤，
     * 對週K 而言是「上一個完成週的最後交易日收盤」，拿它算乖離等於用一週前的價格判斷
     * 「現在進場貴不貴」。日K 路徑的 {@code ma60BiasPercent} 早已是同一作法。</p>
     *
     * <p>{@code volumeRatio} 直接重用日K 的 {@link #volumeRatio}（最新完成週 ÷ 之前 20 根正成交量
     * 完成週的中位數、分母排除最新週、正樣本少於 10 回 null），<b>不另立規則</b>。</p>
     */
    private TradingRadarRuleEngine.WeeklyInput weeklyInput(
            List<WeeklyBarAggregator.WeeklyBar> barsDesc,
            List<StockPriceHistory> seriesDesc,
            TechnicalIndicatorService.FullIndicators ind,
            BigDecimal price) {
        if (barsDesc.isEmpty()) return null;
        WeeklyBarAggregator.WeeklyBar latest = barsDesc.get(0);
        int completedWeeks = barsDesc.size();
        if (completedWeeks < MIN_COMPLETED_WEEKS) {
            // 整組指標缺值，但 completedWeeks 仍如實回報：揭露文案要寫得出「目前 N 根」。
            return new TradingRadarRuleEngine.WeeklyInput(
                    null, null, null, null, null, null, null, null, null, null, null, null,
                    null, null, latest.weekEndDate(), completedWeeks);
        }
        TechnicalIndicatorService.ExtendedIndicators extended = ind.extended();
        return new TradingRadarRuleEngine.WeeklyInput(
                new TradingRadarRuleEngine.CandleInput(
                        latest.open(), latest.high(), latest.low(), latest.close()),
                scale2(ind.weeklyMa()),
                scale2(ind.ma10()),
                scale2(ind.monthlyMa()),
                scale2(ind.k()),
                scale2(ind.d()),
                extended == null ? null : scale2(extended.j9()),
                extended == null ? null : scale2(extended.osc()),
                extended == null ? null : scale2(extended.rsi5()),
                extended == null ? null : scale2(extended.rsi10()),
                scale2(biasPercent(price, ind.ma10())),
                scale2(biasPercent(price, ind.monthlyMa())),
                scale2(volumeRatio(seriesDesc, 0)),
                scale2(changePercent(latest.close(), barsDesc.get(1).close())),
                latest.weekEndDate(),
                completedWeeks);
    }

    /** 週K 指標一律 2 位小數；缺值為 null，不得以 0 冒充（Task 356.3f）。 */
    private static BigDecimal scale2(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
    }

    private static TechnicalIndicatorService.FullIndicators nonNull(
            TechnicalIndicatorService.FullIndicators value) {
        return value == null ? TechnicalIndicatorService.FullIndicators.EMPTY : value;
    }

    /**
     * t274 共用 σ primitive：最近 60 個 adjusted completed-price 日報酬的樣本標準差。
     *
     * <p>輸入為降序價格（最新在前），故需要 61 根價格。live 列不應傳入；production
     * 由 {@code completedCloses} 切出，backtest 由同一 assembler 切片。任何非正值、非有限值、
     * 不足 61 根或常數序列都回 unavailable，不以 sigma floor 偽造可用波動。</p>
     */
    public VolatilityObservation returnStdDev60Ratio(
            List<BigDecimal> completedClosesDesc, LocalDate asOfDate) {
        final int priceCount = 61;
        if (completedClosesDesc == null || completedClosesDesc.size() < priceCount) {
            return VolatilityObservation.unavailable(asOfDate, "sigma_insufficient_prices");
        }
        List<BigDecimal> returns = new ArrayList<>(60);
        for (int i = 0; i < 60; i++) {
            BigDecimal current = completedClosesDesc.get(i);
            BigDecimal previous = completedClosesDesc.get(i + 1);
            if (!positiveFinite(current) || !positiveFinite(previous)) {
                return VolatilityObservation.unavailable(asOfDate, "sigma_non_positive_or_non_finite_price");
            }
            returns.add(current.divide(previous, 16, RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE));
        }
        BigDecimal mean = returns.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(returns.size()), 20, RoundingMode.HALF_UP);
        BigDecimal sumSquares = BigDecimal.ZERO;
        for (BigDecimal value : returns) {
            BigDecimal delta = value.subtract(mean);
            sumSquares = sumSquares.add(delta.multiply(delta));
        }
        BigDecimal variance = sumSquares.divide(BigDecimal.valueOf(returns.size() - 1),
                24, RoundingMode.HALF_UP);
        double varianceDouble = variance.doubleValue();
        if (!(varianceDouble > 0.0) || Double.isInfinite(varianceDouble) || Double.isNaN(varianceDouble)) {
            return VolatilityObservation.unavailable(asOfDate, "sigma_non_positive");
        }
        BigDecimal sigma = BigDecimal.valueOf(Math.sqrt(varianceDouble))
                .setScale(12, RoundingMode.HALF_UP);
        if (!positiveFinite(sigma)) {
            return VolatilityObservation.unavailable(asOfDate, "sigma_non_positive");
        }
        return new VolatilityObservation(
                sigma, asOfDate, "DISTRIBUTION_ADJUSTED_COMPLETED_CLOSES", null);
    }

    private static boolean positiveFinite(BigDecimal value) {
        if (value == null || value.signum() <= 0) return false;
        double d = value.doubleValue();
        return !Double.isNaN(d) && !Double.isInfinite(d);
    }

    /** {@link TechnicalIndicatorService.FullIndicators} → 引擎的 {@code Indicators}。 */
    public TradingRadarRuleEngine.Indicators indicators(TechnicalIndicatorService.FullIndicators ind) {
        return new TradingRadarRuleEngine.Indicators(
                ind.monthlyMa(), ind.quarterlyMa(), ind.annualMa(), ind.k(), ind.d());
    }

    /** 指標服務的擴充值只在此轉成規則引擎輸入，production 與回測不各自複製接線。 */
    public TradingRadarRuleEngine.ExtendedIndicators extendedIndicators(
            TechnicalIndicatorService.ExtendedIndicators e) {
        if (e == null) return null;
        return new TradingRadarRuleEngine.ExtendedIndicators(
                e.j9(), e.k3d2(), e.rsv(), e.ema12(), e.ema26(), e.dif(), e.macd(), e.osc(),
                e.rsi5(), e.rsi10(), e.bias10(), e.bias20(), e.b10b20(), e.wr9());
    }

    /**
     * 最新完成日相對量。成交量已由 {@link DistributionAdjustedPriceService} 依股數事件還原；
     * 最新日不得進入自己的基準，中位數可避免單一爆量日拉歪分母。
     */
    public BigDecimal volumeRatio(List<StockPriceHistory> adjustedRowsDesc, int latestCompletedIndex) {
        if (adjustedRowsDesc == null || latestCompletedIndex < 0
                || latestCompletedIndex >= adjustedRowsDesc.size()) return null;
        Long current = adjustedRowsDesc.get(latestCompletedIndex).getVolume();
        if (current == null || current <= 0) return null;
        List<Long> prior = new ArrayList<>();
        for (int i = latestCompletedIndex + 1;
             i < adjustedRowsDesc.size() && prior.size() < VOLUME_LOOKBACK; i++) {
            Long value = adjustedRowsDesc.get(i).getVolume();
            if (value != null && value > 0) prior.add(value);
        }
        if (prior.size() < VOLUME_MIN_SAMPLES) return null;
        prior.sort(Comparator.naturalOrder());
        BigDecimal median;
        int n = prior.size();
        if (n % 2 == 1) {
            median = BigDecimal.valueOf(prior.get(n / 2));
        } else {
            median = BigDecimal.valueOf(prior.get(n / 2 - 1))
                    .add(BigDecimal.valueOf(prior.get(n / 2)))
                    .divide(BigDecimal.valueOf(2), 8, RoundingMode.HALF_UP);
        }
        if (median.signum() <= 0) return null;
        return BigDecimal.valueOf(current).divide(median, 4, RoundingMode.HALF_UP);
    }

    /** 還原序列的最高價；全為 null 時回 null（不得以 0 充當）。 */
    public BigDecimal maxHigh(List<StockPriceHistory> rows) {
        BigDecimal max = null;
        for (StockPriceHistory r : rows) {
            BigDecimal h = r.getHighPrice() != null ? r.getHighPrice() : r.getClosePrice();
            if (h == null) continue;
            if (max == null || h.compareTo(max) > 0) max = h;
        }
        return max;
    }

    /** 還原序列的最低價；全為 null 時回 null。 */
    public BigDecimal minLow(List<StockPriceHistory> rows) {
        BigDecimal min = null;
        for (StockPriceHistory r : rows) {
            BigDecimal l = r.getLowPrice() != null ? r.getLowPrice() : r.getClosePrice();
            if (l == null) continue;
            if (min == null || l.compareTo(min) < 0) min = l;
        }
        return min;
    }

    /** 9 日高低帶寬度（%）：不足 9 筆、取不到高低或低點非正時回 null（缺值視同未觸發保護）。 */
    public BigDecimal bandWidthPercent(List<StockPriceHistory> rows) {
        if (rows.size() < KD_BAND_ROWS) return null;
        BigDecimal hi = maxHigh(rows);
        BigDecimal lo = minLow(rows);
        if (hi == null || lo == null || lo.signum() <= 0) return null;
        return hi.subtract(lo)
                .divide(lo, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    /** 現價對均線的乖離率（%）；均線缺值或非正時回 null。 */
    public BigDecimal biasPercent(BigDecimal price, BigDecimal ma) {
        if (price == null || ma == null || ma.signum() <= 0) return null;
        return price.subtract(ma)
                .divide(ma, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    /**
     * 季線乖離在自身近一年分布中的分位（0–100），供極端超買／超賣的分位路徑使用（Task 299）。
     *
     * <p>逐日回算「當日收盤對當日 MA60 的乖離」：第 i 日（{@code adjustedRowsDesc} 的降序索引）
     * 的 MA60 取 {@code adjustedRowsDesc[i..i+59]} 共 60 筆收盤均值（與 {@link #biasPercent} 同式，
     * 含當日自身），不足 60 筆的日子不產生觀測值；{@code firstCompleted} 之前的列（如 live K）
     * 不進入觀測分布，與 {@code completedCloses} 同一起點。均線以 rolling sum 遞增，全程 O(n)、
     * 不查 DB。有效觀測數低於 {@link #BIAS_PCT_MIN_SAMPLES} 或現行乖離為 null 時回 null。</p>
     *
     * <p>抽成 public 純方法（比照 {@link #volumeRatio}／{@link #bandWidthPercent}）是刻意的：
     * 測試可直接餵序列驗證，不必為了走 {@link #assemble} 而 stub
     * {@code DistributionAdjustedPriceService.adjust}／{@code TechnicalIndicatorService.computeFromSeries}。</p>
     *
     * @param adjustedRowsDesc   還原後序列（降序，新到舊），與 {@code assemble} 內部同一份。
     * @param firstCompleted     第一筆完成日的索引（{@code liveAdded ? 1 : 0}）。
     * @param currentBiasPercent 現行乖離（%），即 {@code assemble} 已算出的 {@code ma60BiasPercent}（live 價基）。
     */
    public BigDecimal ma60BiasPercentile(
            List<StockPriceHistory> adjustedRowsDesc, int firstCompleted, BigDecimal currentBiasPercent) {
        if (currentBiasPercent == null || adjustedRowsDesc == null) return null;
        int n = adjustedRowsDesc.size();
        int lastStart = n - MA60_WINDOW;
        if (lastStart < firstCompleted) return null;

        BigDecimal sum = BigDecimal.ZERO;
        for (int j = firstCompleted; j < firstCompleted + MA60_WINDOW; j++) {
            sum = sum.add(adjustedRowsDesc.get(j).getClosePrice());
        }
        List<BigDecimal> observations = new ArrayList<>();
        for (int i = firstCompleted; i <= lastStart; i++) {
            if (i > firstCompleted) {
                sum = sum.subtract(adjustedRowsDesc.get(i - 1).getClosePrice())
                        .add(adjustedRowsDesc.get(i + MA60_WINDOW - 1).getClosePrice());
            }
            BigDecimal ma = sum.divide(BigDecimal.valueOf(MA60_WINDOW), 8, RoundingMode.HALF_UP);
            BigDecimal bias = biasPercent(adjustedRowsDesc.get(i).getClosePrice(), ma);
            if (bias != null) observations.add(bias);
        }
        if (observations.size() < BIAS_PCT_MIN_SAMPLES) return null;

        long countAtOrBelow = observations.stream()
                .filter(b -> b.compareTo(currentBiasPercent) <= 0)
                .count();
        return BigDecimal.valueOf(100)
                .multiply(BigDecimal.valueOf(countAtOrBelow))
                .divide(BigDecimal.valueOf(observations.size()), 8, RoundingMode.HALF_UP)
                .setScale(1, RoundingMode.HALF_UP);
    }

    /** 52 週相對位置，clamp 至 [0,1]；高低缺值或區間為 0 時回 null。 */
    public BigDecimal week52Position(BigDecimal price, BigDecimal high, BigDecimal low) {
        if (price == null || high == null || low == null) return null;
        BigDecimal range = high.subtract(low);
        if (range.signum() <= 0) return null;
        BigDecimal pos = price.subtract(low).divide(range, 8, RoundingMode.HALF_UP);
        if (pos.signum() < 0) return BigDecimal.ZERO;
        return pos.compareTo(BigDecimal.ONE) > 0 ? BigDecimal.ONE : pos;
    }

    /** 單日漲跌幅（%）；任一端缺值或前收為 0 時回 null。 */
    public BigDecimal changePercent(BigDecimal current, BigDecimal previous) {
        if (current == null || previous == null || previous.signum() == 0) return null;
        return current.subtract(previous)
                .divide(previous, 8, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(4, RoundingMode.HALF_UP);
    }
}
