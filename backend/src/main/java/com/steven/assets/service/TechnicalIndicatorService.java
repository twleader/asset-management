package com.steven.assets.service;

import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.model.UsIndexDailyHistory;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import com.steven.assets.repository.UsIndexDailyHistoryRepository;
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
 * 共用技術指標計算：MA5／20／60／240、KD/J、MACD、RSI、乖離率與威廉指標。
 * 取最近 240 筆歷史，若當日已有報價但未寫入 history，會把今日股價合併入計算。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TechnicalIndicatorService {

    private final StockPriceHistoryRepository historyRepo;
    private final PriceQueryService priceQuery;
    private final TwseIndexDailyHistoryRepository twseDailyRepo;
    private final UsIndexDailyHistoryRepository usIndexDailyHistoryRepo;

    /** 0000 = 台股大盤特例：價格 / 指標來源走 twse_index_daily_history（含 OHLC）。 */
    private static boolean isTaiex(String code, String market) {
        return "0000".equals(code) && "台股".equals(market);
    }

    /**
     * 完整指標：MA5／20／60／240、當期 KD 與前一期 KD。
     *
     * @param weeklyMa 週線（MA5，台股慣例的 5 個交易日；Task 265）。
     *                 Task 291 起會進入交易雷達的短期與中期獨立權重，短期權重較高。
     *                 <b>價基依呼叫路徑而不同</b>：{@link #computeFromSeries} 由呼叫端餵入（交易雷達餵還原序列），
     *                 {@link #computeAll} 自行讀原始序列故為未還原值——此為 MA20/60/240 既有的同一限制。
     */
    public record FullIndicators(
            BigDecimal monthlyMa,
            BigDecimal quarterlyMa,
            BigDecimal annualMa,
            BigDecimal k,
            BigDecimal d,
            BigDecimal previousK,
            BigDecimal previousD,
            BigDecimal weeklyMa,
            /** 走勢圖指標選單同一組值；Task 291 起由 RadarInputAssembler 接入雙軌評分。 */
            ExtendedIndicators extended) {
        public static final FullIndicators EMPTY = new FullIndicators(
                null, null, null, null, null, null, null, null, ExtendedIndicators.EMPTY);
    }

    /**
     * 走勢圖指標選單（Task 262）同一組值的單點版（Task 281）。
     *
     * <p>暖機／視窗不足的欄位為 {@code null}（不是 0）；全部 {@code setScale(2, HALF_UP)}。
     * 價基由 {@link #computeFromSeries} 的呼叫端決定（交易雷達餵還原權息序列），
     * 與同一份 {@link FullIndicators} 的 {@code k}／{@code d} 出自同一個 {@code series} 參數。</p>
     *
     * <p>Task 291 起，個股的 J／MACD／RSI／BIAS／W%R 進入短期與中期評分；
     * EMA12／EMA26 只透過 DIF、DIF／MACD 只透過 OSC 同源納入，避免代數相依值重複灌權重。
     * 大盤仍以既有 MA／KD 加上市場量能與美股科技日報酬形成 regime。</p>
     */
    public record ExtendedIndicators(
            BigDecimal j9,
            BigDecimal k3d2,
            BigDecimal rsv,
            BigDecimal ema12,
            BigDecimal ema26,
            BigDecimal dif,
            BigDecimal macd,
            BigDecimal osc,
            BigDecimal rsi5,
            BigDecimal rsi10,
            BigDecimal bias10,
            BigDecimal bias20,
            BigDecimal b10b20,
            BigDecimal wr9) {
        public static final ExtendedIndicators EMPTY = new ExtendedIndicators(
                null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    /**
     * 走勢圖用的逐日指標點（Task 261）。視窗／暖機不足的欄位為 null（不是 0）。
     * j9 = 3D − 2K、k3d2 = 3K − 2D（同一對 K/D 的兩種鏡像慣例，畫面兩者都要顯示）。
     */
    public record IndicatorPoint(
            LocalDate tradingDate,
            /** 週線 MA5（Task 265）：台股慣例的 5 個交易日。 */
            BigDecimal ma5,
            BigDecimal ma20,
            BigDecimal ma60,
            BigDecimal ma240,
            BigDecimal k,
            BigDecimal d,
            BigDecimal j9,
            BigDecimal k3d2,
            BigDecimal rsv,
            // Task 262：走勢圖指標選單。參數對齊 Yahoo 股市台股頁（實測 2330 2026-07-31）。
            BigDecimal ema12,
            BigDecimal ema26,
            BigDecimal dif,
            BigDecimal macd,
            BigDecimal osc,
            BigDecimal rsi5,
            BigDecimal rsi10,
            BigDecimal bias10,
            BigDecimal bias20,
            BigDecimal b10b20,
            BigDecimal wr9) {}

    /** MACD 一族逐期輸出（Task 262）；暖機不足者為 EMPTY。 */
    private record MacdPoint(BigDecimal ema12, BigDecimal ema26, BigDecimal dif, BigDecimal macd, BigDecimal osc) {}

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

        BigDecimal ma5   = simpleMa(series, 5);
        BigDecimal ma20  = simpleMa(series, 20);
        BigDecimal ma60  = simpleMa(series, 60);
        BigDecimal ma240 = simpleMa(series, 240);

        // Task 281：單趟 kdSeriesAsc 同時供當期 KD／前一期 KD／擴充指標三者取值。
        // 值與「跑兩趟 stockKd」逐位相同（bit-identical），證明如下：
        // kdSeriesAsc 是對 asc 序列的**前綴相依前向遞迴**——out[i] 只依賴 asc[0..i]
        //（視窗 asc[i-8..i] ＋ 由 index 0 累進的 k/d）。舊版的 previous 走
        // stockKd(desc.subList(1, n))，其反轉後的 asc 正是完整 asc 的前綴 asc[0..n-2]，
        // 故尾筆恆等於完整序列的 out[n-2]。
        // 長度守門亦自然等價：kdSeriesAsc 對 i < 8 一律填 KdPoint.EMPTY（k/d 為 null），
        // 與舊版 stockKd 在 size < 9 回 KdValues.EMPTY 相同；唯一需要的判斷是 n >= 2 的索引下界。
        List<StockPriceHistory> asc = new ArrayList<>(series).reversed();
        List<KdPoint> kd = kdSeriesAsc(asc);
        int last = asc.size() - 1;
        KdPoint currentKd = kd.get(last);
        KdPoint previousKd = last >= 1 ? kd.get(last - 1) : KdPoint.EMPTY;

        return new FullIndicators(
                ma20, ma60, ma240,
                currentKd.k(), currentKd.d(),
                previousKd.k(), previousKd.d(),
                ma5,
                extendedOf(asc, kd));
    }

    /**
     * asc 序列最新一期的擴充指標（Task 281）：J9／K3D2／RSV ＋ MACD 一族 ＋ RSI5／RSI10
     * ＋ BIAS10／BIAS20／B10−B20 ＋ W%R9。
     *
     * <p><b>不含任何新的遞迴</b>——一律呼叫 Task 261／262 既有的序列核心後取尾筆；
     * KD 那一趟由呼叫端傳入，避免重複計算。</p>
     */
    private static ExtendedIndicators extendedOf(List<StockPriceHistory> asc, List<KdPoint> kd) {
        if (asc.isEmpty()) return ExtendedIndicators.EMPTY;
        int i = asc.size() - 1;

        KdPoint p = kd.get(i);
        List<MacdPoint> macd = macdSeriesAsc(asc);
        Double rsi5 = rsiSeriesAsc(asc, 5)[i];
        Double rsi10 = rsiSeriesAsc(asc, 10)[i];
        Double b10 = biasRaw(asc, i, 10);
        Double b20 = biasRaw(asc, i, 20);
        MacdPoint m = macd.get(i);
        // 威廉指標由 RSV 直接導出（W%R9 = 100 − RSV9 為代數恆等式），與 indicatorSeries 逐字同式
        BigDecimal wr9 = p.rsv() == null
                ? null
                : BigDecimal.valueOf(100).subtract(p.rsv()).setScale(2, RoundingMode.HALF_UP);

        return new ExtendedIndicators(
                p.j9(), p.k3d2(), p.rsv(),
                m.ema12(), m.ema26(), m.dif(), m.macd(), m.osc(),
                rsi5 == null ? null : scale2(rsi5),
                rsi10 == null ? null : scale2(rsi10),
                b10 == null ? null : scale2(b10),
                b20 == null ? null : scale2(b20),
                (b10 == null || b20 == null) ? null : scale2(b10 - b20),
                wr9);
    }

    /**
     * 走勢圖用的整段指標序列：[start, end] 逐日的 MA20/60/240 + K/D/J9/K3D2/RSV（Task 261）
     * ＋ EMA12/EMA26/DIF/MACD/OSC、RSI5/RSI10、BIAS10/BIAS20/B10−B20、W%R9（Task 262 指標選單）。
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
            List<MacdPoint> macd = macdSeriesAsc(asc);
            Double[] rsi5 = rsiSeriesAsc(asc, 5);
            Double[] rsi10 = rsiSeriesAsc(asc, 10);

            List<IndicatorPoint> out = new ArrayList<>();
            for (int i = 0; i < asc.size(); i++) {
                LocalDate date = asc.get(i).getTradingDate();
                if (date.isBefore(start)) continue;   // 暖機段只參與計算、不回傳
                KdPoint p = kd.get(i);
                MacdPoint m = macd.get(i);
                Double b10 = biasRaw(asc, i, 10);
                Double b20 = biasRaw(asc, i, 20);
                // 威廉指標由 RSV 直接導出：W%R9 = 100 − RSV9 為代數恆等式
                // （(HH−C)/(HH−LL) = 1 − RSV/100），同視窗、同 fallback、HH==LL 同取 50
                BigDecimal wr9 = p.rsv() == null
                        ? null
                        : BigDecimal.valueOf(100).subtract(p.rsv()).setScale(2, RoundingMode.HALF_UP);
                out.add(new IndicatorPoint(date,
                        maAt(asc, i, 5),
                        maAt(asc, i, 20), maAt(asc, i, 60), maAt(asc, i, 240),
                        p.k(), p.d(), p.j9(), p.k3d2(), p.rsv(),
                        m.ema12(), m.ema26(), m.dif(), m.macd(), m.osc(),
                        rsi5[i] == null ? null : scale2(rsi5[i]),
                        rsi10[i] == null ? null : scale2(rsi10[i]),
                        b10 == null ? null : scale2(b10),
                        b20 == null ? null : scale2(b20),
                        (b10 == null || b20 == null) ? null : scale2(b10 - b20),
                        wr9));
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
     * 指數日線 → {@link StockPriceHistory} 的<b>唯一</b>映射（Task 281 抽出共用）。
     *
     * <p>{@code high}／{@code low} 直接進 KD 的 RSV 分母與 MACD 的 DI 價基，漏抄不會報錯只會算錯；
     * 舊資料為 null 時由序列核心自行 fallback close（既有慣例，不在此補值）。
     * Task 276 之後要加成交量時，只要改這一支。</p>
     *
     * <p>Task 337 起放寬為 package-private，供同 package 的 {@code MarketAnalysisService} 把
     * 「純 DB 完成日序列」映射後餵給 {@link #computeFromSeries(List)}。這只是純欄位映射、
     * <b>不是計算入口</b>，故不會製造繞過 {@link #isTaiex(String, String)} 的第二條計算路徑
     * （與放寬 {@link #computeAllForTaiex()} 的性質完全不同——後者一律不得放寬）。</p>
     */
    static StockPriceHistory toRow(TwseIndexDailyHistory d, String stockCode, String market) {
        return StockPriceHistory.builder()
                .stockCode(stockCode).market(market).tradingDate(d.getTradingDate())
                .closePrice(d.getClosePoint())
                .highPrice(d.getHighPoint())
                .lowPrice(d.getLowPoint())
                .build();
    }

    /**
     * 0000 台股大盤：指數日線映射成 StockPriceHistory 後餵同一份序列核心，避免為型別差異另長一套
     * KD 遞迴——全站 KD 只有 {@link #kdSeriesAsc} 這一份（Task 304 起 {@link #computeAllForTaiex()}
     * 的當期／前一期 KD 亦改走同一份，taiexKd 已刪除）；taiexSimpleMa 服務 computeAll() 的 MA
     * 既有路徑，不動。
     */
    private List<StockPriceHistory> taiexSeriesAsc(String stockCode, String market, LocalDate end) {
        List<StockPriceHistory> asc = twseDailyRepo
                .findByTradingDateBetweenOrderByTradingDateAsc(EPOCH_START, end)
                .stream()
                .map(d -> toRow(d, stockCode, market))
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

    /**
     * KD9 序列核心（Task 261）：asc 最早在前，回傳與輸入等長、逐期的 K/D/J9/K3D2/RSV，
     * 暖機不足 9 筆者為 {@link KdPoint#EMPTY}。
     * {@link #computeFromSeries} 走這裡<b>單趟</b>取尾筆（當期 KD）與倒數第二筆（前一期 KD）——
     * 全站股票 KD 只有這一份遞迴（Task 281 起連 previous 也不再另跑一趟）。
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
     * DI（需求指數）＝ (最高 + 最低 + 2×收盤) / 4，MACD 一族的價基。
     *
     * <b>台股慣例用 DI 而非收盤價</b>：以 2330 全序列實算，用收盤價得 EMA12 = 2338.6794
     * （與 Yahoo 的 2338.96 差 0.28），用 DI 得 2338.9583（差 0.0017）。
     * high/low 缺值時 fallback close，沿用 {@link #kdSeriesAsc} 的既有慣例（0000 大盤舊資料即如此）。
     */
    private static double diOf(StockPriceHistory h) {
        double c = h.getClosePrice().doubleValue();
        double hi = h.getHighPrice() != null ? h.getHighPrice().doubleValue() : c;
        double lo = h.getLowPrice()  != null ? h.getLowPrice().doubleValue()  : c;
        return (hi + lo + 2 * c) / 4;
    }

    /**
     * n 期 EMA，以「前 n 筆的簡單平均」作 seed、之前為 null。
     *
     * 刻意不用 src[0] 當 seed：本專案歷史只保留 10 年而前端 start 也是 10 年前，
     * 返回區間內沒有暖機緩衝，seed 取首筆會讓最左端出現「DIF/OSC 從 0 慢慢張開」的假象。
     */
    private static Double[] emaWithSmaSeed(double[] src, int n) {
        Double[] out = new Double[src.length];
        if (src.length < n) return out;
        double sum = 0;
        for (int i = 0; i < n; i++) sum += src[i];
        out[n - 1] = sum / n;
        double k = 2.0 / (n + 1);
        for (int i = n; i < src.length; i++) out[i] = src[i] * k + out[i - 1] * (1 - k);
        return out;
    }

    /** MACD 一族序列（Task 262）：EMA12／EMA26／DIF／MACD／OSC，價基為 DI。 */
    private static List<MacdPoint> macdSeriesAsc(List<StockPriceHistory> asc) {
        int len = asc.size();
        double[] di = new double[len];
        for (int i = 0; i < len; i++) di[i] = diOf(asc.get(i));

        Double[] e12 = emaWithSmaSeed(di, 12);
        Double[] e26 = emaWithSmaSeed(di, 26);

        Double[] dif = new Double[len];
        for (int i = 0; i < len; i++) {
            if (e12[i] != null && e26[i] != null) dif[i] = e12[i] - e26[i];
        }
        // MACD = DIF 的 9 日 EMA，同樣以 SMA seed（DIF 自第 25 期起有值 → MACD 自第 33 期起有值）
        Double[] macd = new Double[len];
        int difStart = -1;
        for (int i = 0; i < len; i++) if (dif[i] != null) { difStart = i; break; }
        if (difStart >= 0 && len - difStart >= 9) {
            double sum = 0;
            for (int i = difStart; i < difStart + 9; i++) sum += dif[i];
            int seedIdx = difStart + 8;
            macd[seedIdx] = sum / 9;
            double k = 2.0 / 10;
            for (int i = seedIdx + 1; i < len; i++) macd[i] = dif[i] * k + macd[i - 1] * (1 - k);
        }

        List<MacdPoint> out = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            // 五個欄位各自獨立判斷暖機：ema12 自第 11 期、ema26 自第 25 期起有值，
            // 不可用「任一為 null 就整筆 EMPTY」——那會讓已算出的 ema12 在第 11~24 期被連坐清成 null
            BigDecimal oscVal = (dif[i] != null && macd[i] != null) ? scale2(dif[i] - macd[i]) : null;
            out.add(new MacdPoint(
                    e12[i] == null ? null : scale2(e12[i]),
                    e26[i] == null ? null : scale2(e26[i]),
                    dif[i] == null ? null : scale2(dif[i]),
                    macd[i] == null ? null : scale2(macd[i]),
                    oscVal));
        }
        return out;
    }

    /**
     * RSI 序列（Task 262），採 <b>Wilder 平滑</b>——首值為前 n 期漲跌幅的簡單平均，
     * 之後 {@code avg = (avg × (n−1) + 本期) / n}。
     *
     * 實測 2330 於 2026-07-31：Wilder 得 RSI5 = 65.78／RSI10 = 56.53，與 Yahoo 逐位相同；
     * 簡單移動平均得 60.00／61.95，明顯不符。兩者 RSI10 差 5.4 點，不可混用。
     */
    private static Double[] rsiSeriesAsc(List<StockPriceHistory> asc, int n) {
        int len = asc.size();
        Double[] out = new Double[len];
        if (len <= n) return out;

        double[] gain = new double[len];
        double[] loss = new double[len];
        for (int i = 1; i < len; i++) {
            double diff = asc.get(i).getClosePrice().doubleValue() - asc.get(i - 1).getClosePrice().doubleValue();
            gain[i] = Math.max(0, diff);
            loss[i] = Math.max(0, -diff);
        }
        double avgGain = 0, avgLoss = 0;
        for (int i = 1; i <= n; i++) { avgGain += gain[i]; avgLoss += loss[i]; }
        avgGain /= n; avgLoss /= n;
        out[n] = rsiOf(avgGain, avgLoss);
        for (int i = n + 1; i < len; i++) {
            avgGain = (avgGain * (n - 1) + gain[i]) / n;
            avgLoss = (avgLoss * (n - 1) + loss[i]) / n;
            out[i] = rsiOf(avgGain, avgLoss);
        }
        return out;
    }

    private static double rsiOf(double avgGain, double avgLoss) {
        if (avgLoss == 0) return avgGain == 0 ? 50 : 100;   // 連續平盤 → 50；只漲不跌 → 100
        return 100 - 100 / (1 + avgGain / avgLoss);
    }

    /**
     * 乖離率 BIASn ＝ (收盤 − MAn) / MAn × 100，回傳未捨入值（供 b10b20 相減後才捨入）。
     * 刻意重用已捨入的 {@link #maAt}——保住 Task 261 的「新→舊」累加方向，誤差 < 0.001 個百分點。
     */
    private static Double biasRaw(List<StockPriceHistory> asc, int i, int days) {
        BigDecimal ma = maAt(asc, i, days);
        if (ma == null || ma.signum() == 0) return null;
        double m = ma.doubleValue();
        return (asc.get(i).getClosePrice().doubleValue() - m) / m * 100;
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

            BigDecimal ma5   = taiexSimpleMa(desc, 5);
            BigDecimal ma20  = taiexSimpleMa(desc, 20);
            BigDecimal ma60  = taiexSimpleMa(desc, 60);
            BigDecimal ma240 = taiexSimpleMa(desc, 240);

            // Task 304：先把「已含今日 live 合成列」的同一份 desc 映射成 StockPriceHistory，
            // 單趟 kdSeriesAsc 同時取當期／前一期／擴充指標三者（taiexKd 已刪除，不再各自跑一趟）。
            // bit-identical 論證見 kdSeriesAsc／computeFromSeries 的方法註解——同一套前綴相依前向
            // 遞迴，previous 取單趟結果的倒數第二筆與舊版對 subList 重算一趟逐位相同。
            // 整段留在既有的 try 內——例外逸出會被 TradingRadarService 的 catch 放大成整張大盤卡
            // DATA_INCOMPLETE、全部個股停發訊號。
            List<StockPriceHistory> ascRows = desc.reversed().stream()
                    .map(d -> toRow(d, "0000", "台股"))
                    .toList();
            List<KdPoint> kd = kdSeriesAsc(ascRows);
            int last = ascRows.size() - 1;
            KdPoint currentKd = kd.get(last);
            KdPoint previousKd = last >= 1 ? kd.get(last - 1) : KdPoint.EMPTY;
            return new FullIndicators(
                    ma20, ma60, ma240,
                    currentKd.k(), currentKd.d(),
                    previousKd.k(), previousKd.d(),
                    ma5,
                    extendedOf(ascRows, kd));
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

    /**
     * IXIC（那斯達克綜合指數）大盤情境（Task 294）：供美股個股使用，取代台股組的 TAIEX 技術面。
     *
     * <p><b>刻意不併入即時價</b>：IXIC 目前無對應的 Redis 即時報價來源，且個股自身的即時價已由既有
     * {@link PriceQueryService} 路徑處理，不影響個股本身的進出場判斷即時性——純用
     * {@code us_index_daily_history} 表 {@code index_code='IXIC'} 的完成日序列。</p>
     *
     * <p>MA 核心比照 {@link #computeAllForTaiex()} 的<b>呼叫形狀</b>（專屬 desc-list helper
     * {@link #nasdaqSimpleMa}），<b>算術路徑自 Task 336 起刻意不同</b>——本支為 BigDecimal 精確和，
     * {@link #taiexSimpleMa} 仍為 double 累加，理由見 {@link #nasdaqSimpleMa} 的方法註解。
     * 同樣<b>不呼叫</b> {@link #computeFromSeries(List)}——該方法吃的是個股用的
     * {@link StockPriceHistory}，{@code us_index_daily_history} 對應的是 {@link UsIndexDailyHistory}，
     * 型別不同。KD（當期／前一期）與擴充指標則先以 {@link #toRow(UsIndexDailyHistory, String, String)}
     * 轉型，共用同一份 {@link #kdSeriesAsc} 單趟結果（Task 304，nasdaqKd 已刪除）：尾筆＝當期、
     * 倒數第二筆＝前一期，逐筆再餵 {@link #extendedOf}，全站只有這一份 KD 遞迴。
     * bit-identical 論證見 {@link #kdSeriesAsc} 與 {@link #computeFromSeries(List)} 的方法註解。</p>
     *
     * <p>本方法為新增的獨立入口，由 {@code TradingRadarService} 直接呼叫——IXIC 不是「個股」，
     * 沒有 stockCode，故不透過 {@link #computeAll(String, String)} 的 {@code isTaiex(...)} 分支。</p>
     */
    @Transactional(readOnly = true)
    public FullIndicators computeAllForNasdaq() {
        try {
            List<UsIndexDailyHistory> desc = new ArrayList<>(
                    usIndexDailyHistoryRepo.findTopNByIndexCodeOrderByTradingDateDesc("IXIC", 240));
            if (desc.isEmpty()) return FullIndicators.EMPTY;

            BigDecimal ma5   = nasdaqSimpleMa(desc, 5);
            BigDecimal ma20  = nasdaqSimpleMa(desc, 20);
            BigDecimal ma60  = nasdaqSimpleMa(desc, 60);
            BigDecimal ma240 = nasdaqSimpleMa(desc, 240);

            // Task 304：比照 computeAllForTaiex()——先映射 ascRows 再單趟 kdSeriesAsc，
            // 同時取當期／前一期／擴充指標三者（nasdaqKd 已刪除，不再各自跑一趟）。
            List<StockPriceHistory> ascRows = desc.reversed().stream()
                    .map(d -> toRow(d, "IXIC", "美股"))
                    .toList();
            List<KdPoint> kd = kdSeriesAsc(ascRows);
            int last = ascRows.size() - 1;
            KdPoint currentKd = kd.get(last);
            KdPoint previousKd = last >= 1 ? kd.get(last - 1) : KdPoint.EMPTY;
            return new FullIndicators(
                    ma20, ma60, ma240,
                    currentKd.k(), currentKd.d(),
                    previousKd.k(), previousKd.d(),
                    ma5,
                    extendedOf(ascRows, kd));
        } catch (Exception e) {
            log.warn("compute NASDAQ (IXIC) indicators failed", e);
            return FullIndicators.EMPTY;
        }
    }

    /**
     * 指數日線 → {@link StockPriceHistory} 的映射（{@link UsIndexDailyHistory} 版，Task 294）。
     * 與 {@link #toRow(TwseIndexDailyHistory, String, String)} 同一慣例：只搬 high／low／close，
     * stockCode／market 僅供序列核心內部運算識別，不對外洩漏。
     */
    private static StockPriceHistory toRow(UsIndexDailyHistory d, String stockCode, String market) {
        return StockPriceHistory.builder()
                .stockCode(stockCode).market(market).tradingDate(d.getTradingDate())
                .closePrice(d.getClosePoint())
                .highPrice(d.getHighPoint())
                .lowPrice(d.getLowPoint())
                .build();
    }

    /**
     * IXIC 均線：BigDecimal 精確和後 divide(days, 2, HALF_UP)（Task 336）。
     *
     * <p><b>刻意與同類別的 {@link #simpleMa}／{@link #taiexSimpleMa}／{@link #maAt} 三支 double
     * 累加版不同，不是漏改。</b>IXIC 的同一個顯示值全站有三份實作——本支、BFF 的
     * {@code MarketIndexChartService.movingAverage(...)}、以及匯出的
     * {@code ExcelExportService.indexMaAt(...)}（同讀 {@code us_index_daily_history} 的 IXIC
     * 已落地日線收盤、都不併即時價），另外兩份本就是精確路徑。double 累加會在精確商恰為
     * {@code x.xx5} 時與精確路徑差 0.01——實測 2016-06-10~2026-08-14 的 2559 筆 IXIC 日線中，
     * MA20 有 2 日（2018-07-17、2025-12-22）命中。改為精確路徑後三份等值<b>由構造保證</b>
     * （同一批收盤、同一視窗、同樣 divide(w, 2, HALF_UP)；BigDecimal 加法可結合，故累加方向
     * 不影響結果），不需抽樣論證。</p>
     *
     * <p>另外三支 helper 服務台股大盤與個股、屬 structure.md §3.2 鐵則 4 具名例外 (3)（含 live）
     * 的範圍，其收斂前提是先決定「MA 要不要併 live」，屬獨立任務；本次不得一併改動。連帶地
     * {@code BacktestService.buildUsMarketRegimes()} 走的 {@link #simpleMa} 仍是 double，故本支
     * 改動後 live 與回測的 IXIC 均線由 bit-identical 變為存在 0.01 上限的分歧（歷史重放 0 日
     * 翻動 regime），該殘餘為刻意保留、已登記於 structure.md。</p>
     */
    private static BigDecimal nasdaqSimpleMa(List<UsIndexDailyHistory> desc, int days) {
        if (desc.size() < days) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < days; i++) sum = sum.add(desc.get(i).getClosePoint());
        return sum.divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP);
    }
}
