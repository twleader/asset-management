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
     * 走勢圖用的逐日指標點（Task 261）。視窗／暖機不足的欄位為 null（不是 0）。
     * j9 = 3D − 2K、k3d2 = 3K − 2D（同一對 K/D 的兩種鏡像慣例，畫面兩者都要顯示）。
     */
    public record IndicatorPoint(
            LocalDate tradingDate,
            BigDecimal ma20,
            BigDecimal ma60,
            BigDecimal ma240,
            BigDecimal k,
            BigDecimal d,
            BigDecimal j9,
            BigDecimal k3d2,
            BigDecimal rsv) {}

    /** 序列核心逐期輸出；暖機不足 9 筆者為 EMPTY。 */
    private record KdPoint(BigDecimal k, BigDecimal d, BigDecimal j9, BigDecimal k3d2, BigDecimal rsv) {
        private static final KdPoint EMPTY = new KdPoint(null, null, null, null, null);
    }

    /** 取「全部歷史」時的下界；stock_price_history 保留期本就只有 10 年，此值僅為不設限的哨兵。 */
    private static final LocalDate EPOCH_START = LocalDate.of(1970, 1, 1);

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

    /**
     * 走勢圖用的整段指標序列（Task 261）：[start, end] 逐日的 MA20/60/240 + K/D/J9/K3D2/RSV。
     *
     * 與單點 {@link #computeAll} 共用同一份 MA／KD 核心與同一套今日 live 併入規則，因此
     * <b>當 end &gt;= 該市場今日時，尾筆的 k/d/ma* 逐位等於 computeAll()、倒數第二筆的 k/d 等於
     * previousK/previousD</b>——這是「走勢圖與觀察清單表格不再出現兩組數字」的機械判準。
     *
     * 取數刻意涵蓋 end 之前的<b>全部</b>歷史（而非 computeAll 的 240 筆）以供 MA240／KD 暖機；
     * 但 stock_price_history 保留期本就只有 10 年、前端 start 也是 10 年前，故線圖前段的 MA240
     * 仍會是 null（與前端舊 calcMA 行為相同，非回歸）。
     */
    @Transactional(readOnly = true)
    public List<IndicatorPoint> indicatorSeries(String stockCode, String market, LocalDate start, LocalDate end) {
        try {
            List<StockPriceHistory> asc = isTaiex(stockCode, market)
                    ? taiexSeriesAsc(stockCode, market, end)
                    : stockSeriesAsc(stockCode, market, end);
            if (asc.isEmpty()) return List.of();

            List<KdPoint> kd = kdSeriesAsc(asc);
            List<IndicatorPoint> out = new ArrayList<>();
            for (int i = 0; i < asc.size(); i++) {
                LocalDate date = asc.get(i).getTradingDate();
                if (date.isBefore(start)) continue;   // 暖機段只參與計算、不回傳
                KdPoint p = kd.get(i);
                out.add(new IndicatorPoint(date,
                        maAt(asc, i, 20), maAt(asc, i, 60), maAt(asc, i, 240),
                        p.k(), p.d(), p.j9(), p.k3d2(), p.rsv()));
            }
            return out;
        } catch (Exception e) {
            log.warn("compute indicator series failed for {} {}", stockCode, market, e);
            return List.of();
        }
    }

    /** 一般個股：全史 asc ＋（end 已到今日時）比照 computeAll() 併入今日 live。 */
    private List<StockPriceHistory> stockSeriesAsc(String stockCode, String market, LocalDate end) {
        List<StockPriceHistory> asc = new ArrayList<>(
                historyRepo.findByStockCodeAndMarketAndTradingDateBetweenOrderByTradingDateAsc(
                        stockCode, market, EPOCH_START, end));
        LocalDate today = MarketZones.today(market);
        if (end.isBefore(today)) return asc;
        if (!asc.isEmpty() && today.equals(asc.get(asc.size() - 1).getTradingDate())) return asc;

        Optional<PriceQueryService.LivePrice> spOpt = priceQuery.getLive(stockCode, market);
        if (spOpt.isPresent() && spOpt.get().tradingDate() != null
                && today.toString().equals(spOpt.get().tradingDate())) {
            PriceQueryService.LivePrice sp = spOpt.get();
            asc.add(StockPriceHistory.builder()
                    .stockCode(stockCode).market(market).tradingDate(today)
                    .closePrice(sp.price())
                    .highPrice(sp.highPrice() != null ? sp.highPrice() : sp.price())
                    .lowPrice(sp.lowPrice()  != null ? sp.lowPrice()  : sp.price())
                    .build());
        }
        return asc;
    }

    /**
     * 0000 台股大盤：指數日線映射成 StockPriceHistory 後餵同一份序列核心，
     * 避免為了型別差異再長出第四套 KD／MA 遞迴（taiexKd/taiexSimpleMa 服務 computeAll 的既有路徑，不動）。
     */
    private List<StockPriceHistory> taiexSeriesAsc(String stockCode, String market, LocalDate end) {
        List<StockPriceHistory> asc = twseDailyRepo
                .findByTradingDateBetweenOrderByTradingDateAsc(EPOCH_START, end)
                .stream()
                .map(d -> StockPriceHistory.builder()
                        .stockCode(stockCode).market(market).tradingDate(d.getTradingDate())
                        .closePrice(d.getClosePoint())
                        .highPrice(d.getHighPoint())
                        .lowPrice(d.getLowPoint())
                        .build())
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        LocalDate today = LocalDate.now(MarketZones.TW_ZONE);
        if (end.isBefore(today)) return asc;
        if (!asc.isEmpty() && today.equals(asc.get(asc.size() - 1).getTradingDate())) return asc;

        Optional<PriceQueryService.LivePrice> liveOpt = priceQuery.getLive("0000", "台股");
        if (liveOpt.isPresent() && liveOpt.get().tradingDate() != null
                && today.toString().equals(liveOpt.get().tradingDate())) {
            PriceQueryService.LivePrice live = liveOpt.get();
            asc.add(StockPriceHistory.builder()
                    .stockCode(stockCode).market(market).tradingDate(today)
                    .closePrice(live.price())
                    .highPrice(live.highPrice() != null ? live.highPrice() : live.price())
                    .lowPrice(live.lowPrice()  != null ? live.lowPrice()  : live.price())
                    .build());
        }
        return asc;
    }

    /**
     * asc 序列中第 i 期的 n 日均線（往前 n 筆收盤均價）；不足 n 筆回 null。
     *
     * <b>累加方向必須是「新→舊」，與 {@link #simpleMa}／{@link #taiexSimpleMa} 一致。</b>
     * double 加法不可結合：同一組收盤價換個順序相加，末位差經
     * {@code BigDecimal.valueOf(sum / days).setScale(2, HALF_UP)} 會在 x.xx5 邊界翻面
     * ——實測反向累加時 MA20 約 1.7% 的日子會與 computeAll() 差 0.01，走勢圖 legend 的
     * 「月線MA20」就會跟觀察清單表格對不上，正是本任務要消滅的那類不一致。
     */
    private static BigDecimal maAt(List<StockPriceHistory> asc, int i, int days) {
        if (i < days - 1) return null;
        double sum = 0;
        for (int j = i; j >= i - days + 1; j--) sum += asc.get(j).getClosePrice().doubleValue();
        return BigDecimal.valueOf(sum / days).setScale(2, RoundingMode.HALF_UP);
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
        List<KdPoint> series = kdSeriesAsc(new ArrayList<>(desc).reversed());
        KdPoint last = series.get(series.size() - 1);
        return new KdValues(last.k(), last.d());
    }

    /**
     * KD9 序列核心（Task 261）：asc 最早在前，回傳與輸入等長、逐期的 K/D/J9/K3D2/RSV，
     * 暖機不足 9 筆者為 {@link KdPoint#EMPTY}。
     * 單點的 {@link #stockKd} 亦走這裡取最後一筆——全站股票 KD 只有這一份遞迴。
     * k/d 續存未捨入值，j9/k3d2 亦以未捨入的 k/d 算完才捨入（與遞迴內部精度一致，避免二次捨入）。
     */
    private static List<KdPoint> kdSeriesAsc(List<StockPriceHistory> asc) {
        List<KdPoint> out = new ArrayList<>(asc.size());
        double k = 50, d = 50;
        int period = 9;
        for (int i = 0; i < asc.size(); i++) {
            if (i < period - 1) { out.add(KdPoint.EMPTY); continue; }
            List<StockPriceHistory> window = asc.subList(i - period + 1, i + 1);
            double highest = window.stream().mapToDouble(h -> h.getHighPrice() != null
                    ? h.getHighPrice().doubleValue() : h.getClosePrice().doubleValue()).max().orElse(0);
            double lowest = window.stream().mapToDouble(h -> h.getLowPrice() != null
                    ? h.getLowPrice().doubleValue() : h.getClosePrice().doubleValue()).min().orElse(0);
            double rsv = (highest == lowest) ? 50
                    : (asc.get(i).getClosePrice().doubleValue() - lowest) / (highest - lowest) * 100;
            k = k * 2.0 / 3 + rsv / 3.0;
            d = d * 2.0 / 3 + k / 3.0;
            out.add(new KdPoint(scale2(k), scale2(d), scale2(3 * d - 2 * k), scale2(3 * k - 2 * d), scale2(rsv)));
        }
        return out;
    }

    private static BigDecimal scale2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
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
