package com.steven.assets.service;

import com.steven.assets.model.StockDividendHistory;
import com.steven.assets.model.StockPriceHistory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

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

    private final TechnicalIndicatorService indicatorService;
    private final DistributionAdjustedPriceService adjustedPriceService;
    private final TradingRadarRuleEngine ruleEngine;

    /**
     * 組裝結果。除 {@code adjustedRowsDesc} 外，各欄位與 {@code StockInput} 的同名參數一一對應。
     *
     * @param adjustedRowsDesc     還原後的完整序列（降序）。回測用它算前瞻報酬，production 不使用。
     * @param week52High           還原序列前 240（含 live 則 241）筆的最高價；不足 240 筆為 null。
     * @param week52Low            同上的最低價。
     * @param kdBandWidthPercent   還原序列前 9 筆的高低帶寬度（%）。
     * @param ruleChangePercent    規則內部用的單日漲跌幅（還原價基）；前收缺值時為 null，
     *                             呼叫端自行決定是否 fallback 至市場報價漲跌幅。
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
            BigDecimal ma240BiasPercent,
            BigDecimal week52Position,
            BigDecimal ruleChangePercent
    ) {
        public static final Assembled EMPTY = new Assembled(
                TechnicalIndicatorService.FullIndicators.EMPTY, List.of(), false, List.of(),
                null, null, null, null, null,
                TradingRadarRuleEngine.Confirmation.UNAVAILABLE,
                TradingRadarRuleEngine.Confirmation.UNAVAILABLE,
                TradingRadarRuleEngine.Confirmation.UNAVAILABLE,
                null, null, null, null);
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
                biasPercent(price, indicators.quarterlyMa()),
                biasPercent(price, indicators.annualMa()),
                week52Position(price, week52High, week52Low),
                changePercent(price, previousAdjustedClose));
    }

    /** {@link TechnicalIndicatorService.FullIndicators} → 引擎的 {@code Indicators}。 */
    public TradingRadarRuleEngine.Indicators indicators(TechnicalIndicatorService.FullIndicators ind) {
        return new TradingRadarRuleEngine.Indicators(
                ind.monthlyMa(), ind.quarterlyMa(), ind.annualMa(), ind.k(), ind.d());
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
