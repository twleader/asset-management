package com.steven.assets.service.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BlogContentRenderer} 單元測試（Requirement 102 / Task 366）。
 *
 * <p>本類別是唯一把交易雷達內部資料公開發布到外部部落格的路徑，測試重點放在三件事：
 * 白名單（鑑識欄位不得外洩）、正確欄位選用（{@code swingActionLabel} 而非
 * {@code swingAction}、{@code market} 而非 {@code usMarket}）、以及 XSS 兩層防護
 * （scheme 白名單 ＋ escape 缺一不可）。
 */
class BlogContentRendererTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 固定假快照：兩檔個股（一檔欄位齊全、一檔刻意缺值）、market／usMarket 並存、
     * publicInformation 四種 url 型態（合法 https／javascript:／空字串／合法但含雙引號的攻擊測資）。
     */
    private static final String FIXTURE = """
            {
              "market": {
                "regimeLabel": "多方",
                "score": 72,
                "asOfDate": "2026-08-21",
                "price": 23456.78,
                "changePercent": 1.23,
                "reasons": ["站上季線", "成交量放大"],
                "risks": []
              },
              "usMarket": {
                "regimeLabel": "US多方",
                "score": 99,
                "asOfDate": "2026-08-21",
                "price": 88888.88,
                "changePercent": 3.21,
                "reasons": ["納指創高"],
                "risks": ["估值偏高"]
              },
              "stocks": [
                {
                  "stockCode": "2330",
                  "stockName": "台積電<A&B>\\"test\\"",
                  "actionLabel": "加碼",
                  "price": 1000.5,
                  "changePercent": 2.5,
                  "held": true,
                  "shortActionLabel": "短線買進",
                  "shortScore": 80,
                  "shortReasons": ["短線動能強"],
                  "shortRisks": [],
                  "swingActionLabel": "減碼",
                  "swingAction": "SELL_INTERNAL_CODE",
                  "swingScore": 40,
                  "swingReasons": [],
                  "swingRisks": ["籌碼鬆動"],
                  "score": 60,
                  "reasons": ["長期基本面佳"],
                  "risks": [],
                  "evidence": {"secret": "不得外洩"},
                  "fundamental": {"peProvider": "不得外洩"},
                  "actionGateReasons": ["不得外洩"]
                },
                {
                  "stockCode": "2603",
                  "stockName": "長榮",
                  "held": false,
                  "price": 200,
                  "changePercent": -1.1
                }
              ],
              "publicInformation": [
                {"publishedAt": "2026-08-20", "source": "MoneyDJ", "title": "測試新聞&標題", "url": "https://example.com/a", "summary": "摘要1"},
                {"publishedAt": "2026-08-19", "source": "來源2", "title": "壞連結", "url": "javascript:alert(1)", "summary": "摘要2"},
                {"publishedAt": "2026-08-18", "source": "來源3", "title": "空網址", "url": "", "summary": "摘要3"},
                {"publishedAt": "2026-08-17", "source": "來源4", "title": "攻擊測資", "url": "https://evil.example/\\"><script>alert(1)</script>", "summary": "摘要4"}
              ]
            }
            """;

    private static String render() {
        try {
            JsonNode node = MAPPER.readTree(FIXTURE);
            return BlogContentRenderer.render(node);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void 輸出不含script標籤() {
        String html = render();
        assertThat(html.toLowerCase()).doesNotContain("<script>").doesNotContain("<script ");
    }

    @Test
    void 輸出不含鑑識或部署相關關鍵字() {
        String html = render();
        assertThat(html)
                .doesNotContain("EXPORT_OUTPUT_DIR")
                .doesNotContain("/home/steven")
                .doesNotContain("gdrive")
                .doesNotContain("Google Drive")
                .doesNotContain("不得外洩"); // evidence／fundamental／actionGateReasons 的測資值
    }

    @Test
    void details數量與個股數一致() {
        String html = render();
        int count = html.split("<details>", -1).length - 1;
        assertThat(count).isEqualTo(2);
    }

    @Test
    void reasons或risks缺值時不輸出空殼ul() {
        String html = render();
        assertThat(html).doesNotContain("<ul>\n</ul>");
    }

    @Test
    void html特殊字元被正確轉義() {
        String html = render();
        assertThat(html).contains("&lt;A&amp;B&gt;").contains("&quot;test&quot;");
        assertThat(html).doesNotContain("<A&B>").doesNotContain("\"test\"");
        assertThat(html).contains("測試新聞&amp;標題");
    }

    @Test
    void 一周1月軌讀swingActionLabel而非swingAction() {
        String html = render();
        assertThat(html).contains("減碼");
        assertThat(html).doesNotContain("SELL_INTERNAL_CODE");
    }

    @Test
    void 缺值swingActionLabel時fallback今日不交易() {
        String html = render();
        assertThat(html).contains("今日不交易");
    }

    @Test
    void 台股大盤與美股大盤並存時輸出不含usMarket數值() {
        String html = render();
        assertThat(html).contains("23456.78");     // 台股大盤 price
        assertThat(html).doesNotContain("88888.88"); // usMarket price 不得出現
        assertThat(html).doesNotContain("US多方");
        assertThat(html).doesNotContain("納指創高");
        assertThat(html).doesNotContain("估值偏高");
    }

    @Test
    void publicInformation僅合法scheme輸出aHref() {
        String html = render();
        assertThat(html).contains("<a href=\"https://example.com/a\">");
        assertThat(html).doesNotContain("javascript:");
    }

    @Test
    void 合法scheme但含雙引號的攻擊測資雙層防護皆生效() {
        String html = render();
        // href 屬性值中的雙引號已被轉義，不會提前結束屬性
        assertThat(html).contains("href=\"https://evil.example/&quot;&gt;&lt;script&gt;alert(1)&lt;/script&gt;\"");
        assertThat(html.toLowerCase()).doesNotContain("<script>");
    }

    @Test
    void 缺值的個股摘要顯示代碼與名稱不留空白summary() {
        String html = render();
        assertThat(html).contains("<summary>2603 長榮</summary>");
    }
}
