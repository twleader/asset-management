package com.steven.assets.service.export;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 交易雷達快照 → 精簡摘要版 HTML（Requirement 102 / Task 366）。
 *
 * <p><b>純函數、無狀態、不注入任何 repository</b>——方便單元測試傳固定假 {@link JsonNode}。輸入為
 * {@code TradingRadarSnapshotStore} 讀出的單筆快照（與 {@code TradingRadarExportService} 讀同一種
 * 結構），輸出純 HTML 字串，全程不得包含 {@code <script>} 標籤。
 *
 * <p><b>白名單組裝</b>：只讀取本類別明確列出的欄位名稱，不遍歷／轉存快照的其餘內容——
 * {@code evidence}／{@code fundamental}／{@code extendedIndicators}／{@code weeklyIndicators}／
 * {@code dailyCandle}／任何 {@code *Provider}／{@code *SourceUrl}／{@code *AsOf}／利率／treasury
 * 相關欄位、{@code actionGateReasons}、{@code counterTrend*} 等鑑識欄位一律不會出現在輸出中，
 * 因為程式碼裡根本沒有讀取它們的路徑。
 *
 * <p><b>只讀台股大盤</b>（{@code snapshot.path("market")}），<b>不得讀 {@code usMarket}</b>——
 * 比照既有 {@code TradingRadarExportService.marketSheet()}「大盤總覽」分頁本就只讀 {@code market}、
 * 從未輸出 {@code usMarket} 的既有範圍。
 */
public final class BlogContentRenderer {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter UPDATED_AT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String FALLBACK = "—";
    /** 1周~1月軌動作標籤缺值時的 fallback，比照既有 {@code TradingRadarView.vue:709} 既有寫法。 */
    private static final String SWING_FALLBACK = "今日不交易";

    private BlogContentRenderer() {}

    public static String render(JsonNode snapshot) {
        JsonNode root = snapshot == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : snapshot;
        StringBuilder sb = new StringBuilder();
        String updatedAt = LocalDateTime.now(TAIPEI).format(UPDATED_AT_FMT);
        sb.append("<p>最後更新：").append(escape(updatedAt)).append("</p>\n");
        sb.append("<p>本頁內容由系統規則自動產生，僅供個人投資紀錄公開，不構成任何投資建議。</p>\n");
        sb.append(renderMarket(root.path("market")));
        sb.append(renderStocks(root.path("stocks")));
        sb.append(renderPublicInformation(root.path("publicInformation")));
        return sb.toString();
    }

    // ===== 大盤卡 =====

    private static String renderMarket(JsonNode market) {
        StringBuilder sb = new StringBuilder();
        sb.append("<div>\n<p><strong>大盤</strong>：").append(textOr(market, "regimeLabel"))
                .append("｜分數：").append(textOr(market, "score"))
                .append("｜資料日：").append(textOr(market, "asOfDate"))
                .append("｜現價：").append(textOr(market, "price"))
                .append("｜漲跌%：").append(textOr(market, "changePercent"))
                .append("</p>\n");
        sb.append(renderList("支持訊號", market.path("reasons")));
        sb.append(renderList("風險提醒", market.path("risks")));
        sb.append("</div>\n");
        return sb.toString();
    }

    // ===== 個股決策 =====

    private static String renderStocks(JsonNode stocks) {
        if (stocks == null || !stocks.isArray()) return "";
        StringBuilder sb = new StringBuilder();
        for (JsonNode stock : stocks) {
            sb.append(renderStock(stock));
        }
        return sb.toString();
    }

    private static String renderStock(JsonNode stock) {
        StringBuilder sb = new StringBuilder();
        String stockCode = rawText(stock, "stockCode");
        String stockName = rawText(stock, "stockName");
        String actionLabel = rawText(stock, "actionLabel");
        String base = joinNonBlank(stockCode, stockName);
        String summaryText = (actionLabel != null && !actionLabel.isBlank())
                ? (base.isBlank() ? actionLabel : base + " — " + actionLabel)
                : base;
        if (summaryText.isBlank()) summaryText = FALLBACK;

        sb.append("<details>\n<summary>").append(escape(summaryText)).append("</summary>\n");

        boolean held = stock.path("held").asBoolean(false);
        sb.append("<p>現價：").append(textOr(stock, "price"))
                .append("｜漲跌%：").append(textOr(stock, "changePercent"))
                .append("｜是否持有：").append(held ? "是" : "否")
                .append("</p>\n");

        sb.append("<p><strong>一周軌</strong>：").append(textOr(stock, "shortActionLabel"))
                .append("｜分數：").append(textOr(stock, "shortScore")).append("</p>\n");
        sb.append(renderList("一周軌支持訊號", stock.path("shortReasons")));
        sb.append(renderList("一周軌風險提醒", stock.path("shortRisks")));

        // 1周~1月軌固定讀 swingActionLabel（既有中文標籤欄位），不得改讀內部代碼欄位 swingAction。
        String swingLabel = rawText(stock, "swingActionLabel");
        if (swingLabel == null || swingLabel.isBlank()) swingLabel = SWING_FALLBACK;
        sb.append("<p><strong>1周~1月軌</strong>：").append(escape(swingLabel))
                .append("｜分數：").append(textOr(stock, "swingScore")).append("</p>\n");
        sb.append(renderList("1周~1月軌支持訊號", stock.path("swingReasons")));
        sb.append(renderList("1周~1月軌風險提醒", stock.path("swingRisks")));

        sb.append("<p><strong>1月~6月軌</strong>：").append(textOr(stock, "actionLabel"))
                .append("｜分數：").append(textOr(stock, "score")).append("</p>\n");
        sb.append(renderList("1月~6月軌支持訊號", stock.path("reasons")));
        sb.append(renderList("1月~6月軌風險提醒", stock.path("risks")));

        sb.append("</details>\n");
        return sb.toString();
    }

    // ===== 台美公開資訊 =====

    private static String renderPublicInformation(JsonNode items) {
        if (items == null || !items.isArray() || items.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("<p><strong>台美公開資訊</strong></p>\n<ul>\n");
        for (JsonNode item : items) {
            String publishedAt = textOr(item, "publishedAt");
            String source = textOr(item, "source");
            String title = rawText(item, "title");
            String summary = textOr(item, "summary");
            String url = rawText(item, "url");

            String titleHtml;
            if (url != null && isHttpScheme(url)) {
                // 兩層防護缺一不可：scheme 白名單通過後，url 本身在填入 href 前仍要 escape，
                // 否則合法 https:// 開頭但內含雙引號的網址會提前結束 href 屬性造成注入。
                titleHtml = "<a href=\"" + escape(url) + "\">" + escape(title) + "</a>";
            } else {
                titleHtml = escape(title);
            }
            sb.append("<li>").append(publishedAt).append(" ｜ ").append(source).append(" ｜ ")
                    .append(titleHtml).append(" — ").append(summary).append("</li>\n");
        }
        sb.append("</ul>\n");
        return sb.toString();
    }

    private static boolean isHttpScheme(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    // ===== 共用小工具 =====

    /** 缺值不留空殼列表；有值時輸出 {@code <p>title：</p><ul>...</ul>}，每項各自 escape。 */
    private static String renderList(String title, JsonNode arr) {
        if (arr == null || !arr.isArray() || arr.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("<p>").append(escape(title)).append("：</p>\n<ul>\n");
        for (JsonNode item : arr) {
            String text = item.isTextual() ? item.asText() : (item.isNull() ? "" : item.asText());
            sb.append("<li>").append(escape(text)).append("</li>\n");
        }
        sb.append("</ul>\n");
        return sb.toString();
    }

    /** 缺值以「—」呈現的 escape 後文字（供直接插入內文）。 */
    private static String textOr(JsonNode node, String field) {
        String raw = rawText(node, field);
        return raw == null ? FALLBACK : escape(raw);
    }

    /** 原始（未 escape）文字；缺值／null 回 {@code null}，供呼叫端自行決定 fallback。 */
    private static String rawText(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        return v.asText();
    }

    private static String joinNonBlank(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(p);
            }
        }
        return sb.toString();
    }

    /**
     * HTML escape（{@code &}／{@code <}／{@code >}／{@code "}）。{@code null} 回空字串。
     *
     * <p>套用於<b>所有</b>從快照讀出、將寫進 HTML 的文字欄位——含 {@code url}（填入 {@code href}
     * 屬性前）與 {@code stockName}／{@code title}／{@code summary}／{@code source} 等
     * （填入標籤內文前），避免快照內容破壞 HTML 結構、造成屬性逃逸，或被 Blogger 當成標籤解讀。
     */
    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
