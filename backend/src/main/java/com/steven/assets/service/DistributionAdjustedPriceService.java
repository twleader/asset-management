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
 * 把原始 OHLC 轉成以最新價格為基準的還原權息／分割序列。
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

    /** 內部統一事件：除權息與分割合流後依日期升冪套用同一個累積因子。 */
    private record FactorEvent(LocalDate date, BigDecimal factor, boolean split) {}

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
                            e.getExDividendDate(), dividendFactor(e, closeOn(rowsAsc, e.getExDividendDate())), false)));
        }
        if (events.isEmpty()) {
            return new Adjustment(List.copyOf(rowsDesc), false);
        }
        // 同日排序只為輸出穩定性：配息因子已在上方以原始序列預先算好，且套用階段只是 BigDecimal
        // 乘法（可交換、無中間捨入），故同日兩事件互換順序結果完全相同。
        events.sort(Comparator.comparing(FactorEvent::date)
                .thenComparing(e -> e.split() ? 0 : 1));

        List<BigDecimal> sharesByRow = new ArrayList<>(rowsAsc.size());
        BigDecimal shares = BigDecimal.ONE;
        int eventIndex = 0;
        boolean applied = false;

        for (StockPriceHistory row : rowsAsc) {
            while (eventIndex < events.size()
                    && !events.get(eventIndex).date().isAfter(row.getTradingDate())) {
                BigDecimal factor = events.get(eventIndex).factor();
                // 反向分割的因子 < 1，故判準是「不等於 1」而非「大於 1」——舊寫法使 shares 單調遞增、
                // scale <= 1 恆成立，反向分割在結構上無法表達（Task 265）。
                if (factor != null && factor.signum() > 0 && factor.compareTo(BigDecimal.ONE) != 0) {
                    shares = shares.multiply(factor);
                    applied = true;
                }
                eventIndex++;
            }
            sharesByRow.add(shares);
        }

        if (!applied) {
            return new Adjustment(List.copyOf(rowsDesc), false);
        }

        BigDecimal finalShares = shares;
        List<StockPriceHistory> adjustedAsc = new ArrayList<>(rowsAsc.size());
        for (int i = 0; i < rowsAsc.size(); i++) {
            BigDecimal scale = sharesByRow.get(i).divide(finalShares, SCALE, RoundingMode.HALF_UP);
            adjustedAsc.add(adjust(rowsAsc.get(i), scale));
        }
        adjustedAsc.sort(Comparator.comparing(StockPriceHistory::getTradingDate).reversed());
        return new Adjustment(List.copyOf(adjustedAsc), true);
    }

    /**
     * 以相鄰收盤的比例偵測股票分割（Task 265）。
     *
     * <p>回傳的因子語意與股票股利相同：{@code shares} 乘上該因子，使分割日<b>之前</b>的價格
     * 被縮放為 {@code 1/ratio}，對齊分割後的價基。反向分割的因子 &lt; 1，earlier 價格因而放大。</p>
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
            // 非正收盤一律跳過：實測台股有 176 筆，否則會除以零或產生誤判。
            if (prevClose == null || close == null || prevClose.signum() <= 0 || close.signum() <= 0) {
                continue;
            }
            double ratio = prevClose.doubleValue() / close.doubleValue();
            if (ratio < SPLIT_FORWARD_MIN && ratio > SPLIT_REVERSE_MAX) continue;

            // 大額股票股利（如配股 10 元＝1:1）同樣使價格腰斬，ratio ≈ 2.0 會被誤認為 2:1 分割。
            // 若不排除，該事件會被 dividendFactor 與 detectSplits 各計一次、shares 被乘成 4 倍，
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
            out.add(new FactorEvent(rowsAsc.get(i).getTradingDate(), canonical, true));
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

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private StockPriceHistory adjust(StockPriceHistory source, BigDecimal scale) {
        return StockPriceHistory.builder()
                .id(source.getId())
                .stockCode(source.getStockCode())
                .market(source.getMarket())
                .tradingDate(source.getTradingDate())
                .openPrice(scale(source.getOpenPrice(), scale))
                .highPrice(scale(source.getHighPrice(), scale))
                .lowPrice(scale(source.getLowPrice(), scale))
                .closePrice(scale(source.getClosePrice(), scale))
                .volume(source.getVolume())
                .build();
    }

    private BigDecimal scale(BigDecimal value, BigDecimal factor) {
        return value == null ? null : value.multiply(factor).setScale(8, RoundingMode.HALF_UP);
    }
}
