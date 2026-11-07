package com.steven.assets.service;

import com.steven.assets.model.StockDividendHistory;
import com.steven.assets.model.CanonicalDividendFinancialGuard;
import com.steven.assets.model.StockPriceHistory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 把原始 OHLCV 轉成以最新價基與股數基準還原的權息／分割序列。
 *
 * <p>只做純計算、不寫回 entity 或資料庫；沒有可用事件時原值返回，避免無謂精度漂移。</p>
 *
 * <p><b>Task 265：分割偵測不得依賴除權息事件存在。</b>台股沒有合規且可程式化存取的分割事件來源
 * （MOPS {@code robots.txt} 為 {@code Disallow: /}），故採序列啟發式。實測全庫兩筆真分割中的
 * {@code 2327} 在 {@code stock_dividend_history} <b>完全沒有紀錄</b>，若沿用「無事件即原樣返回」
 * 的舊結構，對它永遠不會生效卻也不報錯。</p>
 */
@Component
@Slf4j
public class DistributionAdjustedPriceService {

    private static final BigDecimal STOCK_PAR_VALUE = BigDecimal.TEN;
    private static final int SCALE = 12;

    /** 單日跌幅 ≥ 50% 才視為疑似正向分割（Task 265）。 */
    private static final double SPLIT_FORWARD_MIN = 2.0;
    /** 單日漲幅 ≥ 100% 才視為疑似反向分割（Task 265）。 */
    private static final double SPLIT_REVERSE_MAX = 0.5;
    /**
     * 美股 price-only 路徑的除息後退避根數（Task 565）：序列最近這麼多根（含除息日當根）內
     * 若有配息事件的有效日，price-only 不成立、整組技術指標退回不可用。
     *
     * <p><b>判斷性取值，依據離線實證（Task 565.7）</b>：未做配息還原的收盤價在除息日向下跳一格，
     * KD 要約 14～15 個交易日才讓缺口退出視窗。此數字只涵蓋「缺口仍在視窗內」的時段，
     * <b>不涵蓋</b> 52 週位置的持續性偏低；不得於任何文案宣稱它能提高準確度。</p>
     */
    static final int US_PRICE_ONLY_EX_DIVIDEND_COOLDOWN_BARS = 15;
    /**
     * 二次驗證的相對誤差容差。
     *
     * <p><b>不得放寬到 ±15% 之類的小值。</b>實測全台股序列中 ±15% 以上的跳空共 21 筆，其中僅 2 筆
     * 為真分割（{@code 0050 −74.8%}、{@code 2327 −73.8%}），其餘 19 筆幅度在 −22%～+47%（停牌復牌、
     * 資料源缺日等）。小門檻會產生大量誤報而把序列改壞——<b>改壞序列比不還原更糟</b>，因為前者
     * 無法從畫面察覺。</p>
     */
    private static final double SPLIT_RATIO_TOLERANCE = 0.10;
    /** 二次驗證比對的標準分割比例（含其倒數）。 */
    private static final int[] SPLIT_CANONICAL_RATIOS = {2, 3, 4, 5, 10};

    /**
     * 還原結果。
     *
     * @param rowsDesc          還原後的序列（降序）；無事件時為原物件（scale 維持
     *                          {@code numeric(15,4)} 的 4），走縮放路徑時 scale 為 8。
     *                          <b>兩者數值可能相同但 {@code equals} 不同，比對一律用
     *                          {@code compareTo}。</b>
     * @param adjusted          序列是否真的被縮放過（存在 growth ≠ 1 的事件）。
     * @param appliedEventDates growth ≠ 1 的事件日期，<b>升冪</b>（Task 356.4e）。
     *                          呼叫端據此判斷「事件是否落在自己的視窗內」——
     *                          {@code priceScale(i) = cumulative(i) / finalPriceGrowth}，
     *                          若視窗內最舊一列的日期已晚於（或等於）全部事件日，視窗內每一列的
     *                          {@code cumulative} 都等於 {@code final}、{@code priceScale} 恆為 1，
     *                          此時宣稱「已使用還原權息價」是對使用者的假陳述。判準因此必須是
     *                          「事件日期<b>嚴格晚於</b>視窗最舊一列」而不是「視窗日期區間內有事件」。
     */
    public record Adjustment(
            List<StockPriceHistory> rowsDesc,
            boolean adjusted,
            List<LocalDate> appliedEventDates) {

        /** 既有兩參數形狀的相容建構式（呼叫端尚未關心事件日期時使用）。 */
        public Adjustment(List<StockPriceHistory> rowsDesc, boolean adjusted) {
            this(rowsDesc, adjusted, List.of());
        }
    }

    /**
     * 內部統一事件。{@code priceGrowth} 含現金股利、股票股利與分割；{@code shareGrowth}
     * 僅代表事件後股數／事件前股數，現金股利恆為 1，避免拿價格因子污染成交量。
     */
    private record FactorEvent(
            LocalDate date,
            BigDecimal priceGrowth,
            BigDecimal shareGrowth,
            boolean split
    ) {}

    public Adjustment adjust(
            List<StockPriceHistory> rowsDesc,
            List<StockDividendHistory> rawEvents) {
        if (rowsDesc == null || rowsDesc.isEmpty()) {
            return new Adjustment(List.of(), false);
        }

        List<StockPriceHistory> rowsAsc = new ArrayList<>(rowsDesc);
        rowsAsc.sort(Comparator.comparing(StockPriceHistory::getTradingDate));
        var firstDate = rowsAsc.get(0).getTradingDate();
        var lastDate = rowsAsc.get(rowsAsc.size() - 1).getTradingDate();

        var canonical = CanonicalDividendFinancialFacts.assess(rawEvents, firstDate, lastDate);
        canonical.requireComparablePriceBasis();
        // Canonical cash/stock amounts for the same effective date form one factor.
        // Multiplying per-row factors would add an artificial cash cross term.
        List<FactorEvent> events = new ArrayList<>(detectSplits(rowsAsc, canonical.events()));
        for (CanonicalDividendFinancialGuard.EffectiveEvent event : canonical.events()) {
            BigDecimal stock = event.stock().divide(STOCK_PAR_VALUE, SCALE, RoundingMode.HALF_UP);
            BigDecimal close = closeOn(rowsAsc, event.date());
            BigDecimal cash = event.cash().signum() > 0 && close != null && close.signum() > 0
                    ? event.cash().divide(close, SCALE, RoundingMode.HALF_UP) : BigDecimal.ZERO;
            if (event.cash().signum() > 0 && (close == null || close.signum() <= 0))
                throw new CanonicalDividendFinancialGuard.Unavailable("DIVIDEND_EFFECTIVE_CLOSE_UNAVAILABLE");
            if (event.cash().signum() > 0 || event.stock().signum() > 0)
                events.add(new FactorEvent(event.date(), BigDecimal.ONE.add(stock).add(cash),
                        BigDecimal.ONE.add(stock), false));
        }
        if (events.isEmpty()) {
            return new Adjustment(List.copyOf(rowsDesc), false);
        }
        // 同日排序只為輸出穩定性：配息因子已在上方以原始序列預先算好，且套用階段只是 BigDecimal
        // 乘法（可交換、無中間捨入），故同日兩事件互換順序結果完全相同。
        events.sort(Comparator.comparing(FactorEvent::date)
                .thenComparing(e -> e.split() ? 0 : 1));

        List<BigDecimal> priceGrowthByRow = new ArrayList<>(rowsAsc.size());
        List<BigDecimal> shareGrowthByRow = new ArrayList<>(rowsAsc.size());
        BigDecimal cumulativePriceGrowth = BigDecimal.ONE;
        BigDecimal cumulativeShareGrowth = BigDecimal.ONE;
        int eventIndex = 0;
        boolean applied = false;
        List<LocalDate> appliedEventDates = new ArrayList<>();

        for (StockPriceHistory row : rowsAsc) {
            while (eventIndex < events.size()
                    && !events.get(eventIndex).date().isAfter(row.getTradingDate())) {
                FactorEvent event = events.get(eventIndex);
                BigDecimal priceGrowth = event.priceGrowth();
                BigDecimal shareGrowth = event.shareGrowth();
                // 反向分割的 growth < 1，故判準是「不等於 1」而非「大於 1」（Task 265）。
                if (priceGrowth != null && priceGrowth.signum() > 0) {
                    cumulativePriceGrowth = cumulativePriceGrowth.multiply(priceGrowth);
                }
                if (shareGrowth != null && shareGrowth.signum() > 0) {
                    cumulativeShareGrowth = cumulativeShareGrowth.multiply(shareGrowth);
                }
                if ((priceGrowth != null && priceGrowth.compareTo(BigDecimal.ONE) != 0)
                        || (shareGrowth != null && shareGrowth.compareTo(BigDecimal.ONE) != 0)) {
                    applied = true;
                    // events 已依日期升冪排序，故此清單天然為升冪（Task 356.4e）。
                    appliedEventDates.add(event.date());
                }
                eventIndex++;
            }
            priceGrowthByRow.add(cumulativePriceGrowth);
            shareGrowthByRow.add(cumulativeShareGrowth);
        }

        if (!applied) {
            return new Adjustment(List.copyOf(rowsDesc), false);
        }

        BigDecimal finalPriceGrowth = cumulativePriceGrowth;
        BigDecimal finalShareGrowth = cumulativeShareGrowth;
        List<StockPriceHistory> adjustedAsc = new ArrayList<>(rowsAsc.size());
        for (int i = 0; i < rowsAsc.size(); i++) {
            BigDecimal priceScale = priceGrowthByRow.get(i)
                    .divide(finalPriceGrowth, SCALE, RoundingMode.HALF_UP);
            // historicalShareScale = cumulativeAtRow / final。1:4 分割前為 0.25，故 volume / 0.25 = ×4。
            BigDecimal historicalShareScale = shareGrowthByRow.get(i)
                    .divide(finalShareGrowth, SCALE, RoundingMode.HALF_UP);
            adjustedAsc.add(adjust(rowsAsc.get(i), priceScale, historicalShareScale));
        }
        adjustedAsc.sort(Comparator.comparing(StockPriceHistory::getTradingDate).reversed());
        return new Adjustment(List.copyOf(adjustedAsc), true, List.copyOf(appliedEventDates));
    }

    /**
     * 美股 price-only 價基（Task 565）：不還原、直接以庫內<b>未做配息還原</b>的完成收盤列計算技術指標。
     *
     * <p>用於 {@link #adjust} 因「沒有美股歷史價的拆股／價基證明」而拋
     * {@link CanonicalDividendFinancialGuard.Unavailable} 的非債券美股。本方法<b>不改輸入、不縮放任何價格</b>，
     * 回傳的是原列副本、{@code adjusted=false}；它只負責把「price-only 可以成立」的條件寫死，
     * 任一條件不成立一律拋 {@link CanonicalDividendFinancialGuard.Unavailable}（fail-closed，
     * 由呼叫端退回 Task 564 的技術指標不可用路徑）：</p>
     * <ol>
     *   <li><b>價基斷層</b>：依日期升序，相鄰兩列都有正收盤時「前收 ÷ 後收」≥ {@link #SPLIT_FORWARD_MIN}
     *       或 ≤ {@link #SPLIT_REVERSE_MAX} 即拋 {@code US_PRICE_ONLY_BASIS_BREAK:<後一列交易日>}。
     *       不論是否接近標準分割比例、不套股票股利豁免、不放寬也不收緊 {@link #detectSplits} 的門檻——
     *       price-only 沒有能力還原分割，疑似分割一律放棄。</li>
     *   <li><b>除息後退避</b>：事件有效日（{@code canonicalDate}，否則 {@code anchorDate()}）落在序列最近
     *       {@value #US_PRICE_ONLY_EX_DIVIDEND_COOLDOWN_BARS} 根（含除息日當根）內即拋
     *       {@code US_PRICE_ONLY_RECENT_EX_DIVIDEND:<有效日>}；多個事件取最晚的有效日。
     *       金額 null 的 UNRESOLVED 事件同樣觸發（無法證明沒有除息）；現金與股票股利皆為 0 的事件忽略；
     *       有效日晚於序列最新列的未來事件不退避；沒有任何日期者無法證明，拋
     *       {@code US_PRICE_ONLY_EX_DIVIDEND_DATE_UNVERIFIED}。</li>
     * </ol>
     *
     * @param events 該序列日期區間內的配息事件；{@code null} 與 {@code CanonicalDividendFinancialGuard.assess(null)}
     *               一致地拋 {@code DIVIDEND_FACTS_MISSING}，空清單視為無事件。
     */
    public Adjustment priceOnlyBasis(
            List<StockPriceHistory> rowsDesc,
            List<StockDividendHistory> events) {
        if (rowsDesc == null || rowsDesc.isEmpty()) {
            return new Adjustment(List.of(), false);
        }

        List<StockPriceHistory> rowsAsc = new ArrayList<>(rowsDesc);
        rowsAsc.sort(Comparator.comparing(StockPriceHistory::getTradingDate));

        for (int i = 1; i < rowsAsc.size(); i++) {
            double ratio = adjacentCloseRatio(rowsAsc.get(i - 1), rowsAsc.get(i));
            if (!Double.isNaN(ratio) && exceedsSplitBand(ratio)) {
                throw new CanonicalDividendFinancialGuard.Unavailable(
                        "US_PRICE_ONLY_BASIS_BREAK:" + rowsAsc.get(i).getTradingDate());
            }
        }

        if (events == null) {
            throw new CanonicalDividendFinancialGuard.Unavailable("DIVIDEND_FACTS_MISSING");
        }
        LocalDate newestRowDate = rowsAsc.get(rowsAsc.size() - 1).getTradingDate();
        LocalDate latestEffective = null;
        for (StockDividendHistory event : events) {
            if (event == null) {
                throw new CanonicalDividendFinancialGuard.Unavailable("DIVIDEND_FACT_MISSING");
            }
            boolean provablyNoDistribution = event.getCashDividend() != null && event.getStockDividend() != null
                    && event.getCashDividend().signum() == 0 && event.getStockDividend().signum() == 0;
            if (provablyNoDistribution) continue;
            // 有效日與 findAdjustmentEvents 的 COALESCE(canonical_date, LEAST(ex, rights)) 同義；
            // anchorDate() 是 StockDividendHistory 既有的唯一 min 實作，不在此另寫一份。
            LocalDate effective = event.getCanonicalDate() != null
                    ? event.getCanonicalDate() : event.anchorDate();
            if (effective == null) {
                throw new CanonicalDividendFinancialGuard.Unavailable(
                        "US_PRICE_ONLY_EX_DIVIDEND_DATE_UNVERIFIED");
            }
            if (effective.isAfter(newestRowDate)) continue;
            if (latestEffective == null || effective.isAfter(latestEffective)) latestEffective = effective;
        }
        if (latestEffective != null) {
            int barsSinceExDividend = 0;
            for (int i = rowsAsc.size() - 1; i >= 0
                    && !rowsAsc.get(i).getTradingDate().isBefore(latestEffective); i--) {
                barsSinceExDividend++;
            }
            if (barsSinceExDividend >= 1 && barsSinceExDividend <= US_PRICE_ONLY_EX_DIVIDEND_COOLDOWN_BARS) {
                throw new CanonicalDividendFinancialGuard.Unavailable(
                        "US_PRICE_ONLY_RECENT_EX_DIVIDEND:" + latestEffective);
            }
        }
        return new Adjustment(List.copyOf(rowsDesc), false);
    }

    /**
     * 相鄰兩列「前收 ÷ 後收」；任一側收盤為 null 或非正時回 {@link Double#NaN}（呼叫端一律跳過該對）。
     *
     * <p>{@link #detectSplits} 與 {@link #priceOnlyBasis} 共用這一份算法，避免兩處對「疑似分割跳空」的
     * 定義各自漂移。非正收盤一律跳過（除以零與誤判的縱深防禦）。Task 279 前實測台股有 175 筆
     * 這種列（來源對「當日無整股成交」不發布 OHLC、FinMind 序列化為 0.0），
     * 已全數刪除、且 stock_price_history.close_price 已有 CHECK (close_price > 0)，
     * 故現在應恆為 0 筆；此判斷保留，不依賴上游守門。</p>
     */
    private static double adjacentCloseRatio(StockPriceHistory previous, StockPriceHistory next) {
        BigDecimal prevClose = previous.getClosePrice();
        BigDecimal close = next.getClosePrice();
        if (prevClose == null || close == null || prevClose.signum() <= 0 || close.signum() <= 0) {
            return Double.NaN;
        }
        return prevClose.doubleValue() / close.doubleValue();
    }

    /** 比值落在 [{@link #SPLIT_FORWARD_MIN}, ∞) 或 (0, {@link #SPLIT_REVERSE_MAX}] 才是疑似分割跳空。 */
    private static boolean exceedsSplitBand(double ratio) {
        return !(ratio < SPLIT_FORWARD_MIN && ratio > SPLIT_REVERSE_MAX);
    }

    /**
     * 以相鄰收盤的比例偵測股票分割（Task 265）。
     *
     * <p>回傳的 {@code priceGrowth} 與 {@code shareGrowth} 都是事件後／事件前比例：分割日之前的價格
     * 乘 {@code 1/ratio}，成交量除以 {@code 1/ratio}；反向分割時兩者方向相反地調整。</p>
     *
     * <p>採<b>標準比例</b>（{@code {2,3,4,5,10}} 之一）而非觀察到的實際比例：實際比例
     * （0050 為 3.966）含當日真實漲跌，用它還原會把真實價格變動也一併抹掉。</p>
     */
    private List<FactorEvent> detectSplits(
            List<StockPriceHistory> rowsAsc, List<CanonicalDividendFinancialGuard.EffectiveEvent> rawEvents) {
        List<FactorEvent> out = new ArrayList<>();
        for (int i = 1; i < rowsAsc.size(); i++) {
            // 非正收盤一律跳過的縱深防禦與比值算法抽到 adjacentCloseRatio（Task 565：與 priceOnlyBasis 共用）。
            double ratio = adjacentCloseRatio(rowsAsc.get(i - 1), rowsAsc.get(i));
            if (Double.isNaN(ratio)) continue;
            if (!exceedsSplitBand(ratio)) continue;

            // 大額股票股利（如配股 10 元＝1:1）同樣使價格腰斬，ratio ≈ 2.0 會被誤認為 2:1 分割。
            // 若不排除，該事件會被 dividendFactor 與 detectSplits 各計一次、price/share growth 被乘成 4 倍，
            // 分割前價格被縮成 1/4 而非 1/2——方向錯、幅度錯、且不拋任何例外。
            if (hasStockDividendOn(rawEvents, rowsAsc.get(i).getTradingDate())) {
                log.info("跳空已由股票股利解釋，不認定為分割：{}/{} {} ratio={}",
                        rowsAsc.get(i).getStockCode(), rowsAsc.get(i).getMarket(),
                        rowsAsc.get(i).getTradingDate(), String.format("%.4f", ratio));
                continue;
            }

            BigDecimal canonical = canonicalSplitRatio(ratio);
            if (canonical == null) {
                log.warn("疑似分割但比例不接近標準值，維持原值不還原：{}/{} {} ratio={}",
                        rowsAsc.get(i).getStockCode(), rowsAsc.get(i).getMarket(),
                        rowsAsc.get(i).getTradingDate(), String.format("%.4f", ratio));
                continue;
            }
            log.info("偵測到股票分割並還原：{}/{} {} ratio={}（採標準比例 {}）",
                    rowsAsc.get(i).getStockCode(), rowsAsc.get(i).getMarket(),
                    rowsAsc.get(i).getTradingDate(), String.format("%.4f", ratio), canonical.toPlainString());
            out.add(new FactorEvent(rowsAsc.get(i).getTradingDate(), canonical, canonical, true));
        }
        return out;
    }

    /** 該日（或前一交易日）是否已有股票股利事件可解釋這個跳空。 */
    private boolean hasStockDividendOn(List<CanonicalDividendFinancialGuard.EffectiveEvent> rawEvents, LocalDate date) {
        if (rawEvents == null) return false;
        for (CanonicalDividendFinancialGuard.EffectiveEvent e : rawEvents) {
            if (e == null) continue;
            if (!positive(e.stock())) continue;
            // Task 357／357.4a-2：改判除權日（股票股利真正的基準日），只配股事件的
            // exDividendDate 會是 null；沒有除權日時退回 anchorDate 保底。
            LocalDate exRights = e.date();
            if (exRights == null) continue;
            // 容一個交易日的誤差：除權日與價格跳空日在來源資料中偶有一日之差。
            if (!exRights.isBefore(date.minusDays(1)) && !exRights.isAfter(date.plusDays(1))) {
                return true;
            }
        }
        return false;
    }

    /** 比例須接近 {2,3,4,5,10} 或其倒數之一、相對誤差 ≤ 容差；否則回 null（不還原）。 */
    private BigDecimal canonicalSplitRatio(double ratio) {
        for (int r : SPLIT_CANONICAL_RATIOS) {
            if (Math.abs(ratio - r) / r <= SPLIT_RATIO_TOLERANCE) {
                return BigDecimal.valueOf(r);
            }
            double inverse = 1.0 / r;
            if (Math.abs(ratio - inverse) / inverse <= SPLIT_RATIO_TOLERANCE) {
                return BigDecimal.ONE.divide(BigDecimal.valueOf(r), SCALE, RoundingMode.HALF_UP);
            }
        }
        return null;
    }

    /** 取指定日期（含）之前最近一筆的收盤，供現金股利因子作分母。 */
    private BigDecimal closeOn(List<StockPriceHistory> rowsAsc, LocalDate date) {
        BigDecimal found = null;
        for (StockPriceHistory row : rowsAsc) {
            if (row.getTradingDate().isAfter(date)) break;
            found = row.getClosePrice();
        }
        return found;
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private StockPriceHistory adjust(
            StockPriceHistory source,
            BigDecimal priceScale,
            BigDecimal historicalShareScale) {
        return StockPriceHistory.builder()
                .id(source.getId())
                .stockCode(source.getStockCode())
                .market(source.getMarket())
                .tradingDate(source.getTradingDate())
                .openPrice(scale(source.getOpenPrice(), priceScale))
                .highPrice(scale(source.getHighPrice(), priceScale))
                .lowPrice(scale(source.getLowPrice(), priceScale))
                .closePrice(scale(source.getClosePrice(), priceScale))
                .volume(scaleVolume(source.getVolume(), historicalShareScale))
                .build();
    }

    private Long scaleVolume(Long volume, BigDecimal historicalShareScale) {
        if (volume == null || historicalShareScale == null || historicalShareScale.signum() <= 0) return volume;
        return BigDecimal.valueOf(volume)
                .divide(historicalShareScale, 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    private BigDecimal scale(BigDecimal value, BigDecimal factor) {
        return value == null ? null : value.multiply(factor).setScale(8, RoundingMode.HALF_UP);
    }
}
