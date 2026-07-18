package com.steven.assets.service;

import com.steven.assets.model.StockDividendHistory;
import com.steven.assets.model.StockPriceHistory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 把原始 OHLC 轉成以最新價格為基準的還原權息序列。
 *
 * <p>只做純計算、不寫回 entity 或資料庫；沒有可用事件時原值返回，避免無謂精度漂移。</p>
 */
@Component
public class DistributionAdjustedPriceService {

    private static final BigDecimal STOCK_PAR_VALUE = BigDecimal.TEN;
    private static final int SCALE = 12;

    public record Adjustment(List<StockPriceHistory> rowsDesc, boolean adjusted) {}

    public Adjustment adjust(
            List<StockPriceHistory> rowsDesc,
            List<StockDividendHistory> rawEvents) {
        if (rowsDesc == null || rowsDesc.isEmpty()) {
            return new Adjustment(List.of(), false);
        }
        if (rawEvents == null || rawEvents.isEmpty()) {
            return new Adjustment(List.copyOf(rowsDesc), false);
        }

        List<StockPriceHistory> rowsAsc = new ArrayList<>(rowsDesc);
        rowsAsc.sort(Comparator.comparing(StockPriceHistory::getTradingDate));
        var firstDate = rowsAsc.get(0).getTradingDate();
        var lastDate = rowsAsc.get(rowsAsc.size() - 1).getTradingDate();

        List<StockDividendHistory> events = rawEvents.stream()
                .filter(this::validEvent)
                .filter(event -> !event.getExDividendDate().isBefore(firstDate)
                        && !event.getExDividendDate().isAfter(lastDate))
                .sorted(Comparator.comparing(StockDividendHistory::getExDividendDate)
                        .thenComparing(e -> e.getId() == null ? Long.MAX_VALUE : e.getId()))
                .toList();
        if (events.isEmpty()) {
            return new Adjustment(List.copyOf(rowsDesc), false);
        }

        List<BigDecimal> sharesByRow = new ArrayList<>(rowsAsc.size());
        BigDecimal shares = BigDecimal.ONE;
        int eventIndex = 0;
        boolean applied = false;

        for (StockPriceHistory row : rowsAsc) {
            while (eventIndex < events.size()
                    && !events.get(eventIndex).getExDividendDate().isAfter(row.getTradingDate())) {
                BigDecimal factor = eventFactor(events.get(eventIndex), row.getClosePrice());
                if (factor.compareTo(BigDecimal.ONE) > 0) {
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

    private boolean validEvent(StockDividendHistory event) {
        return event != null
                && event.getExDividendDate() != null
                && (positive(event.getCashDividend()) || positive(event.getStockDividend()));
    }

    private BigDecimal eventFactor(StockDividendHistory event, BigDecimal eventDayClose) {
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
