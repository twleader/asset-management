package com.steven.assets.service;

import com.steven.assets.model.StockPrice;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.StockPriceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 共用技術指標計算：季線 MA60、KD9。
 * 取最近 60 筆歷史，若當日已有報價但未寫入 history，會把今日股價合併入計算。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TechnicalIndicatorService {

    private final StockPriceHistoryRepository historyRepo;
    private final StockPriceRepository priceRepo;

    public record Indicators(BigDecimal quarterlyMa, BigDecimal k, BigDecimal d) {
        public static final Indicators EMPTY = new Indicators(null, null, null);
    }

    @Transactional(readOnly = true)
    public Indicators compute(String stockCode, String market) {
        try {
            List<StockPriceHistory> desc = historyRepo.findRecentN(stockCode, market, 60);
            LocalDate today = LocalDate.now();
            List<StockPriceHistory> series = new ArrayList<>(desc);

            if (series.isEmpty() || !today.equals(series.get(0).getTradingDate())) {
                Optional<StockPrice> spOpt = priceRepo.findByStockCodeAndMarket(stockCode, market);
                if (spOpt.isPresent() && today.equals(spOpt.get().getTradingDate())) {
                    StockPrice sp = spOpt.get();
                    StockPriceHistory t = StockPriceHistory.builder()
                            .stockCode(stockCode).market(market).tradingDate(today)
                            .closePrice(sp.getPrice())
                            .highPrice(sp.getHighPrice() != null ? sp.getHighPrice() : sp.getPrice())
                            .lowPrice(sp.getLowPrice()  != null ? sp.getLowPrice()  : sp.getPrice())
                            .build();
                    series.add(0, t);
                }
            }

            if (series.isEmpty()) return Indicators.EMPTY;

            int n = Math.min(60, series.size());
            double sum = 0;
            for (int i = 0; i < n; i++) sum += series.get(i).getClosePrice().doubleValue();
            BigDecimal ma60 = BigDecimal.valueOf(sum / n).setScale(2, RoundingMode.HALF_UP);

            BigDecimal kVal = null, dVal = null;
            if (series.size() >= 9) {
                List<StockPriceHistory> asc = new ArrayList<>(series).reversed();
                double k = 50, d = 50;
                int period = 9;
                for (int i = period - 1; i < asc.size(); i++) {
                    List<StockPriceHistory> window = asc.subList(i - period + 1, i + 1);
                    double highest = window.stream().mapToDouble(h -> h.getHighPrice() != null
                            ? h.getHighPrice().doubleValue() : h.getClosePrice().doubleValue()).max().orElse(0);
                    double lowest  = window.stream().mapToDouble(h -> h.getLowPrice() != null
                            ? h.getLowPrice().doubleValue() : h.getClosePrice().doubleValue()).min().orElse(0);
                    double rsv = (highest == lowest) ? 50
                            : (asc.get(i).getClosePrice().doubleValue() - lowest) / (highest - lowest) * 100;
                    k = k * 2.0 / 3 + rsv / 3.0;
                    d = d * 2.0 / 3 + k  / 3.0;
                }
                kVal = BigDecimal.valueOf(k).setScale(2, RoundingMode.HALF_UP);
                dVal = BigDecimal.valueOf(d).setScale(2, RoundingMode.HALF_UP);
            }
            return new Indicators(ma60, kVal, dVal);
        } catch (Exception e) {
            log.warn("compute indicators failed for {} {}", stockCode, market, e);
            return Indicators.EMPTY;
        }
    }
}
