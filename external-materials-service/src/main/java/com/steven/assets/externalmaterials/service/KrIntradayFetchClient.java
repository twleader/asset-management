package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.NewsRow;
import com.steven.assets.externalmaterials.client.PriceFetchClient;
import com.steven.assets.externalmaterials.client.PriceFetchClient.KrIntradayQuote;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 韓股「盤中」快照組裝（Task 193）：即時抓 Yahoo Finance 組一則 {@link NewsRow}（{@code category=kr-intraday}）
 * 併入每輪公開資訊，讓今日股市分析看得到<b>當日開盤動向</b>。
 *
 * <p><b>為什麼需要</b>：{@link MarketSnapshotFetchClient} 的既有韓股快照（{@code kr-market}，Task 185）純讀 DB，
 * 而其資料由 {@code IndexDailyRefreshScheduler}（07:00 Asia/Taipei＝08:00 KST，<b>韓股尚未開盤</b>）與
 * {@code KrStockPoller}（16:00 Asia/Taipei＝17:00 KST，<b>已收盤</b>）寫入，故 08:20 那輪爬蟲組出的韓股快照
 * <b>恆為前一交易日收盤</b>。但韓股 09:00–15:30 KST <b>＝台北 08:00–14:30</b>，08:20 爬蟲執行時韓股已開盤 20 分鐘；
 * 三星電子／SK 海力士同為記憶體權值、對台股 09:00 開盤具領先參考價值，其當日開盤動向卻完全沒進 08:45 的分析。
 *
 * <p><b>為什麼不併進 {@link MarketSnapshotFetchClient}</b>：後者契約明訂「皆讀已抓好的 DB 資料、不另打外部 API」，
 * 本 client <b>會即時打 Yahoo</b>，故另立 component 以維持該契約不被破壞。由 {@link NewsPoller#run} 以第四個
 * {@code rows.addAll(...)} 接線，與既有三個來源同 pattern。
 *
 * <p><b>不新增任何 {@code @Scheduled}</b>：掛 {@link NewsPoller} 既有輪次即可，故排程列表頁
 * {@code SchedulePublicBffController} 的靜態清單無須更動（該清單只列 {@code @Scheduled}）。
 * <b>產出與否只由時段閘門決定，與 {@link NewsPoller} 的執行時點無耦合</b>——現行 08:20／11:30 落在韓股盤中
 * 故產出，18:00 那輪在韓股收盤後故自然為空（當日收盤已由 {@code kr-market} 涵蓋，不重複）；
 * 日後調整爬蟲執行時點無須改動本類。
 *
 * <p><b>雙閘門（免自建韓國假日曆）</b>：repo 內無韓國假日資料，且설날／추석 屬農曆，{@code MarketCalendar} 的
 * {@code nthWeekday}／{@code goodFriday} 純函式算不出；{@code KrStockPoller} 靠「Yahoo 對非交易日不回 bar」繞過，
 * 盤中無法沿用（盤中得先知道「現在該不該抓」）。故：
 * <ol>
 *   <li><b>時段閘門</b>：台北 MON–FRI 08:00–14:30（本地時鐘，{@link Clock} 可注入供測試）。「是否盤中」一律由此判定。</li>
 *   <li><b>資料閘門</b>：{@link KrIntradayQuote#sessionDate}（Yahoo {@code meta.regularMarketTime} 換算 KST 之日期）
 *       ≠ 今日 KST → 判定韓國休市，不產出（休市時 Yahoo 回前一交易日 bar，日期天然對不上）。</li>
 * </ol>
 *
 * <p>三檔逐一 graceful（任一失敗或被資料閘門擋下只略過該項，比照既有 producer 慣例）；全空則不產列。
 * 沿用 Task 185 之「只餵資料給分析」：自動進 prompt 量化桶，不新增輸出欄位／前端專屬區塊。
 * 資料源為 Yahoo Finance（美國站，非中港澳）。
 */
@Slf4j
@Component
public class KrIntradayFetchClient {

    private static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");
    private static final ZoneId KR_ZONE = ZoneId.of("Asia/Seoul");

    /** 韓股 09:00–15:30 KST ＝台北 08:00–14:30（KST = 台北 +1h）。 */
    private static final LocalTime SESSION_OPEN_TW = LocalTime.of(8, 0);
    private static final LocalTime SESSION_CLOSE_TW = LocalTime.of(14, 30);

    private static final DateTimeFormatter KST_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** 快照標的：完整 Yahoo symbol → 顯示名（順序即標題順序，與 kr-market 快照一致：大盤在前）。 */
    private static final List<Symbol> SYMBOLS = List.of(
            new Symbol("^KS11", "KOSPI"),
            new Symbol("005930.KS", "三星電子"),
            new Symbol("000660.KS", "SK海力士"));

    private record Symbol(String yahooSymbol, String displayName) {}

    private final PriceFetchClient priceFetch;
    private final Clock clock;

    @Autowired
    public KrIntradayFetchClient(PriceFetchClient priceFetch) {
        this(priceFetch, Clock.system(TW_ZONE));
    }

    /** 測試用：注入固定 {@link Clock} 以模擬時點（時段閘門與 KST 今日皆由它推導）。 */
    KrIntradayFetchClient(PriceFetchClient priceFetch, Clock clock) {
        this.priceFetch = priceFetch;
        this.clock = clock;
    }

    /** @return 0 或 1 則盤中快照；非韓股盤中時段、或三檔皆無今日資料時回空。 */
    public List<NewsRow> fetchAll() {
        if (!inKrSession()) return List.of();

        LocalDate todayKst = ZonedDateTime.now(clock).withZoneSameInstant(KR_ZONE).toLocalDate();
        List<Entry> entries = new ArrayList<>(SYMBOLS.size());
        for (Symbol s : SYMBOLS) {
            try {
                priceFetch.fetchKrIntradayQuote(s.yahooSymbol())
                        .filter(q -> todayKst.equals(q.sessionDate()))   // 資料閘門：非今日＝韓國休市
                        .ifPresent(q -> entries.add(new Entry(s.displayName(), q)));
            } catch (Exception e) {
                log.warn("韓股盤中快照抓取失敗（{}）：{}", s.displayName(), e.getMessage());
            }
        }
        if (entries.isEmpty()) {
            // 盤中時段卻三檔全無今日資料：韓國休市（設날／추석等農曆假日）或 Yahoo 全失敗，兩者皆略過本輪。
            log.info("韓股盤中快照：時段內但無今日（{} KST）資料，判為韓國休市或來源失敗，本輪不產出", todayKst);
            return List.of();
        }
        return List.of(buildNewsRow(entries));
    }

    /** 時段閘門：台北 MON–FRI 08:00–14:30。「是否盤中」一律由本地時鐘判定，不看 Yahoo 時戳（KOSPI 實測回收盤後時間）。 */
    private boolean inKrSession() {
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(TW_ZONE);
        DayOfWeek dow = now.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return false;
        LocalTime t = now.toLocalTime();
        return !t.isBefore(SESSION_OPEN_TW) && !t.isAfter(SESSION_CLOSE_TW);
    }

    private record Entry(String name, KrIntradayQuote quote) {}

    private NewsRow buildNewsRow(List<Entry> entries) {
        String stamp = ZonedDateTime.now(clock).withZoneSameInstant(KR_ZONE).format(KST_STAMP);

        StringBuilder title = new StringBuilder("韓國股市盤中（").append(stamp).append(" KST）：");
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (i > 0) title.append("、");
            title.append(e.name()).append(" ").append(comma(e.quote().price())).append("（");
            if (e.quote().open() != null) title.append("開盤 ").append(comma(e.quote().open())).append("，");
            title.append("較昨收 ").append(changePct(e.quote().changePct())).append("）");
        }

        // summary 會餵入 LLM prompt 與 SRPP JSON：須明講這是盤中即時值而非收盤，並與同批 kr-market 快照消歧
        // （08:20 那輪兩則會同時出現在 prompt：kr-market＝前一交易日收盤、本則＝今日盤中）。
        String summary = "來源：Yahoo Finance（KOSPI ^KS11／三星電子 005930.KS／SK海力士 000660.KS；價格 KRW）。"
                + "此為韓股盤中即時快照（韓股 09:00–15:30 KST＝台北 08:00–14:30），非當日收盤值；"
                + "開盤價為當日開盤，漲跌% 對前一交易日收盤。"
                + "同批公開資訊另有「韓國股市（截至 … 收盤）」一則，為前一交易日收盤快照，與本則時點不同。";

        return new NewsRow(title.toString(), "kr-intraday",
                "https://finance.yahoo.com/quote/%5EKS11",
                "kr-intraday", "KR", summary, Instant.now(clock));
    }

    /** 價格千分位、2 位小數（固定 US locale，避免容器 locale 影響分位/小數符號）。比照 MarketSnapshotFetchClient。 */
    private static String comma(BigDecimal v) {
        return new DecimalFormat("#,##0.00", new DecimalFormatSymbols(Locale.US)).format(v);
    }

    /** 漲跌%（已為百分點單位）；缺料回 "—"。 */
    private static String changePct(BigDecimal pct) {
        if (pct == null) return "—";
        return String.format(Locale.US, "%+.2f%%", pct.doubleValue());
    }
}
