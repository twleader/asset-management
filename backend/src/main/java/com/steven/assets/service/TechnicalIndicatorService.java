package com.steven.assets.service;

import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import com.steven.assets.util.MarketZones;
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
    private final PriceQueryService priceQuery;
    private final TwseIndexDailyHistoryRepository twseDailyRepo;

    /** 0000 = 台股大盤特例：價格 / 指標來源走 twse_index_daily_history（含 OHLC）。 */
    private static boolean isTaiex(String code, String market) {
        return "0000".equals(code) && "台股".equals(market);
    }

    /** 完整指標：MA20／60／240、當期 KD 與前一期 KD。 */
    public record FullIndicators(
            BigDecimal monthlyMa,
            BigDecimal quarterlyMa,
            BigDecimal annualMa,
            BigDecimal k,
            BigDecimal d,
            BigDecimal previousK,
            BigDecimal previousD) {
        public static final FullIndicators EMPTY = new FullIndicators(
                null, null, null, null, null, null, null);
    }

    private record KdValues(BigDecimal k, BigDecimal d) {
        private static final KdValues EMPTY = new KdValues(null, null);
    }

    /**
     * 一次計算 MA20 / MA60 / MA240 / K / D。
     * 歷史資料不足以撐滿某個視窗時，該欄位回傳 null（其他仍照算）。
     */
    @Transactional(readOnly = true)
    public FullIndicators computeAll(String stockCode, String market) {
        if (isTaiex(stockCode, market)) {
            return computeAllForTaiex();
        }
        try {
            List<StockPriceHistory> desc = historyRepo.findRecentN(stockCode, market, 240);
            // Task 252：用該股市場時區的今日。JVM 牆鐘在美股台北 00:00–04:00 會取到 D+1，
            // 與 tradingDate（D）比對失敗 → 今日即時價不併入 → MA/KD 用舊序列算。
            LocalDate today = MarketZones.today(market);
            List<StockPriceHistory> series = new ArrayList<>(desc);

            if (series.isEmpty() || !today.equals(series.get(0).getTradingDate())) {
                Optional<PriceQueryService.LivePrice> spOpt = priceQuery.getLive(stockCode, market);
                if (spOpt.isPresent() && spOpt.get().tradingDate() != null
                        && today.toString().equals(spOpt.get().tradingDate())) {
                    PriceQueryService.LivePrice sp = spOpt.get();
                    StockPriceHistory t = StockPriceHistory.builder()
                            .stockCode(stockCode).market(market).tradingDate(today)
                            .closePrice(sp.price())
                            .highPrice(sp.highPrice() != null ? sp.highPrice() : sp.price())
                            .lowPrice(sp.lowPrice()  != null ? sp.lowPrice()  : sp.price())
                            .build();
                    series.add(0, t);
                }
            }

            return computeFromSeries(series);
        } catch (Exception e) {
            log.warn("compute indicators failed for {} {}", stockCode, market, e);
            return FullIndicators.EMPTY;
        }
    }

    /**
     * 對已準備好的降序 OHLC 序列使用同一套 MA／KD 核心。
     * 交易雷達會先還原權息再呼叫，避免在此服務重複資料存取或混用價基。
     */
    FullIndicators computeFromSeries(List<StockPriceHistory> series) {
        if (series == null || series.isEmpty()) return FullIndicators.EMPTY;

        BigDecimal ma20  = simpleMa(series, 20);
        BigDecimal ma60  = simpleMa(series, 60);
        BigDecimal ma240 = simpleMa(series, 240);

        KdValues currentKd = stockKd(series);
        KdValues previousKd = series.size() > 1
                ? stockKd(series.subList(1, series.size()))
                : KdValues.EMPTY;
        return new FullIndicators(
                ma20, ma60, ma240,
                currentKd.k(), currentKd.d(),
                previousKd.k(), previousKd.d());
    }

    /** 取最近 days 筆收盤價平均；series 為 desc。資料不足時回傳 null。 */
    private static BigDecimal simpleMa(List<StockPriceHistory> series, int days) {
        if (series.size() < days) return null;
        double sum = 0;
        for (int i = 0; i < days; i++) sum += series.get(i).getClosePrice().doubleValue();
        return BigDecimal.valueOf(sum / days).setScale(2, RoundingMode.HALF_UP);
    }

    /** 與既有 KD9 完全同式；desc 最新在前，回傳該序列最後一期 K/D。 */
    private static KdValues stockKd(List<StockPriceHistory> desc) {
        if (desc.size() < 9) return KdValues.EMPTY;
        List<StockPriceHistory> asc = new ArrayList<>(desc).reversed();
        double k = 50, d = 50;
        int period = 9;
        for (int i = period - 1; i < asc.size(); i++) {
            List<StockPriceHistory> window = asc.subList(i - period + 1, i + 1);
            double highest = window.stream().mapToDouble(h -> h.getHighPrice() != null
                    ? h.getHighPrice().doubleValue() : h.getClosePrice().doubleValue()).max().orElse(0);
            double lowest = window.stream().mapToDouble(h -> h.getLowPrice() != null
                    ? h.getLowPrice().doubleValue() : h.getClosePrice().doubleValue()).min().orElse(0);
            double rsv = (highest == lowest) ? 50
                    : (asc.get(i).getClosePrice().doubleValue() - lowest) / (highest - lowest) * 100;
            k = k * 2.0 / 3 + rsv / 3.0;
            d = d * 2.0 / 3 + k / 3.0;
        }
        return new KdValues(
                BigDecimal.valueOf(k).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(d).setScale(2, RoundingMode.HALF_UP));
    }

    /**
     * 0000 台股大盤：從 twse_index_daily_history 計算 MA20 / MA60 / MA240 / KD；若完成日 K 尚未到今日
     * 但 Redis 有今日即時點位（Task 228），比照 computeAll() 對一般個股的既有作法暫加一筆到序列最前。
     * OHLC 在 v1.21 之後才補；舊資料 high/low/open 可能為 null，KD 計算時 fallback 用 close。
     */
    private FullIndicators computeAllForTaiex() {
        try {
            List<TwseIndexDailyHistory> desc = new ArrayList<>(twseDailyRepo.findTopNByOrderByTradingDateDesc(240));
            // Task 252：台股大盤，顯式用台北時區（本方法無 market 參數）
            LocalDate today = LocalDate.now(MarketZones.TW_ZONE);
            if (desc.isEmpty() || !today.equals(desc.get(0).getTradingDate())) {
                Optional<PriceQueryService.LivePrice> liveOpt = priceQuery.getLive("0000", "台股");
                if (liveOpt.isPresent() && liveOpt.get().tradingDate() != null
                        && today.toString().equals(liveOpt.get().tradingDate())) {
                    PriceQueryService.LivePrice live = liveOpt.get();
                    TwseIndexDailyHistory t = new TwseIndexDailyHistory();
                    t.setTradingDate(today);
                    t.setClosePoint(live.price());
                    t.setHighPoint(live.highPrice() != null ? live.highPrice() : live.price());
                    t.setLowPoint(live.lowPrice() != null ? live.lowPrice() : live.price());
                    t.setOpenPoint(live.openPrice());
                    desc.add(0, t);
                }
            }
            if (desc.isEmpty()) return FullIndicators.EMPTY;

            BigDecimal ma20  = taiexSimpleMa(desc, 20);
            BigDecimal ma60  = taiexSimpleMa(desc, 60);
            BigDecimal ma240 = taiexSimpleMa(desc, 240);

            KdValues currentKd = taiexKd(desc);
            KdValues previousKd = desc.size() > 1
                    ? taiexKd(desc.subList(1, desc.size()))
                    : KdValues.EMPTY;
            return new FullIndicators(
                    ma20, ma60, ma240,
                    currentKd.k(), currentKd.d(),
                    previousKd.k(), previousKd.d());
        } catch (Exception e) {
            log.warn("compute TAIEX indicators failed", e);
            return FullIndicators.EMPTY;
        }
    }

    private static BigDecimal taiexSimpleMa(List<TwseIndexDailyHistory> desc, int days) {
        if (desc.size() < days) return null;
        double sum = 0;
        for (int i = 0; i < days; i++) sum += desc.get(i).getClosePoint().doubleValue();
        return BigDecimal.valueOf(sum / days).setScale(2, RoundingMode.HALF_UP);
    }

    private static KdValues taiexKd(List<TwseIndexDailyHistory> desc) {
        if (desc.size() < 9) return KdValues.EMPTY;
        List<TwseIndexDailyHistory> asc = new ArrayList<>(desc).reversed();
        double k = 50, d = 50;
        int period = 9;
        for (int i = period - 1; i < asc.size(); i++) {
            List<TwseIndexDailyHistory> window = asc.subList(i - period + 1, i + 1);
            double highest = window.stream().mapToDouble(h -> h.getHighPoint() != null
                    ? h.getHighPoint().doubleValue() : h.getClosePoint().doubleValue()).max().orElse(0);
            double lowest = window.stream().mapToDouble(h -> h.getLowPoint() != null
                    ? h.getLowPoint().doubleValue() : h.getClosePoint().doubleValue()).min().orElse(0);
            double close = asc.get(i).getClosePoint().doubleValue();
            double rsv = (highest == lowest) ? 50 : (close - lowest) / (highest - lowest) * 100;
            k = k * 2.0 / 3 + rsv / 3.0;
            d = d * 2.0 / 3 + k / 3.0;
        }
        return new KdValues(
                BigDecimal.valueOf(k).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(d).setScale(2, RoundingMode.HALF_UP));
    }
}
