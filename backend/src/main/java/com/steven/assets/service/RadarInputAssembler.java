package com.steven.assets.service;

import com.steven.assets.model.StockDividendHistory;
import com.steven.assets.model.StockPriceHistory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
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
            VolatilityObservation volatility60
    ) {
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
                VolatilityObservation.unavailable(null, "沒有可用的 adjusted completed-price 序列"));
    }

    /**
     * @param combinedDesc      降序（新到舊）的 OHLC 序列；production 會在最前面插入當日 live K。
     * @param events            該序列日期區間內的除權息事件（分割由 adjust 自行以序列偵測）。
     * @param liveAdded         {@code combinedDesc} 的第 0 筆是否為尚未收盤的 live K。
     * @param completedRowCount 完成日 K 的筆數（用於切出 {@code completedCloses}）。
     * @param price             規則所用的現價。production 傳 live 價、回測傳該日還原收盤價。
     */
    public Assembled assemble(
            List<StockPriceHistory> combinedDesc,
            List<StockDividendHistory> events,
            boolean liveAdded,
            int completedRowCount,
            BigDecimal price) {

        if (combinedDesc == null || combinedDesc.isEmpty()) return Assembled.EMPTY;

        DistributionAdjustedPriceService.Adjustment adjustment =
                adjustedPriceService.adjust(new ArrayList<>(combinedDesc), events);
        List<StockPriceHistory> adjustedRows = adjustment.rowsDesc();

        int completedStart = liveAdded ? 1 : 0;
        int completedEnd = Math.min(completedStart + completedRowCount, adjustedRows.size());
        List<BigDecimal> completedCloses = completedEnd <= completedStart
                ? List.of()
                : adjustedRows.subList(completedStart, completedEnd).stream()
                        .map(StockPriceHistory::getClosePrice)
                        .toList();

        int indicatorRows = Math.min(adjustedRows.size(), liveAdded ? FULL_WINDOW + 1 : FULL_WINDOW);
        TechnicalIndicatorService.FullIndicators indicators =
                indicatorService.computeFromSeries(adjustedRows.subList(0, indicatorRows));

        BigDecimal previousAdjustedClose = adjustedRows.size() >= 2
                ? adjustedRows.get(1).getClosePrice()
                : null;

        // 最近一根完成日 K 相對前一根的漲跌幅（還原後價基），供逆勢「停止續跌」判定（Task 217.3）。
        int firstCompleted = liveAdded ? 1 : 0;
        BigDecimal completedChangePercent = adjustedRows.size() >= firstCompleted + 2
                ? changePercent(adjustedRows.get(firstCompleted).getClosePrice(),
                                adjustedRows.get(firstCompleted + 1).getClosePrice())
                : null;

        // Task 264：52 週高低與 9 日帶寬一律取自同一份還原序列，與 MA／KD 同一價基，
        // 不違反「禁止混用原始／還原價」。
        List<StockPriceHistory> window = adjustedRows.subList(0, indicatorRows);
        BigDecimal week52High = window.size() >= FULL_WINDOW ? maxHigh(window) : null;
        BigDecimal week52Low = window.size() >= FULL_WINDOW ? minLow(window) : null;
        BigDecimal kdBandWidthPercent = bandWidthPercent(
                adjustedRows.subList(0, Math.min(adjustedRows.size(), KD_BAND_ROWS)));

        // Task 299：現行乖離＝同一組還原序列算出的 live 價基乖離，分位在此之後才求值，
        // 使兩者恆為同一輸入的兩種摘要，不會各自漂移。
        BigDecimal ma60Bias = biasPercent(price, indicators.quarterlyMa());
        BigDecimal ma60BiasPct = ma60BiasPercentile(adjustedRows, firstCompleted, ma60Bias);
        LocalDate volatilityAsOf = adjustedRows.size() > firstCompleted
                ? adjustedRows.get(firstCompleted).getTradingDate() : null;
        VolatilityObservation volatility60 = returnStdDev60Ratio(completedCloses, volatilityAsOf);

        return new Assembled(
                indicators,
                adjustedRows,
                adjustment.adjusted(),
                completedCloses,
                previousAdjustedClose,
                completedChangePercent,
                week52High,
                week52Low,
                kdBandWidthPercent,
                ruleEngine.confirm(completedCloses, 20),
                ruleEngine.confirm(completedCloses, 60),
                ruleEngine.confirm(completedCloses, FULL_WINDOW),
                ma60Bias,
                ma60BiasPct,
                biasPercent(price, indicators.annualMa()),
                week52Position(price, week52High, week52Low),
                changePercent(price, previousAdjustedClose),
                volumeRatio(adjustedRows, firstCompleted),
                volatility60);
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
