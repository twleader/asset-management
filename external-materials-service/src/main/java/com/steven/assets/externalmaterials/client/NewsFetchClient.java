package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 權威財經新聞抓取（Task 149.21）：玩股網 WantGoo（JSON API）＋ MoneyDJ 理財網（即時新聞 HTML）
 * ＋自由時報 財經 / 政治 / 國際（RSS，Task 180 增政治・國際以涵蓋央行・金管會政策、地緣政治、川普言論、Fed 政策）
 * ＋經濟日報（RSS）〔以上 {@code region=TW}〕＋<b>美國財經新聞 CNBC（Economy／Finance／Markets 三個財經專屬分類 RSS）
 * ＋Nasdaq（Markets RSS）</b>〔{@code region=US}、{@code source=cnbc}／{@code nasdaq}，Task 198〕。
 * 皆帶「真實發布時間」（WantGoo {@code time} epoch、MoneyDJ 列時間、RSS {@code pubDate} RFC-1123；CNBC 為「少秒＋GMT」、
 * Nasdaq 為數字時區，皆可由 {@link #parsePubDate} 之 {@code RFC_1123_DATE_TIME} 解析），較付費 web_search 的模型自報日期精準。
 * UA 一律 {@code Mozilla/5.0}（比照既有抓取慣例）。逐來源獨立 try/catch：單一來源失敗只 log warn、
 * 回空 list，不影響其他來源。**來源皆台灣／美國權威媒體，不抓中港澳。**（鉅亨網 cnyes 因言論偏頗已於 Task 149.22 移除。）
 *
 * <p><b>美國財經新聞（Task 198）</b>：CNBC／Nasdaq 為財經專屬分類 feed，故<b>不套</b> {@link EditorialNewsFilter}
 * 編輯政策過濾（其關鍵詞為中文、僅用於台灣混合型一般新聞 feed）。美國新聞 {@code category=news}，經
 * {@code PublicInfoStockFilter} 天然全留（個股判定只認台股 {@code -TW} 代號與 wantgoo tags），並計入今日股市分析的
 * 一般新聞上限（{@code LOCAL_NEWS_MAX}，依 {@code published_at} 排序，與台灣新聞共用額度）。
 */
@Slf4j
@Component
public class NewsFetchClient {

    /** 玩股網 WantGoo 頭條/各分類新聞 JSON API；回 {news:[{id,headline,summary,time(epoch ms)}]}。 */
    private static final String WANTGOO_URL =
            "https://www.wantgoo.com/news/all-headlines-by-category?v=20250924";
    /** MoneyDJ 理財網「即時新聞」列表 HTML（無可用 RSS，2026 已停）；列為 `MM/DD HH:MM`＋newsviewer 連結。 */
    private static final String MONEYDJ_URL =
            "https://www.moneydj.com/kmdj/news/newsreallist.aspx?a=MB010000";
    /** 自由時報財經 RSS。 */
    private static final String LTN_BUSINESS_RSS = "https://news.ltn.com.tw/rss/business.xml";
    /** 自由時報政治 RSS（Task 180）：涵蓋央行 / 金管會政策、兩岸・國安等政策面。 */
    private static final String LTN_POLITICS_RSS = "https://news.ltn.com.tw/rss/politics.xml";
    /** 自由時報國際 RSS（Task 180）：涵蓋地緣政治、川普言論、Fed 政策等國際財經政策。 */
    private static final String LTN_WORLD_RSS = "https://news.ltn.com.tw/rss/world.xml";
    /** 經濟日報（udn money）財經 RSS。 */
    private static final String UDN_MONEY_RSS = "https://money.udn.com/rssfeed/news/1001/5591?ch=money";

    // ===== 美國財經新聞 RSS（Task 198；region=US）=====
    /** CNBC 經濟（Fed／通膨／關稅等總經）RSS。 */
    private static final String CNBC_ECONOMY_RSS = "https://www.cnbc.com/id/20910258/device/rss/rss.html";
    /** CNBC 金融 RSS。 */
    private static final String CNBC_FINANCE_RSS = "https://www.cnbc.com/id/10000664/device/rss/rss.html";
    /** CNBC 投資／市場（Investing）RSS。 */
    private static final String CNBC_MARKETS_RSS = "https://www.cnbc.com/id/15839069/device/rss/rss.html";
    /** Nasdaq 市場（Markets）RSS。 */
    private static final String NASDAQ_MARKETS_RSS = "https://www.nasdaq.com/feed/rssoutbound?category=Markets";

    /** MoneyDJ 即時新聞列：`<td>MM/DD HH:MM</td><td><a href='...newsviewer.aspx?a=..' title="全標題">`。 */
    private static final Pattern MDJ_ROW = Pattern.compile(
            "(\\d{2}/\\d{2}\\s+\\d{2}:\\d{2})\\s*</td>\\s*<td>\\s*<a\\s+href='([^']+newsviewer\\.aspx[^']+)'[^>]*title=\"([^\"]*)\"",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern ITEM = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL);
    private static final Pattern LINK = Pattern.compile("<link>(.*?)</link>", Pattern.DOTALL);
    private static final Pattern PUBDATE = Pattern.compile("<pubDate>(.*?)</pubDate>", Pattern.DOTALL);
    private static final Pattern CDATA = Pattern.compile("<!\\[CDATA\\[(.*?)]]>", Pattern.DOTALL);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /** 抓全部權威新聞來源，逐來源 graceful，回合併後的清單。 */
    public List<NewsRow> fetchAll() {
        List<NewsRow> out = new ArrayList<>();
        // 純財經來源（玩股網／MoneyDJ）本就財經專屬，不套編輯政策過濾。
        out.addAll(safe("wantgoo", this::fetchWantgoo));
        out.addAll(safe("moneydj", this::fetchMoneydj));
        // 自由時報 財經／政治／國際 與經濟日報為「整個版面」的一般新聞，套 EditorialNewsFilter 編輯政策（Task 199）：
        // 財經一律留；中國新聞只留財經/北京政權；政治只留美日台歐盟＋影響市場地緣；台灣地方只留北北高；其餘濾除。
        // 取代舊 relevantOnly（僅政治/國際、只做財經・政策・地緣關鍵詞白名單），涵蓋更完整。
        out.addAll(safe("ltn-business", () -> EditorialNewsFilter.retain(fetchRss(LTN_BUSINESS_RSS, "ltn", "TW"))));
        out.addAll(safe("ltn-politics", () -> EditorialNewsFilter.retain(fetchRss(LTN_POLITICS_RSS, "ltn", "TW"))));
        out.addAll(safe("ltn-world", () -> EditorialNewsFilter.retain(fetchRss(LTN_WORLD_RSS, "ltn", "TW"))));
        out.addAll(safe("udn", () -> EditorialNewsFilter.retain(fetchRss(UDN_MONEY_RSS, "udn", "TW"))));
        // 美國財經新聞（Task 198）：CNBC 財經專屬三分類＋Nasdaq Markets。皆為財經 feed，故不套編輯政策過濾。
        out.addAll(safe("cnbc-economy", () -> fetchRss(CNBC_ECONOMY_RSS, "cnbc", "US")));
        out.addAll(safe("cnbc-finance", () -> fetchRss(CNBC_FINANCE_RSS, "cnbc", "US")));
        out.addAll(safe("cnbc-markets", () -> fetchRss(CNBC_MARKETS_RSS, "cnbc", "US")));
        out.addAll(safe("nasdaq-markets", () -> fetchRss(NASDAQ_MARKETS_RSS, "nasdaq", "US")));
        return out;
    }

    private interface Fetcher { List<NewsRow> get() throws Exception; }

    private List<NewsRow> safe(String name, Fetcher f) {
        try {
            List<NewsRow> rows = f.get();
            log.info("新聞抓取 {}：{} 則", name, rows.size());
            return rows;
        } catch (Exception e) {
            log.warn("新聞抓取 {} 失敗：{}", name, e.getMessage());
            return List.of();
        }
    }

    // ===== 玩股網 WantGoo（JSON）=====

    private List<NewsRow> fetchWantgoo() throws Exception {
        String body = get(WANTGOO_URL);
        JsonNode root = mapper.readTree(body);
        JsonNode news = root.path("news");
        List<NewsRow> out = new ArrayList<>();
        if (!news.isArray()) return out;
        // newsTags 為獨立陣列 [{newsId,name}]，先依 newsId 分組供個股過濾（Task 178）。
        Map<Long, List<String>> tagsById = new HashMap<>();
        for (JsonNode t : root.path("newsTags")) {
            long nid = t.path("newsId").asLong(0);
            String name = t.path("name").asText("").trim();
            if (nid > 0 && !name.isEmpty()) tagsById.computeIfAbsent(nid, k -> new ArrayList<>()).add(name);
        }
        for (JsonNode n : news) {
            long id = n.path("id").asLong(0);
            String title = n.path("headline").asText("").trim();
            long timeMs = n.path("time").asLong(0);   // epoch 毫秒
            if (id <= 0 || title.isEmpty() || timeMs <= 0) continue;
            String summary = n.path("summary").asText("").trim();
            out.add(new NewsRow(
                    title, "wantgoo",
                    "https://www.wantgoo.com/news/" + id,
                    "news", "TW",
                    summary.isEmpty() ? null : summary,
                    Instant.ofEpochMilli(timeMs),
                    tagsById.getOrDefault(id, List.of())));
        }
        return out;
    }

    // ===== MoneyDJ 理財網即時新聞（HTML）=====

    private List<NewsRow> fetchMoneydj() throws Exception {
        String html = get(MONEYDJ_URL);
        List<NewsRow> out = new ArrayList<>();
        Matcher m = MDJ_ROW.matcher(html);
        while (m.find()) {
            String dt = m.group(1);            // "07/07 16:03"（MM/DD HH:MM，無年份）
            String href = m.group(2);          // /kmdj/news/newsviewer.aspx?a=...&c=...
            String title = m.group(3).trim();  // title 屬性為完整標題
            if (title.isEmpty()) continue;
            Instant published = parseMoneydjDate(dt);
            if (published == null) continue;   // 無可信時間 → 略過
            String url = href.startsWith("http") ? href : "https://www.moneydj.com" + href;
            out.add(new NewsRow(title, "moneydj", url, "news", "TW", null, published));
        }
        return out;
    }

    /** MoneyDJ 列時間 "MM/DD HH:MM"（無年份，Asia/Taipei）→ Instant；跨年時往前推一年；失敗回 null。 */
    private static Instant parseMoneydjDate(String dt) {
        try {
            String[] p = dt.trim().split("[\\s/:]+");   // [MM, DD, HH, mm]
            if (p.length < 4) return null;
            int mm = Integer.parseInt(p[0]), dd = Integer.parseInt(p[1]),
                hh = Integer.parseInt(p[2]), mi = Integer.parseInt(p[3]);
            java.time.ZoneId tw = java.time.ZoneId.of("Asia/Taipei");
            java.time.LocalDate today = java.time.LocalDate.now(tw);
            java.time.LocalDateTime ldt = java.time.LocalDateTime.of(today.getYear(), mm, dd, hh, mi);
            if (ldt.toLocalDate().isAfter(today.plusDays(2))) ldt = ldt.minusYears(1);  // 12月看到隔年初→去年
            return ldt.atZone(tw).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    // ===== 自由時報 / 經濟日報（RSS）=====

    private List<NewsRow> fetchRss(String url, String source, String region) throws Exception {
        String xml = get(url);
        List<NewsRow> out = new ArrayList<>();
        Matcher im = ITEM.matcher(xml);
        while (im.find()) {
            String item = im.group(1);
            String title = firstGroupUnwrapped(TITLE, item);
            String link = firstGroupUnwrapped(LINK, item);
            String pub = firstGroupUnwrapped(PUBDATE, item);
            if (title == null || title.isBlank() || link == null || link.isBlank()) continue;
            Instant publishedAt = parsePubDate(pub);
            if (publishedAt == null) continue;   // 無可信發布日 → 略過（本地新聞主打日期精準）
            out.add(new NewsRow(title.trim(), source, link.trim(), "news", region, null, publishedAt));
        }
        return out;
    }

    /** 取第一個群組並剝掉 CDATA 與前後空白。 */
    private static String firstGroupUnwrapped(Pattern p, String s) {
        Matcher m = p.matcher(s);
        if (!m.find()) return null;
        String v = m.group(1).trim();
        Matcher c = CDATA.matcher(v);
        if (c.find()) v = c.group(1).trim();
        return v;
    }

    /** RSS pubDate（RFC-1123，如 "Tue, 07 Jul 2026 22:15:48 +0800"）→ Instant；解析失敗回 null。 */
    private static Instant parsePubDate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return OffsetDateTime.parse(raw.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    private String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "Mozilla/5.0")
                .header("Accept", "application/json, application/xml, text/xml, */*")
                .timeout(Duration.ofSeconds(15))
                .GET().build();
        HttpResponse<String> res = http.send(req,
                HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
        if (res.statusCode() / 100 != 2) {
            throw new RuntimeException("HTTP " + res.statusCode() + " @ " + url);
        }
        return res.body();
    }
}
