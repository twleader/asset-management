package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.NewsRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 公開資訊「均線突破」快照組裝（Task 207）：由 DB 日收盤計算季線 MA60／年線 MA240（SMA），偵測
 * <b>台股大盤（0000）／0050／00881</b> 在<b>前一交易日</b>收盤對均線的<b>漲破／跌破</b>，命中則<b>每則事件各組一則</b>
 * {@link NewsRow} 併入每輪公開資訊（同進 SRPP 公開資訊 JSON 與今日股市分析）。
 *
 * <p>均線定義與今日股市分析／股票分析頁的 {@code TechnicalIndicatorService}（月線 MA20／季線 MA60／年線 MA240、
 * 皆 SMA）<b>一致</b>；因該 service 在 business-services、爬蟲（external-materials-service）不可跨呼叫，故本 client
 * 讀相同來源（大盤走 {@code twse_index_daily_history.close_point}、ETF 走 {@code stock_price_history.close_price}）
 * 自行以相同定義計算，維持「同一事實、同一定義」。
 *
 * <p><b>突破判定（逐日對齊自身均線的穿越）</b>：令前一交易日收盤 {@code cPrev}、前二交易日收盤 {@code cPrev2}、
 * 各日自身的 MA_n（{@code maT}／{@code maT1}）——
 * <ul>
 *   <li><b>漲破</b>：{@code cPrev2 ≤ maT1} 且 {@code cPrev > maT}（由均線之下/之上穿到之上）；</li>
 *   <li><b>跌破</b>：{@code cPrev2 ≥ maT1} 且 {@code cPrev < maT}。</li>
 * </ul>
 * 資料不足（{@code < n+1} 筆，算不出前一/前二日的 MA_n）則略過該均線；三檔皆無突破則本輪不寫入（不塞雜訊）。
 *
 * <p><b>分割／反分割防呆（兩層）</b>：{@code stock_price_history} 存<b>未還原權值</b>的原始收盤（如 0050 於 2025-06
 * 分割使收盤驟降 ~4 倍），故——
 * <ol>
 *   <li><b>單日層</b>：前一交易日對前二日變動 &gt; {@link #MAX_DAILY_MOVE} → 該標的本輪整檔跳過（擋分割<b>當日</b>）；</li>
 *   <li><b>視窗層</b>：該均線取樣視窗 {@code [n-1-period, n-1]} 內<b>任一相鄰兩日</b>變動 &gt; 門檻 → 該均線本輪略過，
 *       直到舊權值滾出視窗（擋分割<b>之後</b>）。缺了這層，受污染的 MA 會以每日固定幅度單調下滑，
 *       在分割後約第 60／240 個交易日<b>自己穿過靜止的股價</b>，噴出「股價沒動卻漲破年線」的假訊號。</li>
 * </ol>
 *
 * <p>比照 Task 180/185 快照：{@code category=ma-cross}／{@code source=ma-cross}／{@code region=TW}。
 * <b>{@code publishedAt} 取該事件的「資料日」（非抓取當下 {@code now()}）</b>，使突破事件依真實發生日隨新聞時效窗
 * 自然老化——否則每輪重抓都把 {@code published_at} 推到今天，舊突破會被當「今天的新聞」重複餵入分析達數日。
 * 因資料日可能早於匯出端的 {@code resolveTradingCutoff()}，{@code loadTodayPublicInfoForExport} 對本 category
 * 開 cutoff 例外（改以 {@code fetched_at} 當日為準），避免大盤指數表落後時該列被 SRPP JSON 靜默濾掉。
 *
 * <p><b>{@code url} 帶事件識別 fragment</b>（{@code #代號-ma期數-資料日}）：去重鍵為
 * {@code sha256(source|url|category)}，若三者皆固定則 {@code ma-cross} 永遠只有一列，後來的事件會整列覆寫先前的，
 * 使分析視窗內的既有事件被靜默吞掉且無法重建（{@code detect()} 只讀最後兩根、不補偵測）。帶 fragment 後
 * 每個 (標的, 均線, 資料日) 各一列、同輪重跑仍冪等覆寫同一列。
 * {@code ma-cross} 為非 {@code news} category → {@code PublicInfoStockFilter} 一律保留、下游分析歸「量化資訊」全列。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MaCrossSnapshotClient {

    private final StockSourceQuery source;

    /** 標的：顯示名、代號、是否為大盤指數（大盤走 twse_index_daily_history，其餘走 stock_price_history）。 */
    private record Target(String label, String code, boolean index) {}

    private static final List<Target> TARGETS = List.of(
            new Target("台股大盤", "0000", true),
            new Target("0050", "0050", false),
            new Target("00881", "00881", false));

    /** 均線規格：顯示名＋期數（交易日）。季線 MA60、年線 MA240（與 TechnicalIndicatorService 一致）。 */
    private record MaSpec(String name, int period) {}

    private static final List<MaSpec> MAS = List.of(
            new MaSpec("季線", 60),
            new MaSpec("年線", 240));

    /** 撈回的日收盤筆數：需 ≥ 年線 240 + 1（算前一/前二日 MA240）；取 250 留餘裕。 */
    private static final int LOOKBACK = 250;

    private static final String MARKET_TW = "台股";

    private static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");

    /** 公開資訊列的固定連結（TWSE 大盤日 OHLC，即本專案 TAIEX 日收盤的實際上游）；實際去重靠其後的 fragment。 */
    private static final String BASE_URL = "https://www.twse.com.tw/zh/indices/taiex/mi-5min-hist.html";

    /**
     * 相鄰兩交易日收盤變動門檻（15%）：超過視為<b>股票分割／反分割／資料跳空</b>而非真實價格變動。台股 ETF/個股
     * 單日 ±10% 漲跌幅限制、大盤幾無單日 &gt;10%，故 15% 只擋資料不連續、絕不誤殺真實突破（真突破多發生於 ±1~3%
     * 的平常日）。單日層用於擋分割當日、視窗層用於擋分割後的 MA 污染（見 class javadoc）。
     */
    private static final BigDecimal MAX_DAILY_MOVE = new BigDecimal("0.15");

    public List<NewsRow> fetchAll() {
        List<Event> events = new ArrayList<>();
        for (Target t : TARGETS) {
            try {
                List<StockSourceQuery.ClosePoint> series = t.index()
                        ? source.loadRecentTaiexCloses(LOOKBACK)
                        : source.loadRecentStockCloses(t.code(), MARKET_TW, LOOKBACK);
                events.addAll(detect(t, series));
            } catch (Exception e) {
                log.warn("均線突破偵測失敗（{}）：{}", t.code(), e.getMessage());
            }
        }
        if (events.isEmpty()) return List.of();

        String summary = "來源：由台股大盤／0050／00881 日收盤計算之季線 MA60、年線 MA240（SMA），"
                + "偵測最近交易日收盤對均線的漲破／跌破（資料日見標題；與今日股市分析技術指標同定義）。";
        List<NewsRow> rows = new ArrayList<>();
        for (Event e : events) {
            // 每則事件各一列：url 帶 (代號, 均線期數, 資料日) fragment 使去重鍵具事件識別度，避免互相覆寫。
            String url = BASE_URL + "#" + e.code() + "-ma" + e.period() + "-" + e.dataDate();
            // 以台股收盤 13:30 Asia/Taipei 為該交易日代表時點。
            Instant publishedAt = e.dataDate().atTime(13, 30).atZone(TW_ZONE).toInstant();
            rows.add(new NewsRow("台股均線突破：" + e.text(), "ma-cross", url, "ma-cross", "TW", summary, publishedAt));
        }
        return rows;
    }

    /** 一則突破事件：顯示字串＋標的代號／均線期數／資料日（後三者供組去重用的 url fragment）。 */
    private record Event(String text, String code, int period, LocalDate dataDate) {}

    /** 對單一標的偵測季線／年線的漲破/跌破，回傳 0~2 則事件。 */
    private List<Event> detect(Target t, List<StockSourceQuery.ClosePoint> series) {
        List<Event> out = new ArrayList<>();
        int n = series.size();
        if (n < 2) return out;   // 至少要前一交易日＋前二交易日
        BigDecimal cPrev = series.get(n - 1).close();
        BigDecimal cPrev2 = series.get(n - 2).close();
        LocalDate dataDate = series.get(n - 1).date();
        if (cPrev == null || cPrev2 == null || cPrev2.signum() == 0) return out;

        // 第一層：單日變動 >15% → 疑似分割/資料跳空（非真實穿越），跳過該標的當輪偵測。
        if (exceedsThreshold(cPrev2, cPrev)) {
            log.warn("均線突破偵測：{} 前一交易日({})單日變動過大，疑似分割/資料跳空，本輪跳過", t.label(), dataDate);
            return out;
        }

        for (MaSpec ma : MAS) {
            if (n < ma.period() + 1) continue;   // 算不出前一/前二日的均線 → 該均線本輪略過
            // 第二層：均線取樣視窗內若有跳空，MA 仍被舊權值污染 → 略過該均線，直到污染滾出視窗。
            if (windowHasGap(series, n - 1, ma.period())) {
                log.warn("均線突破偵測：{} 的 {}(MA{}) 取樣視窗內含跳空（疑似分割），本輪略過該均線",
                        t.label(), ma.name(), ma.period());
                continue;
            }
            BigDecimal maT = sma(series, n - 1, ma.period());    // 前一交易日的均線
            BigDecimal maT1 = sma(series, n - 2, ma.period());   // 前二交易日的均線
            if (maT == null || maT1 == null) continue;

            String dir = null;
            if (cPrev2.compareTo(maT1) <= 0 && cPrev.compareTo(maT) > 0) dir = "漲破";
            else if (cPrev2.compareTo(maT1) >= 0 && cPrev.compareTo(maT) < 0) dir = "跌破";
            if (dir != null) {
                out.add(new Event(String.format("%s 收 %s %s%s(MA%d %s)〔資料日 %s〕",
                        t.label(), num(cPrev), dir, ma.name(), ma.period(), num(maT), dataDate),
                        t.code(), ma.period(), dataDate));
            }
        }
        return out;
    }

    /**
     * 均線取樣視窗 {@code [endIdx-period, endIdx]}（含算 maT1 所需的多一筆）內，是否存在相鄰兩日變動超過門檻。
     * 有則代表視窗跨越分割點、MA 被舊權值污染，該均線本輪不可信。
     */
    private static boolean windowHasGap(List<StockSourceQuery.ClosePoint> series, int endIdx, int period) {
        int start = Math.max(0, endIdx - period);
        for (int i = start + 1; i <= endIdx; i++) {
            BigDecimal prev = series.get(i - 1).close();
            BigDecimal cur = series.get(i).close();
            if (prev == null || cur == null || prev.signum() == 0) return true;   // 資料不完整同樣不可信
            if (exceedsThreshold(prev, cur)) return true;
        }
        return false;
    }

    /** |cur-prev|/prev 是否超過 {@link #MAX_DAILY_MOVE}。prev 須非零（呼叫端已保證）。 */
    private static boolean exceedsThreshold(BigDecimal prev, BigDecimal cur) {
        return cur.subtract(prev).abs()
                .divide(prev, 4, RoundingMode.HALF_UP)
                .compareTo(MAX_DAILY_MOVE) > 0;
    }

    /** series（由舊到新）中，以索引 endIdx（含）結尾往前 n 筆收盤的 SMA；筆數不足或含 null 回 null。 */
    private static BigDecimal sma(List<StockSourceQuery.ClosePoint> series, int endIdx, int n) {
        int start = endIdx - n + 1;
        if (start < 0) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = start; i <= endIdx; i++) {
            BigDecimal c = series.get(i).close();
            if (c == null) return null;
            sum = sum.add(c);
        }
        return sum.divide(BigDecimal.valueOf(n), 2, RoundingMode.HALF_UP);
    }

    /** 千分位＋2 位小數（固定 US locale，避免容器 locale 影響分位符號）。 */
    private static String num(BigDecimal v) {
        return new DecimalFormat("#,##0.00", new DecimalFormatSymbols(Locale.US)).format(v);
    }
}
