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
 * 共用技術指標計算：月線 MA20、季線 MA60、年線 MA240、KD9。
 * 取最近 240 筆歷史，若當日已有報價但未寫入 history，會把今日股價合併入計算。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TechnicalIndicatorService {

    private final StockPriceHistoryRepository historyRepo;
    private final StockPriceRepository priceRepo;

    /** 季線 + KD（保留舊簽名供 WatchStock 等列表頁使用，避免不必要的 MA240 計算成本） */
    public record Indicators(BigDecimal quarterlyMa, BigDecimal k, BigDecimal d) {
        public static final Indicators EMPTY = new Indicators(null, null, null);
    }

    /** 完整 5 指標：警示觸發紀錄使用 */
    public record FullIndicators(
            BigDecimal monthlyMa,
            BigDecimal quarterlyMa,
            BigDecimal annualMa,
            BigDecimal k,
            BigDecimal d) {
        public static final FullIndicators EMPTY = new FullIndicators(null, null, null, null, null);
    }

    @Transactional(readOnly = true)
    public Indicators compute(String stockCode, String market) {
        FullIndicators f = computeAll(stockCode, market);
        return new Indicators(f.quarterlyMa, f.k, f.d);
    }

    /**
     * 一次計算 MA20 / MA60 / MA240 / K / D。
     * 歷史資料不足以撐滿某個視窗時，該欄位回傳 null（其他仍照算）。
     */
    @Transactional(readOnly = true)
    public FullIndicators computeAll(String stockCode, String market) {
        try {
            List<StockPriceHistory> desc = historyRepo.findRecentN(stockCode, market, 240);
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

            if (series.isEmpty()) return FullIndicators.EMPTY;

            BigDecimal ma20  = simpleMa(series, 20);
            BigDecimal ma60  = simpleMa(series, 60);
            BigDecimal ma240 = simpleMa(series, 240);

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
            return new FullIndicators(ma20, ma60, ma240, kVal, dVal);
        } catch (Exception e) {
            log.warn("compute indicators failed for {} {}", stockCode, market, e);
            return FullIndicators.EMPTY;
        }
    }

    /** 取最近 days 筆收盤價平均；series 為 desc。資料不足時回傳 null。 */
    private static BigDecimal simpleMa(List<StockPriceHistory> series, int days) {
        if (series.size() < days) return null;
        double sum = 0;
        for (int i = 0; i < days; i++) sum += series.get(i).getClosePrice().doubleValue();
        return BigDecimal.valueOf(sum / days).setScale(2, RoundingMode.HALF_UP);
    }
}
