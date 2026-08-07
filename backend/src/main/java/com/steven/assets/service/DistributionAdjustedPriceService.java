package com.steven.assets.service;

import com.steven.assets.model.StockDividendHistory;
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

    public record Adjustment(List<StockPriceHistory> rowsDesc, boolean adjusted) {}

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

        // 分割偵測先於除權息的 early-return：無配息紀錄的標的（如 2327）同樣必須被還原。
        List<FactorEvent> events = new ArrayList<>(detectSplits(rowsAsc, rawEvents));

        if (rawEvents != null) {
            rawEvents.stream()
                    .filter(this::validEvent)
                    .filter(event -> !event.getExDividendDate().isBefore(firstDate)
                            && !event.getExDividendDate().isAfter(lastDate))
                    .sorted(Comparator.comparing(StockDividendHistory::getExDividendDate)
                            .thenComparing(e -> e.getId() == null ? Long.MAX_VALUE : e.getId()))
                    .forEach(e -> events.add(new FactorEvent(
                            e.getExDividendDate(),
                            dividendFactor(e, closeOn(rowsAsc, e.getExDividendDate())),
                            stockShareGrowth(e),
                            false)));
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
        return new Adjustment(List.copyOf(adjustedAsc), true);
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
            List<StockPriceHistory> rowsAsc, List<StockDividendHistory> rawEvents) {
        List<FactorEvent> out = new ArrayList<>();
        for (int i = 1; i < rowsAsc.size(); i++) {
            BigDecimal prevClose = rowsAsc.get(i - 1).getClosePrice();
            BigDecimal close = rowsAsc.get(i).getClosePrice();
            // 非正收盤一律跳過（除以零與誤判的縱深防禦）。Task 279 前實測台股有 175 筆
            // 這種列（來源對「當日無整股成交」不發布 OHLC、FinMind 序列化為 0.0），
            // 已全數刪除、且 stock_price_history.close_price 已有 CHECK (close_price > 0)，
            // 故現在應恆為 0 筆；此判斷保留，不依賴上游守門。
            if (prevClose == null || close == null || prevClose.signum() <= 0 || close.signum() <= 0) {
                continue;
            }
            double ratio = prevClose.doubleValue() / close.doubleValue();
            if (ratio < SPLIT_FORWARD_MIN && ratio > SPLIT_REVERSE_MAX) continue;

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
    private boolean hasStockDividendOn(List<StockDividendHistory> rawEvents, LocalDate date) {
        if (rawEvents == null) return false;
        for (StockDividendHistory e : rawEvents) {
            if (e == null || e.getExDividendDate() == null) continue;
            if (!positive(e.getStockDividend())) continue;
            // 容一個交易日的誤差：除權日與價格跳空日在來源資料中偶有一日之差。
            if (!e.getExDividendDate().isBefore(date.minusDays(1))
                    && !e.getExDividendDate().isAfter(date.plusDays(1))) {
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

    private boolean validEvent(StockDividendHistory event) {
        return event != null
                && event.getExDividendDate() != null
                && (positive(event.getCashDividend()) || positive(event.getStockDividend()));
    }

    private BigDecimal dividendFactor(StockDividendHistory event, BigDecimal eventDayClose) {
        BigDecimal stockFactor = positive(event.getStockDividend())
                ? event.getStockDividend().divide(STOCK_PAR_VALUE, SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal cashFactor = positive(event.getCashDividend())
                && eventDayClose != null && eventDayClose.signum() > 0
                ? event.getCashDividend().divide(eventDayClose, SCALE, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        return BigDecimal.ONE.add(stockFactor).add(cashFactor);
    }

    /** 股票股利造成的股數成長；現金股利不改變股數。 */
    private BigDecimal stockShareGrowth(StockDividendHistory event) {
        return positive(event.getStockDividend())
                ? BigDecimal.ONE.add(event.getStockDividend()
                        .divide(STOCK_PAR_VALUE, SCALE, RoundingMode.HALF_UP))
                : BigDecimal.ONE;
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
