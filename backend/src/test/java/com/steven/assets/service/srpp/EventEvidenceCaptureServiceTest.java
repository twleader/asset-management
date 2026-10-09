package com.steven.assets.service.srpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.model.News;
import com.steven.assets.model.SrppEventEvidenceBundle;
import com.steven.assets.repository.NewsHeadlineRepository;
import com.steven.assets.repository.SrppEventEvidenceBundleRepository;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.srpp.SrppJcs;
import com.steven.assets.srpp.SrppPolicyRegistryService;
import com.steven.assets.srpp.SupportedPolicy;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Requirement 181／Task 481.13：{@link EventEvidenceCaptureService} 的 Mockito 單元測試（不啟動 Spring、不連資料庫）。
 *
 * <p>涵蓋 481.5 的每個 code、三種 source kind 的通過與失敗、volatile category 只驗存在、奈秒列被秒精度引用、
 * {@code conflicting}、類別重複／缺少／未知、{@code officialEvents} 的非法組合與降級、metadata／content 衝突、
 * 等價重送（缺省與明寫預設值）回 200、replay 不查 {@code news_headline}、失敗不呼叫 repository 的 {@code save*}，
 * 以及並行輸家只認具名 identity constraint 的 23505。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EventEvidenceCaptureServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ZoneId TW = ZoneId.of("Asia/Taipei");
    /** 2026-10-12（一）09:10 台北。 */
    private static final Instant NOW = Instant.parse("2026-10-12T01:10:00Z");
    private static final String TODAY = "2026-10-12";
    private static final String POLICY = "a".repeat(64);
    private static final String SWAGGER = "b".repeat(64);
    private static final long OWNER = 7L;
    private static final String RUBRIC = "3a780e748aa3dc8cc94053178c378069fc54b3c10a6d1a09bd32c9bc0628f378";

    // news_headline 測試列：一般新聞（published_at 帶奈秒）、volatile 匯率列、過期列
    private static final String NEWS_SOURCE = "ltn";
    private static final String NEWS_URL = "https://news.ltn.com.tw/news/world/breakingnews/100";
    private static final String NEWS_CATEGORY = "news";
    private static final String NEWS_KEY = EventEvidenceCaptureService.dedupeKey(NEWS_SOURCE, NEWS_URL, NEWS_CATEGORY);
    private static final String FX_SOURCE = "bot-fx";
    private static final String FX_URL = "https://rate.bot.com.tw/xrt?Lang=zh-TW";
    private static final String FX_KEY = EventEvidenceCaptureService.dedupeKey(FX_SOURCE, FX_URL, "fx");
    private static final String STALE_URL = "https://news.ltn.com.tw/news/world/breakingnews/99";
    private static final String STALE_KEY = EventEvidenceCaptureService.dedupeKey(NEWS_SOURCE, STALE_URL, NEWS_CATEGORY);
    private static final String EDGE_URL = "https://news.ltn.com.tw/news/world/breakingnews/98";
    private static final String EDGE_KEY = EventEvidenceCaptureService.dedupeKey(NEWS_SOURCE, EDGE_URL, NEWS_CATEGORY);

    @Mock CurrentUserContext currentUser;
    @Mock MarketDataService marketData;
    @Mock SrppPolicyRegistryService policies;
    @Mock PublishedSwaggerIdentity swagger;
    @Mock SrppEventEvidenceBundleRepository bundles;
    @Mock NewsHeadlineRepository news;
    @Mock PlatformTransactionManager transactionManager;

    private EventEvidenceCaptureService service;

    @BeforeEach
    void setUp() {
        service = new EventEvidenceCaptureService(currentUser, marketData, policies, swagger, bundles, news,
                EventEvidenceCaptureService.newTransactions(transactionManager), defaults(), Clock.fixed(NOW, TW));
        when(currentUser.hasUser()).thenReturn(true);
        when(currentUser.getEffectiveUserId()).thenReturn(OWNER);
        when(marketData.isTwTradingDayCachedOnly(LocalDate.parse(TODAY))).thenReturn(Optional.of(true));
        when(policies.find(POLICY)).thenReturn(Optional.of(new SupportedPolicy(POLICY, "ASSET_MGMT_SRPP_V2", "c".repeat(64),
                "d".repeat(64), null, null, new TreeMap<>(), Instant.parse("2026-10-01T00:00:00Z"))));
        when(swagger.sha256()).thenReturn(Optional.of(SWAGGER));
        when(bundles.findIdentity(anyLong(), any(), anyString(), anyString(), anyString(), anyString())).thenReturn(Optional.empty());
        when(bundles.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(news.findByDedupeKey(anyString())).thenReturn(Optional.empty());
        when(news.findByDedupeKey(NEWS_KEY)).thenReturn(Optional.of(row(NEWS_KEY, "臺灣　央行  宣布升息 ", NEWS_URL,
                NEWS_CATEGORY, Instant.parse("2026-10-11T13:30:24.123456789Z"), Instant.parse("2026-10-12T00:30:00Z"))));
        when(news.findByDedupeKey(FX_KEY)).thenReturn(Optional.of(row(FX_KEY, "美元兌台幣 32.105（09:05 更新）", FX_URL, "fx",
                Instant.parse("2026-10-12T01:05:00.987654Z"), Instant.parse("2026-10-12T01:05:01Z"))));
        when(news.findByDedupeKey(STALE_KEY)).thenReturn(Optional.of(row(STALE_KEY, "舊聞", STALE_URL, NEWS_CATEGORY,
                Instant.parse("2026-10-08T10:00:00Z"), Instant.parse("2026-10-08T15:59:59Z"))));
        when(news.findByDedupeKey(EDGE_KEY)).thenReturn(Optional.of(row(EDGE_KEY, "邊界", EDGE_URL, NEWS_CATEGORY,
                Instant.parse("2026-10-08T10:00:00Z"), Instant.parse("2026-10-08T16:00:00Z"))));
    }

    private static EventEvidenceCaptureService.Settings defaults() {
        return new EventEvidenceCaptureService.Settings(Set.of("fx", "us-market", "kr-market", "kr-intraday"),
                Set.of("/api/public/commodity-prices", "/api/public/market-index", "/api/public/exchange-rate/usd-twd"), 31,
                List.of("federalreserve.gov", "bls.gov", "bea.gov", "treasury.gov", "dgbas.gov.tw", "stat.gov.tw",
                        "cbc.gov.tw", "twse.com.tw", "tpex.org.tw"));
    }

    private static News row(String key, String title, String url, String category, Instant publishedAt, Instant fetchedAt) {
        return new News(1L, title, "src", url, category, "TW", "摘要", publishedAt, fetchedAt, key);
    }

    // ---------------------------------------------------------------- request 建構

    private static ObjectNode news(String source, String url, String category, String title, String publishedAt) {
        ObjectNode n = JSON.createObjectNode();
        n.put("kind", "NEWS_HEADLINE").put("source", source).put("url", url).put("category", category)
                .put("title", title).put("publishedAt", publishedAt);
        return n;
    }

    private static ObjectNode headline() {
        return news(NEWS_SOURCE, NEWS_URL, NEWS_CATEGORY, "台灣 央行 宣布升息", "2026-10-11T21:30:24+08:00");
    }

    private static ObjectNode fx() {
        return news(FX_SOURCE, FX_URL, "fx", "美元兌台幣 31.900（舊值）", "2026-10-10T09:00:00+08:00");
    }

    private static ObjectNode api(String endpoint, String observedDate) {
        ObjectNode n = JSON.createObjectNode();
        n.put("kind", "API_RESPONSE").put("endpoint", endpoint).put("observedDate", observedDate);
        return n;
    }

    private static ObjectNode api() {
        return api("/api/public/commodity-prices", "2026-10-09");
    }

    private static ObjectNode official(String url, String retrievedAt, String period) {
        ObjectNode n = JSON.createObjectNode();
        n.put("kind", "OFFICIAL_PAGE").put("url", url).put("retrievedAt", retrievedAt).put("period", period);
        return n;
    }

    private static ObjectNode official() {
        return official("https://www.federalreserve.gov/newsevents/pressreleases.htm", "2026-10-12T09:03:00+08:00", "2026-09");
    }

    private static ObjectNode category(String code, int score, ObjectNode... sources) {
        ObjectNode n = JSON.createObjectNode();
        n.put("code", code).put("claimedScore", score);
        ArrayNode array = n.putArray("sources");
        for (ObjectNode source : sources) array.add(source);
        return n;
    }

    /** fed=1（新聞）、geopolitics=2（API）、oil=0（官方頁）、taiwan_politics=0（無來源）、us_taiwan_inflation=0（volatile fx）、
     *  semiconductor=1（新聞＋API）→ 總分 4、已評 5、NORMAL。 */
    private static ObjectNode validBody() {
        ObjectNode n = JSON.createObjectNode();
        n.put("tradingDate", TODAY).put("slot", "09:05").put("consumer", "Claude")
                .put("decisionId", "CLAUDE-20261012-0905-091458").put("policyBundleSha256", POLICY).put("swaggerSha256", SWAGGER);
        ArrayNode categories = n.putArray("categories");
        categories.add(category("fed", 1, headline()));
        categories.add(category("geopolitics", 2, api()));
        categories.add(category("oil", 0, official()));
        categories.add(category("taiwan_politics", 0));
        categories.add(category("us_taiwan_inflation", 0, fx()));
        categories.add(category("semiconductor_cycle_and_advanced_process", 1, headline(), api()));
        return n;
    }

    private static ObjectNode categoryOf(ObjectNode body, String code) {
        for (JsonNode item : body.path("categories")) if (code.equals(item.path("code").asText())) return (ObjectNode) item;
        throw new IllegalArgumentException(code);
    }

    private static ObjectNode event(String symbol, String status, ObjectNode... sources) {
        ObjectNode n = JSON.createObjectNode();
        n.put("symbol", symbol).put("status", status);
        ArrayNode array = n.putArray("sources");
        for (ObjectNode source : sources) array.add(source);
        return n;
    }

    private EventEvidenceCaptureService.Result capture(JsonNode body) throws Exception {
        return service.capture(JSON.writeValueAsString(body));
    }

    private SrppCaptureProblem problem(String raw) {
        SrppCaptureProblem problem = catchThrowableOfType(SrppCaptureProblem.class, () -> service.capture(raw));
        assertThat(problem).as("應丟出 SrppCaptureProblem").isNotNull();
        return problem;
    }

    private SrppCaptureProblem problem(JsonNode body) throws Exception {
        return problem(JSON.writeValueAsString(body));
    }

    private void assertNoWrites() {
        verify(bundles, never()).saveAndFlush(any());
        verify(bundles, never()).save(any());
        verify(bundles, never()).saveAll(any());
        verify(bundles, never()).saveAllAndFlush(any());
    }

    private static List<String> errorKeys(SrppCaptureProblem problem) {
        List<String> out = new ArrayList<>();
        for (Map<String, Object> error : problem.errors) {
            String key = error.containsKey("riskCategory") ? (String) error.get("riskCategory") : "#" + error.get("symbol");
            out.add(key + "/" + error.getOrDefault("sourceIndex", "-") + "/" + error.get("code"));
        }
        return out;
    }

    // ---------------------------------------------------------------- 201 成功

    @Test
    void validRequestIsScoredPersistedAndReturnedAs201() throws Exception {
        EventEvidenceCaptureService.Result result = capture(validBody());

        assertThat(result.status()).isEqualTo(HttpStatus.CREATED);
        ArgumentCaptor<SrppEventEvidenceBundle> saved = ArgumentCaptor.forClass(SrppEventEvidenceBundle.class);
        verify(bundles).saveAndFlush(saved.capture());
        SrppEventEvidenceBundle row = saved.getValue();
        assertThat(row.getOwnerUserId()).isEqualTo(OWNER);
        assertThat(row.getTradingDate()).isEqualTo(LocalDate.parse(TODAY));
        assertThat(row.getSlot()).isEqualTo("09:05");
        assertThat(row.getAnalysisProfile()).isEqualTo("TW_DAILY");
        assertThat(row.getConsumer()).isEqualTo("Claude");
        assertThat(row.getDecisionId()).isEqualTo("CLAUDE-20261012-0905-091458");
        assertThat(row.getPolicyBundleSha256()).isEqualTo(POLICY);
        assertThat(row.getSwaggerSha256()).isEqualTo(SWAGGER);
        assertThat(row.getCreatedAt()).isEqualTo(NOW);
        assertThat(row.isNew()).isTrue();

        JsonNode body = JSON.readTree(result.body());
        assertThat(result.body()).isEqualTo(SrppJcs.canonicalize(body));
        assertThat(body.path("schemaVersion").intValue()).isEqualTo(1);
        assertThat(body.path("eventBundleId").asText()).isEqualTo(row.getId().toString()).isEqualTo(result.eventBundleId());
        assertThat(body.path("status").asText()).isEqualTo("FINAL");
        assertThat(body.path("created").booleanValue()).isTrue();
        assertThat(body.path("idempotentReplay").booleanValue()).isFalse();
        assertThat(body.path("tradeAuthorization").isBoolean() && !body.path("tradeAuthorization").booleanValue()).isTrue();
        assertThat(body.path("placesOrders").isBoolean() && !body.path("placesOrders").booleanValue()).isTrue();
        assertThat(body.path("rubricSha256").asText()).isEqualTo(RUBRIC);
        assertThat(body.path("identity").toString()).isEqualTo("{\"analysisProfile\":\"TW_DAILY\",\"consumer\":\"Claude\","
                + "\"decisionId\":\"CLAUDE-20261012-0905-091458\",\"policyBundleSha256\":\"" + POLICY + "\",\"slot\":\"09:05\","
                + "\"swaggerSha256\":\"" + SWAGGER + "\",\"tradingDate\":\"2026-10-12\"}");

        JsonNode risk = body.path("riskAssessment");
        assertThat(risk.path("totalScore").isInt() && risk.path("totalScore").intValue() == 4).isTrue();
        assertThat(risk.path("assessedCount").intValue()).isEqualTo(5);
        assertThat(risk.path("riskMode").asText()).isEqualTo("NORMAL");
        List<String> codes = new ArrayList<>();
        risk.path("categories").forEach(c -> codes.add(c.path("code").asText() + ":" + c.path("score").intValue() + ":"
                + c.path("assessed").booleanValue() + ":" + c.path("evidenceStatus").asText()));
        assertThat(codes).containsExactly(
                "fed:1:true:RUBRIC_EVENT_FOUND",
                "geopolitics:2:true:RUBRIC_EVENT_FOUND",
                "oil:0:true:VERIFIED_NO_RUBRIC_EVENT",
                "taiwan_politics:0:false:EVIDENCE_INSUFFICIENT",
                "us_taiwan_inflation:0:true:VERIFIED_NO_RUBRIC_EVENT",
                "semiconductor_cycle_and_advanced_process:1:true:RUBRIC_EVENT_FOUND");
        JsonNode categories = risk.path("categories");
        // 奈秒列被秒精度字串引用：通過，period 是資料庫列截斷到秒的 Asia/Taipei 字串
        assertThat(categories.get(0).path("sourceReceipts").toString()).isEqualTo("[{\"dedupeKey\":\"" + NEWS_KEY
                + "\",\"kind\":\"NEWS_HEADLINE\",\"period\":\"2026-10-11T21:30:24+08:00\",\"provenance\":\"DB_VERIFIED\"}]");
        assertThat(categories.get(1).path("sourceReceipts").toString()).isEqualTo("[{\"endpoint\":\"/api/public/commodity-prices\","
                + "\"kind\":\"API_RESPONSE\",\"period\":\"2026-10-09\",\"provenance\":\"ATTESTED\"}]");
        assertThat(categories.get(2).path("sourceReceipts").toString()).isEqualTo("[{\"host\":\"www.federalreserve.gov\","
                + "\"kind\":\"OFFICIAL_PAGE\",\"period\":\"2026-09\",\"provenance\":\"ATTESTED\"}]");
        assertThat(categories.get(3).path("sourceReceipts")).isEmpty();
        // volatile：period 取資料庫列（本輪就地覆寫後）的 published_at
        assertThat(categories.get(4).path("sourceReceipts").toString()).isEqualTo("[{\"dedupeKey\":\"" + FX_KEY
                + "\",\"kind\":\"NEWS_HEADLINE\",\"period\":\"2026-10-12T09:05:00+08:00\",\"provenance\":\"DB_VERIFIED\"}]");
        assertThat(categories.get(5).path("sourceReceipts").size()).isEqualTo(2);
        assertThat(body.path("officialEvents")).isEmpty();

        // content_jcs：不含 created／idempotentReplay，含 eventBundleContentSha256（= 去掉三欄後的 JCS SHA-256）
        ObjectNode content = (ObjectNode) SrppJcs.parseStrict(row.getContentJcs());
        assertThat(content.has("created") || content.has("idempotentReplay")).isFalse();
        assertThat(row.getContentJcs()).isEqualTo(SrppJcs.canonicalize(content));
        ObjectNode unsigned = content.deepCopy();
        unsigned.remove("eventBundleContentSha256");
        assertThat(content.path("eventBundleContentSha256").asText()).isEqualTo(SrppJcs.hash(unsigned))
                .isEqualTo(body.path("eventBundleContentSha256").asText()).isEqualTo(result.eventBundleContentSha256());
        ObjectNode withoutFlags = ((ObjectNode) body).deepCopy();
        withoutFlags.remove(List.of("created", "idempotentReplay"));
        assertThat(SrppJcs.canonicalize(withoutFlags)).isEqualTo(row.getContentJcs());
        // 不保存 request 原文或爬蟲內文
        assertThat(row.getContentJcs()).doesNotContain("央行").doesNotContain(NEWS_URL).doesNotContain("摘要");
        assertThat(row.getRequestSha256()).matches("[0-9a-f]{64}");
    }

    @Test
    void captureIsNotTransactionalOnTheOuterMethod() throws Exception {
        assertThat(EventEvidenceCaptureService.class.getMethod("capture", String.class).isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(EventEvidenceCaptureService.class.isAnnotationPresent(Transactional.class)).isFalse();
    }

    // ---------------------------------------------------------------- 400：結構（owner 之前、零 IO）

    static Stream<Arguments> structuralInvalidBodies() {
        Stream.Builder<Arguments> out = Stream.builder();
        Consumer<String> raw = text -> out.add(Arguments.of("raw " + text, (Consumer<ObjectNode>) null, text));
        for (String text : new String[] {"not json", "[]", "null", "\"x\"", "1", "{", "{} {}",
                "{\"tradingDate\":\"2026-10-12\",\"tradingDate\":\"2026-10-12\"}"}) raw.accept(text);
        add(out, "未知欄位 score", n -> n.put("score", 1));
        add(out, "未知欄位 riskMode", n -> n.put("riskMode", "NORMAL"));
        add(out, "未知欄位 tradeAuthorization", n -> n.put("tradeAuthorization", false));
        add(out, "未知欄位 refresh", n -> n.put("refresh", true));
        add(out, "category 帶 score", n -> categoryOf(n, "fed").put("score", 1));
        add(out, "category 帶 assessed", n -> categoryOf(n, "fed").put("assessed", true));
        add(out, "category 帶 evidenceStatus", n -> categoryOf(n, "fed").put("evidenceStatus", "RUBRIC_EVENT_FOUND"));
        add(out, "source 帶未知欄位", n -> ((ObjectNode) categoryOf(n, "fed").path("sources").get(0)).put("summary", "x"));
        add(out, "缺 tradingDate", n -> n.remove("tradingDate"));
        add(out, "缺 slot", n -> n.remove("slot"));
        add(out, "缺 consumer", n -> n.remove("consumer"));
        add(out, "缺 decisionId", n -> n.remove("decisionId"));
        add(out, "缺 policyBundleSha256", n -> n.remove("policyBundleSha256"));
        add(out, "缺 swaggerSha256", n -> n.remove("swaggerSha256"));
        add(out, "缺 categories", n -> n.remove("categories"));
        add(out, "categories 不是陣列", n -> n.put("categories", "x"));
        add(out, "categories 超過 12 項", n -> {
            ArrayNode categories = (ArrayNode) n.path("categories");
            for (int i = 0; i < 7; i++) categories.add(category("fed", 0));
        });
        add(out, "category 缺 code", n -> categoryOf(n, "oil").remove("code"));
        add(out, "category code 超過 64 字元", n -> ((ObjectNode) n.path("categories").get(0)).put("code", "x".repeat(65)));
        add(out, "category 缺 claimedScore", n -> categoryOf(n, "oil").remove("claimedScore"));
        add(out, "claimedScore 是字串", n -> categoryOf(n, "oil").put("claimedScore", "1"));
        add(out, "claimedScore 是小數", n -> categoryOf(n, "oil").put("claimedScore", 1.5));
        add(out, "claimedScore 是 1.0", n -> categoryOf(n, "oil").put("claimedScore", 1.0));
        add(out, "claimedScore 超出 int", n -> categoryOf(n, "oil").put("claimedScore", 2_147_483_648L));
        add(out, "conflicting 不是布林", n -> categoryOf(n, "oil").put("conflicting", "false"));
        add(out, "category 缺 sources", n -> categoryOf(n, "oil").remove("sources"));
        add(out, "sources 超過 20 筆", n -> {
            ArrayNode sources = (ArrayNode) categoryOf(n, "oil").path("sources");
            for (int i = 0; i < 20; i++) sources.add(official());
        });
        add(out, "kind 不合法", n -> ((ObjectNode) categoryOf(n, "oil").path("sources").get(0)).put("kind", "RSS"));
        add(out, "NEWS 缺 title", n -> ((ObjectNode) categoryOf(n, "fed").path("sources").get(0)).remove("title"));
        add(out, "NEWS title 空白", n -> ((ObjectNode) categoryOf(n, "fed").path("sources").get(0)).put("title", "  "));
        add(out, "NEWS title 501 字", n -> ((ObjectNode) categoryOf(n, "fed").path("sources").get(0)).put("title", "標".repeat(501)));
        add(out, "NEWS source 101 字", n -> ((ObjectNode) categoryOf(n, "fed").path("sources").get(0)).put("source", "s".repeat(101)));
        add(out, "NEWS url 1025 字", n -> ((ObjectNode) categoryOf(n, "fed").path("sources").get(0)).put("url", "u".repeat(1025)));
        add(out, "NEWS category 33 字", n -> ((ObjectNode) categoryOf(n, "fed").path("sources").get(0)).put("category", "c".repeat(33)));
        add(out, "NEWS publishedAt 不是字串", n -> ((ObjectNode) categoryOf(n, "fed").path("sources").get(0)).put("publishedAt", 1));
        add(out, "API endpoint 201 字", n -> ((ObjectNode) categoryOf(n, "geopolitics").path("sources").get(0)).put("endpoint", "/".repeat(201)));
        add(out, "API 缺 observedDate", n -> ((ObjectNode) categoryOf(n, "geopolitics").path("sources").get(0)).remove("observedDate"));
        add(out, "OFFICIAL 缺 period", n -> ((ObjectNode) categoryOf(n, "oil").path("sources").get(0)).remove("period"));
        add(out, "ownerEmail 不是字串", n -> n.put("ownerEmail", 1));
        add(out, "ownerEmail 為 null", n -> n.putNull("ownerEmail"));
        add(out, "ownerEmail 空白", n -> n.put("ownerEmail", "  "));
        add(out, "ownerEmail 格式錯誤", n -> n.put("ownerEmail", "not-an-email"));
        add(out, "ownerEmail 超過 254 字元", n -> n.put("ownerEmail", "a".repeat(250) + "@x.com"));
        add(out, "tradingDate 格式錯誤", n -> n.put("tradingDate", "2026/10/12"));
        add(out, "tradingDate 不存在的日期", n -> n.put("tradingDate", "2026-02-30"));
        add(out, "slot 不合法", n -> n.put("slot", "13:30"));
        add(out, "analysisProfile 不是 TW_DAILY", n -> n.put("analysisProfile", "TW_WEEKLY"));
        add(out, "consumer 不合法", n -> n.put("consumer", "Gemini"));
        add(out, "consumer 大小寫不符", n -> n.put("consumer", "claude"));
        add(out, "decisionId 不合法", n -> n.put("decisionId", "-bad"));
        add(out, "decisionId 超過 100 字元", n -> n.put("decisionId", "A".repeat(101)));
        add(out, "policy hash 大寫", n -> n.put("policyBundleSha256", "A".repeat(64)));
        add(out, "swagger hash 63 碼", n -> n.put("swaggerSha256", "b".repeat(63)));
        add(out, "officialEvents 不是陣列", n -> n.put("officialEvents", "x"));
        add(out, "officialEvents 超過 100 筆", n -> {
            ArrayNode events = n.putArray("officialEvents");
            for (int i = 0; i < 101; i++) events.add(event(String.format("%04d", i), "VERIFIED_NO_EVENT"));
        });
        add(out, "officialEvents symbol 不合法", n -> n.putArray("officialEvents").add(event("00a", "VERIFIED_NO_EVENT")));
        add(out, "officialEvents status 不合法", n -> n.putArray("officialEvents").add(event("0050", "UNKNOWN")));
        add(out, "officialEvents 缺 sources", n -> n.putArray("officialEvents").add(event("0050", "VERIFIED_NO_EVENT").without("sources")));
        add(out, "officialEvents sources 超過 3 筆", n -> n.putArray("officialEvents").add(event("0050", "EVENT_FOUND",
                official(), official(), official(), official())));
        add(out, "VERIFIED_NO_EVENT 帶來源", n -> n.putArray("officialEvents").add(event("0050", "VERIFIED_NO_EVENT", official())));
        add(out, "SOURCE_UNAVAILABLE 帶來源", n -> n.putArray("officialEvents").add(event("0050", "SOURCE_UNAVAILABLE", official())));
        add(out, "EVENT_FOUND 帶 NEWS_HEADLINE", n -> n.putArray("officialEvents").add(event("0050", "EVENT_FOUND", headline())));
        add(out, "EVENT_FOUND 帶 API_RESPONSE", n -> n.putArray("officialEvents").add(event("0050", "EVENT_FOUND", api())));
        add(out, "officialEvents 帶未知欄位", n -> n.putArray("officialEvents").add(event("0050", "VERIFIED_NO_EVENT").put("note", "x")));
        return out.build();
    }

    private static void add(Stream.Builder<Arguments> out, String label, Consumer<ObjectNode> mutation) {
        out.add(Arguments.of(label, mutation, null));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("structuralInvalidBodies")
    void structuralErrorsAre400BeforeOwnerAndWithoutAnyIo(String label, Consumer<ObjectNode> mutation, String raw) throws Exception {
        String text = raw;
        if (text == null) {
            ObjectNode body = validBody();
            mutation.accept(body);
            text = JSON.writeValueAsString(body);
        }

        SrppCaptureProblem problem = problem(text);

        assertThat(problem.status).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(problem.code).isEqualTo("INVALID_REQUEST");
        verifyNoInteractions(currentUser, marketData, policies, swagger, bundles, news, transactionManager);
    }

    @Test
    void nullBodyIs400() {
        SrppCaptureProblem problem = problem((String) null);
        assertThat(problem.code).isEqualTo("INVALID_REQUEST");
        verifyNoInteractions(currentUser, bundles, news);
    }

    @Test
    void validOwnerEmailIsAcceptedButNeverUsedAsOwnerIdentity() throws Exception {
        ObjectNode body = validBody();
        body.put("ownerEmail", "Selected.User+srpp@Example.Invalid");

        EventEvidenceCaptureService.Result result = capture(body);

        assertThat(result.status()).isEqualTo(HttpStatus.CREATED);
        ArgumentCaptor<SrppEventEvidenceBundle> saved = ArgumentCaptor.forClass(SrppEventEvidenceBundle.class);
        verify(bundles).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getOwnerUserId()).isEqualTo(OWNER);
        assertThat(saved.getValue().getContentJcs()).doesNotContain("Example.Invalid");
    }

    // ---------------------------------------------------------------- 503／400／409：owner、日期、日曆、規則包、Swagger

    @Test
    void missingOwnerContextIs503OwnerUnavailableBeforeDateCheck() throws Exception {
        when(currentUser.hasUser()).thenReturn(false);
        ObjectNode body = validBody();
        body.put("tradingDate", "2026-10-11");

        SrppCaptureProblem problem = problem(body);

        assertThat(problem.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(problem.code).isEqualTo("OWNER_UNAVAILABLE");
        verifyNoInteractions(marketData, policies, swagger, bundles, news);
    }

    @ParameterizedTest(name = "tradingDate={0} 不是台北今天 → 400")
    @ValueSource(strings = {"2026-10-11", "2026-10-13"})
    void tradingDateMustBeTaipeiToday(String date) throws Exception {
        ObjectNode body = validBody();
        body.put("tradingDate", date);

        SrppCaptureProblem problem = problem(body);

        assertThat(problem.code).isEqualTo("INVALID_REQUEST");
        verifyNoInteractions(marketData, policies, bundles, news);
    }

    @Test
    void unknownCalendarIs503() throws Exception {
        when(marketData.isTwTradingDayCachedOnly(LocalDate.parse(TODAY))).thenReturn(Optional.empty());

        SrppCaptureProblem problem = problem(validBody());

        assertThat(problem.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(problem.code).isEqualTo("CALENDAR_UNAVAILABLE");
        assertThat(problem.retryable).isTrue();
        verifyNoInteractions(policies, bundles, news);
    }

    @Test
    void nonTradingDayIs409() throws Exception {
        when(marketData.isTwTradingDayCachedOnly(LocalDate.parse(TODAY))).thenReturn(Optional.of(false));

        SrppCaptureProblem problem = problem(validBody());

        assertThat(problem.status).isEqualTo(HttpStatus.CONFLICT);
        assertThat(problem.code).isEqualTo("NON_TRADING_DAY");
        verifyNoInteractions(policies, bundles, news);
    }

    @Test
    void unregisteredPolicyIs409PolicyUnsupported() throws Exception {
        when(policies.find(POLICY)).thenReturn(Optional.empty());

        SrppCaptureProblem problem = problem(validBody());

        assertThat(problem.status).isEqualTo(HttpStatus.CONFLICT);
        assertThat(problem.code).isEqualTo("POLICY_UNSUPPORTED");
        verifyNoInteractions(swagger, bundles, news);
    }

    @Test
    void missingPublishedSwaggerIdentityFailsClosedWith503() throws Exception {
        when(swagger.sha256()).thenReturn(Optional.empty());

        SrppCaptureProblem problem = problem(validBody());

        assertThat(problem.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(problem.code).isEqualTo("CONTEXT_NOT_READY");
        verifyNoInteractions(bundles, news);
    }

    @Test
    void swaggerHashMismatchIs409() throws Exception {
        when(swagger.sha256()).thenReturn(Optional.of("e".repeat(64)));

        SrppCaptureProblem problem = problem(validBody());

        assertThat(problem.status).isEqualTo(HttpStatus.CONFLICT);
        assertThat(problem.code).isEqualTo("SWAGGER_MISMATCH");
        verifyNoInteractions(bundles, news);
    }

    // ---------------------------------------------------------------- 識別：replay、409、等價重送

    /** 先以 validBody 成功 capture 一次，取得 insert 的列，之後識別查詢都回這列。 */
    private SrppEventEvidenceBundle persistedFirst(ObjectNode first) throws Exception {
        capture(first);
        ArgumentCaptor<SrppEventEvidenceBundle> saved = ArgumentCaptor.forClass(SrppEventEvidenceBundle.class);
        verify(bundles).saveAndFlush(saved.capture());
        SrppEventEvidenceBundle row = saved.getValue();
        when(bundles.findIdentity(anyLong(), any(), anyString(), anyString(), anyString(), anyString())).thenReturn(Optional.of(row));
        org.mockito.Mockito.clearInvocations(bundles, news);
        return row;
    }

    @Test
    void sameRequestIsReplayedAs200WithoutQueryingNewsHeadline() throws Exception {
        ObjectNode body = validBody();
        EventEvidenceCaptureService.Result first = capture(body);
        ArgumentCaptor<SrppEventEvidenceBundle> saved = ArgumentCaptor.forClass(SrppEventEvidenceBundle.class);
        verify(bundles).saveAndFlush(saved.capture());
        when(bundles.findIdentity(anyLong(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.of(saved.getValue()));
        org.mockito.Mockito.clearInvocations(bundles, news);
        // 來源列之後被清除也不影響 replay
        when(news.findByDedupeKey(anyString())).thenReturn(Optional.empty());

        EventEvidenceCaptureService.Result replay = capture(body);

        assertThat(replay.status()).isEqualTo(HttpStatus.OK);
        ObjectNode firstBody = (ObjectNode) JSON.readTree(first.body());
        ObjectNode replayBody = (ObjectNode) JSON.readTree(replay.body());
        assertThat(replayBody.path("created").booleanValue()).isFalse();
        assertThat(replayBody.path("idempotentReplay").booleanValue()).isTrue();
        firstBody.remove(List.of("created", "idempotentReplay"));
        replayBody.remove(List.of("created", "idempotentReplay"));
        assertThat(replayBody).isEqualTo(firstBody);
        assertThat(replay.eventBundleContentSha256()).isEqualTo(first.eventBundleContentSha256());
        verifyNoInteractions(news);
        assertNoWrites();
    }

    @Test
    void equivalentResendWithExplicitDefaultsIsReplayedAs200() throws Exception {
        persistedFirst(validBody());
        ObjectNode explicit = validBody();
        explicit.put("ownerEmail", "selected@example.invalid");
        explicit.put("analysisProfile", "TW_DAILY");
        explicit.putArray("officialEvents");
        explicit.path("categories").forEach(category -> ((ObjectNode) category).put("conflicting", false));

        EventEvidenceCaptureService.Result replay = capture(explicit);

        assertThat(replay.status()).isEqualTo(HttpStatus.OK);
        assertNoWrites();
        verifyNoInteractions(news);
    }

    @Test
    void differentPolicyOrSwaggerForSameIdentityIs409MetadataMismatch() throws Exception {
        SrppEventEvidenceBundle row = persistedFirst(validBody());
        String otherPolicy = "f".repeat(64);
        when(policies.find(otherPolicy)).thenReturn(Optional.of(new SupportedPolicy(otherPolicy, "ASSET_MGMT_SRPP_V2",
                "c".repeat(64), "d".repeat(64), null, null, new TreeMap<>(), Instant.parse("2026-10-01T00:00:00Z"))));
        ObjectNode body = validBody();
        body.put("policyBundleSha256", otherPolicy);

        SrppCaptureProblem policy = problem(body);

        assertThat(policy.status).isEqualTo(HttpStatus.CONFLICT);
        assertThat(policy.code).isEqualTo("BUNDLE_METADATA_MISMATCH");

        // 已發布 Swagger 換版後，以新雜湊重送同識別
        String newSwagger = "e".repeat(64);
        when(swagger.sha256()).thenReturn(Optional.of(newSwagger));
        ObjectNode swaggerBody = validBody();
        swaggerBody.put("swaggerSha256", newSwagger);
        SrppCaptureProblem swaggerProblem = problem(swaggerBody);
        assertThat(swaggerProblem.code).isEqualTo("BUNDLE_METADATA_MISMATCH");
        assertThat(row.getSwaggerSha256()).isEqualTo(SWAGGER);
        assertNoWrites();
        verifyNoInteractions(news);
    }

    @Test
    void differentContentForSameIdentityIs409ContentConflict() throws Exception {
        persistedFirst(validBody());
        ObjectNode body = validBody();
        categoryOf(body, "fed").put("claimedScore", 2);

        SrppCaptureProblem problem = problem(body);

        assertThat(problem.status).isEqualTo(HttpStatus.CONFLICT);
        assertThat(problem.code).isEqualTo("BUNDLE_CONTENT_CONFLICT");
        assertNoWrites();
        verifyNoInteractions(news);
    }

    @Test
    void tamperedStoredReceiptIs502UpstreamInvalid() throws Exception {
        SrppEventEvidenceBundle row = persistedFirst(validBody());
        String tampered = row.getContentJcs().replace("\"riskMode\":\"NORMAL\"", "\"riskMode\":\"CAUTIOUS\"");
        SrppEventEvidenceBundle bad = new SrppEventEvidenceBundle(row.getId(), row.getOwnerUserId(), row.getTradingDate(),
                row.getSlot(), row.getAnalysisProfile(), row.getConsumer(), row.getDecisionId(), row.getPolicyBundleSha256(),
                row.getSwaggerSha256(), row.getRequestSha256(), tampered, row.getCreatedAt());
        when(bundles.findIdentity(anyLong(), any(), anyString(), anyString(), anyString(), anyString())).thenReturn(Optional.of(bad));

        SrppCaptureProblem problem = problem(validBody());

        assertThat(problem.status).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(problem.code).isEqualTo("UPSTREAM_INVALID");
    }

    @Test
    void differentConsumerOrDecisionIdIsAnIndependentIdentity() throws Exception {
        capture(validBody());
        ObjectNode codex = validBody();
        codex.put("consumer", "Codex");
        ObjectNode rerun = validBody();
        rerun.put("decisionId", "CLAUDE-20261012-0905-R2");

        assertThat(capture(codex).status()).isEqualTo(HttpStatus.CREATED);
        assertThat(capture(rerun).status()).isEqualTo(HttpStatus.CREATED);
        verify(bundles, times(3)).saveAndFlush(any());
        verify(bundles).findIdentity(OWNER, LocalDate.parse(TODAY), "09:05", "TW_DAILY", "Codex", "CLAUDE-20261012-0905-091458");
        verify(bundles).findIdentity(OWNER, LocalDate.parse(TODAY), "09:05", "TW_DAILY", "Claude", "CLAUDE-20261012-0905-R2");
    }

    // ---------------------------------------------------------------- 422 EVIDENCE_REJECTED

    private SrppCaptureProblem rejected(ObjectNode body) throws Exception {
        SrppCaptureProblem problem = problem(body);
        assertThat(problem.status).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(problem.code).isEqualTo("EVIDENCE_REJECTED");
        assertThat(problem.retryable).isFalse();
        assertNoWrites();
        return problem;
    }

    @Test
    void unknownMissingAndDuplicateCategoriesAreCollectedTogether() throws Exception {
        ObjectNode body = validBody();
        ArrayNode categories = (ArrayNode) body.path("categories");
        categories.remove(3);                                     // 缺 taiwan_politics
        categories.add(category("rates", 0));                     // 未知
        categories.add(category("fed", 0, headline()));           // 重複

        SrppCaptureProblem problem = rejected(body);

        assertThat(errorKeys(problem)).containsExactly(
                "fed/-/DUPLICATE_CATEGORY",
                "rates/-/UNKNOWN_CATEGORY",
                "taiwan_politics/-/MISSING_CATEGORY");
        assertThat(problem.truncated).isFalse();
    }

    @Test
    void emptyCategoriesReportsAllSixMissing() throws Exception {
        ObjectNode body = validBody();
        body.putArray("categories");

        assertThat(errorKeys(rejected(body))).containsExactly(
                "fed/-/MISSING_CATEGORY", "geopolitics/-/MISSING_CATEGORY", "oil/-/MISSING_CATEGORY",
                "semiconductor_cycle_and_advanced_process/-/MISSING_CATEGORY", "taiwan_politics/-/MISSING_CATEGORY",
                "us_taiwan_inflation/-/MISSING_CATEGORY");
    }

    @Test
    void scoreOutsideCategoryMaximumIs422() throws Exception {
        ObjectNode body = validBody();
        categoryOf(body, "fed").put("claimedScore", 3);           // fed 上限 2
        categoryOf(body, "oil").put("claimedScore", -1);

        assertThat(errorKeys(rejected(body))).containsExactly(
                "fed/-/SCORE_OUT_OF_RANGE",
                "oil/-/SCORE_OUT_OF_RANGE");
    }

    @Test
    void scoreWithoutEvidenceWhenNoVerifiedSourceOrConflicting() throws Exception {
        ObjectNode body = validBody();
        categoryOf(body, "taiwan_politics").put("claimedScore", 1);                  // 沒有來源
        categoryOf(body, "geopolitics").put("conflicting", true);                    // 來源矛盾仍給分

        assertThat(errorKeys(rejected(body))).containsExactly(
                "geopolitics/-/SCORE_WITHOUT_EVIDENCE",
                "taiwan_politics/-/SCORE_WITHOUT_EVIDENCE");
    }

    @Test
    void conflictingWithZeroScoreIsAcceptedAsEvidenceInsufficient() throws Exception {
        ObjectNode body = validBody();
        categoryOf(body, "oil").put("conflicting", true);

        JsonNode categories = JSON.readTree(capture(body).body()).path("riskAssessment").path("categories");

        assertThat(categories.get(2).path("evidenceStatus").asText()).isEqualTo("EVIDENCE_INSUFFICIENT");
        assertThat(categories.get(2).path("assessed").booleanValue()).isFalse();
        assertThat(categories.get(2).path("sourceReceipts").size()).isEqualTo(1);
    }

    static Stream<Arguments> newsHeadlineFailures() {
        return Stream.of(
                Arguments.of("dedupe_key 查無", news(NEWS_SOURCE, NEWS_URL + "?x", NEWS_CATEGORY, "台灣 央行 宣布升息",
                        "2026-10-11T21:30:24+08:00"), List.of("CITATION_NOT_FOUND")),
                Arguments.of("title 不同", news(NEWS_SOURCE, NEWS_URL, NEWS_CATEGORY, "台灣 央行 宣布降息",
                        "2026-10-11T21:30:24+08:00"), List.of("CITATION_NOT_FOUND")),
                Arguments.of("publishedAt 差一秒", news(NEWS_SOURCE, NEWS_URL, NEWS_CATEGORY, "台灣 央行 宣布升息",
                        "2026-10-11T21:30:25+08:00"), List.of("CITATION_NOT_FOUND")),
                Arguments.of("publishedAt 無 offset", news(NEWS_SOURCE, NEWS_URL, NEWS_CATEGORY, "台灣 央行 宣布升息",
                        "2026-10-11T21:30:24"), List.of("CITATION_NOT_FOUND")),
                Arguments.of("publishedAt 無法解析", news(NEWS_SOURCE, NEWS_URL, NEWS_CATEGORY, "台灣 央行 宣布升息",
                        "昨天"), List.of("CITATION_NOT_FOUND")),
                Arguments.of("fetched_at 早於交易日往前 3 日 00:00", news(NEWS_SOURCE, STALE_URL, NEWS_CATEGORY, "舊聞",
                        "2026-10-08T18:00:00+08:00"), List.of("SNAPSHOT_STALE")),
                Arguments.of("過期且 title 不同", news(NEWS_SOURCE, STALE_URL, NEWS_CATEGORY, "另一則",
                        "2026-10-08T18:00:00+08:00"), List.of("CITATION_NOT_FOUND", "SNAPSHOT_STALE")),
                Arguments.of("volatile category 也要驗存在", news(FX_SOURCE, FX_URL + "&x", "fx", "美元",
                        "2026-10-12T09:00:00+08:00"), List.of("CITATION_NOT_FOUND")),
                Arguments.of("非 volatile 的同一 URL 換 category 即查無", news(FX_SOURCE, FX_URL, NEWS_CATEGORY, "美元",
                        "2026-10-12T09:00:00+08:00"), List.of("CITATION_NOT_FOUND")));
    }

    @ParameterizedTest(name = "NEWS_HEADLINE 失敗：{0}")
    @MethodSource("newsHeadlineFailures")
    void newsHeadlineFailuresAreCollectedPerSource(String label, ObjectNode source, List<String> codes) throws Exception {
        ObjectNode body = validBody();
        ((ArrayNode) categoryOf(body, "taiwan_politics").path("sources")).add(api()).add(source);

        List<String> expected = codes.stream().map(code -> "taiwan_politics/1/" + code).toList();
        assertThat(errorKeys(rejected(body))).containsExactlyElementsOf(expected);
    }

    @Test
    void freshnessFloorIsInclusiveAtThreeCalendarDaysBeforeMidnight() throws Exception {
        ObjectNode body = validBody();
        ((ArrayNode) categoryOf(body, "taiwan_politics").path("sources"))
                .add(news(NEWS_SOURCE, EDGE_URL, NEWS_CATEGORY, "邊界", "2026-10-08T18:00:00+08:00"));

        assertThat(capture(body).status()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    void volatileCategoryOnlyChecksExistenceAndFreshness() throws Exception {
        // fx 列的 title 與 published_at 都和 request 不同，仍通過（validBody 的 us_taiwan_inflation）
        JsonNode categories = JSON.readTree(capture(validBody()).body()).path("riskAssessment").path("categories");
        assertThat(categories.get(4).path("assessed").booleanValue()).isTrue();

        // 同一列若 volatile 清單不含 fx，就要比對 title／publishedAt → CITATION_NOT_FOUND
        EventEvidenceCaptureService strict = new EventEvidenceCaptureService(currentUser, marketData, policies, swagger,
                bundles, news, EventEvidenceCaptureService.newTransactions(transactionManager),
                new EventEvidenceCaptureService.Settings(Set.of(), defaults().apiEndpoints(), 31, defaults().officialDomains()),
                Clock.fixed(NOW, TW));
        SrppCaptureProblem problem = catchThrowableOfType(SrppCaptureProblem.class,
                () -> strict.capture(JSON.writeValueAsString(validBody())));
        assertThat(errorKeys(problem)).containsExactly("us_taiwan_inflation/0/CITATION_NOT_FOUND");
    }

    @Test
    void titleComparisonUsesNfkcTaiwanVariantAndWhitespaceFolding() {
        assertThat(EventEvidenceCaptureService.normalizeTitle("  臺灣　央行\t\n宣布  升息 ")).isEqualTo("台灣 央行 宣布 升息");
        assertThat(EventEvidenceCaptureService.normalizeTitle("ＡＢＣ１２３")).isEqualTo("ABC123");
    }

    static Stream<Arguments> apiResponseCases() {
        return Stream.of(
                Arguments.of("endpoint 不在清單", api("/api/public/transactions", "2026-10-09"), List.of("SOURCE_NOT_ALLOWED")),
                Arguments.of("observedDate 晚於交易日", api("/api/public/market-index", "2026-10-13"), List.of("SOURCE_PERIOD_INVALID")),
                Arguments.of("observedDate 早於 31 天", api("/api/public/market-index", "2026-09-10"), List.of("SOURCE_PERIOD_INVALID")),
                Arguments.of("observedDate 無法解析", api("/api/public/market-index", "2026-9-10"), List.of("SOURCE_PERIOD_INVALID")),
                Arguments.of("observedDate 不存在", api("/api/public/market-index", "2026-02-30"), List.of("SOURCE_PERIOD_INVALID")),
                Arguments.of("兩者皆錯", api("/api/quotes", "2026-10-13"), List.of("SOURCE_NOT_ALLOWED", "SOURCE_PERIOD_INVALID")));
    }

    @ParameterizedTest(name = "API_RESPONSE 失敗：{0}")
    @MethodSource("apiResponseCases")
    void apiResponseFailures(String label, ObjectNode source, List<String> codes) throws Exception {
        ObjectNode body = validBody();
        ((ArrayNode) categoryOf(body, "taiwan_politics").path("sources")).add(source);

        assertThat(errorKeys(rejected(body))).containsExactlyElementsOf(
                codes.stream().map(code -> "taiwan_politics/0/" + code).toList());
    }

    @ParameterizedTest(name = "API_RESPONSE 通過：observedDate={0}")
    @ValueSource(strings = {"2026-10-12", "2026-09-11"})
    void apiResponseBoundariesPass(String observedDate) throws Exception {
        ObjectNode body = validBody();
        ((ArrayNode) categoryOf(body, "taiwan_politics").path("sources")).add(api("/api/public/exchange-rate/usd-twd", observedDate));

        JsonNode category = JSON.readTree(capture(body).body()).path("riskAssessment").path("categories").get(3);
        assertThat(category.path("sourceReceipts").get(0).path("provenance").asText()).isEqualTo("ATTESTED");
        assertThat(category.path("sourceReceipts").get(0).path("period").asText()).isEqualTo(observedDate);
    }

    static Stream<Arguments> officialPageCases() {
        String ok = "https://www.bls.gov/cpi/";
        return Stream.of(
                Arguments.of("http 不允許", official("http://www.bls.gov/cpi/", "2026-10-12T09:00:00+08:00", "2026-09"), List.of("SOURCE_NOT_ALLOWED")),
                Arguments.of("網域不在清單", official("https://www.example.com/", "2026-10-12T09:00:00+08:00", "2026-09"), List.of("SOURCE_NOT_ALLOWED")),
                Arguments.of("網域只是字尾相同", official("https://evilbls.gov/", "2026-10-12T09:00:00+08:00", "2026-09"), List.of("SOURCE_NOT_ALLOWED")),
                Arguments.of("允許網域當子網域前綴", official("https://bls.gov.evil.com/", "2026-10-12T09:00:00+08:00", "2026-09"), List.of("SOURCE_NOT_ALLOWED")),
                Arguments.of("URL 無法解析", official("https://bls gov/", "2026-10-12T09:00:00+08:00", "2026-09"), List.of("SOURCE_NOT_ALLOWED")),
                Arguments.of("retrievedAt 前一日", official(ok, "2026-10-11T23:59:59+08:00", "2026-09"), List.of("SOURCE_PERIOD_INVALID")),
                Arguments.of("retrievedAt 超過現在加 5 分鐘", official(ok, "2026-10-12T09:15:01+08:00", "2026-09"), List.of("SOURCE_PERIOD_INVALID")),
                Arguments.of("retrievedAt 無 offset", official(ok, "2026-10-12T09:00:00", "2026-09"), List.of("SOURCE_PERIOD_INVALID")),
                Arguments.of("period 用斜線", official(ok, "2026-10-12T09:00:00+08:00", "2026/09"), List.of("SOURCE_PERIOD_INVALID")),
                Arguments.of("period 單位數月份", official(ok, "2026-10-12T09:00:00+08:00", "2026-9"), List.of("SOURCE_PERIOD_INVALID")),
                Arguments.of("period 年月字樣", official(ok, "2026-10-12T09:00:00+08:00", "2026年9月"), List.of("SOURCE_PERIOD_INVALID")),
                Arguments.of("period 13 月", official(ok, "2026-10-12T09:00:00+08:00", "2026-13"), List.of("SOURCE_PERIOD_INVALID")),
                Arguments.of("period 只有年份", official(ok, "2026-10-12T09:00:00+08:00", "2026"), List.of("SOURCE_PERIOD_INVALID")),
                Arguments.of("網域與期間皆錯", official("https://example.com/", "2026-10-13T09:00:00+08:00", "Q3"),
                        List.of("SOURCE_NOT_ALLOWED", "SOURCE_PERIOD_INVALID")));
    }

    @ParameterizedTest(name = "OFFICIAL_PAGE 失敗：{0}")
    @MethodSource("officialPageCases")
    void officialPageFailures(String label, ObjectNode source, List<String> codes) throws Exception {
        ObjectNode body = validBody();
        ((ArrayNode) categoryOf(body, "taiwan_politics").path("sources")).add(source);

        assertThat(errorKeys(rejected(body))).containsExactlyElementsOf(
                codes.stream().map(code -> "taiwan_politics/0/" + code).toList());
    }

    static Stream<Arguments> officialPagePasses() {
        return Stream.of(
                Arguments.of("https://bls.gov/x", "2026-10-12T09:15:00+08:00", "2026-10", "bls.gov"),
                Arguments.of("HTTPS://WWW.CBC.GOV.TW/tw/", "2026-10-11T16:00:00Z", "2026-Q3", "www.cbc.gov.tw"),
                Arguments.of("https://data.stat.gov.tw/a?b=1", "2026-10-12T00:00:00+08:00", "2026-09-30", "data.stat.gov.tw"));
    }

    @ParameterizedTest(name = "OFFICIAL_PAGE 通過：{0}")
    @MethodSource("officialPagePasses")
    void officialPagePasses(String url, String retrievedAt, String period, String host) throws Exception {
        ObjectNode body = validBody();
        ((ArrayNode) categoryOf(body, "taiwan_politics").path("sources")).add(official(url, retrievedAt, period));

        JsonNode receipt = JSON.readTree(capture(body).body()).path("riskAssessment").path("categories").get(3)
                .path("sourceReceipts").get(0);
        assertThat(receipt.toString()).isEqualTo("{\"host\":\"" + host + "\",\"kind\":\"OFFICIAL_PAGE\",\"period\":\""
                + period + "\",\"provenance\":\"ATTESTED\"}");
    }

    @Test
    void emptyAllowListsRejectEveryAttestedSource() throws Exception {
        EventEvidenceCaptureService empty = new EventEvidenceCaptureService(currentUser, marketData, policies, swagger,
                bundles, news, EventEvidenceCaptureService.newTransactions(transactionManager),
                new EventEvidenceCaptureService.Settings(defaults().volatileCategories(), Set.of(), 31, List.of()),
                Clock.fixed(NOW, TW));

        SrppCaptureProblem problem = catchThrowableOfType(SrppCaptureProblem.class,
                () -> empty.capture(JSON.writeValueAsString(validBody())));

        assertThat(errorKeys(problem)).containsExactly(
                "geopolitics/-/SCORE_WITHOUT_EVIDENCE",
                "geopolitics/0/SOURCE_NOT_ALLOWED",
                "oil/0/SOURCE_NOT_ALLOWED",
                "semiconductor_cycle_and_advanced_process/1/SOURCE_NOT_ALLOWED");
    }

    @Test
    void errorsAreSortedAndTruncatedAtTwoHundred() throws Exception {
        ObjectNode body = validBody();
        body.putArray("categories");
        for (String code : SrppRiskRubricV1.CATEGORY_CODES) {
            ObjectNode category = category(code, 0);
            for (int i = 0; i < 20; i++) ((ArrayNode) category.path("sources")).add(api("/api/quotes", "2026-10-13"));
            ((ArrayNode) body.path("categories")).add(category);
        }

        SrppCaptureProblem problem = rejected(body);

        assertThat(problem.errors).hasSize(200);
        assertThat(problem.truncated).isTrue();
        assertThat(errorKeys(problem).subList(0, 4)).containsExactly("fed/0/SOURCE_NOT_ALLOWED", "fed/0/SOURCE_PERIOD_INVALID",
                "fed/1/SOURCE_NOT_ALLOWED", "fed/1/SOURCE_PERIOD_INVALID");
        assertThat(problem.errors.get(0)).containsOnlyKeys("code", "riskCategory", "sourceIndex");
    }

    // ---------------------------------------------------------------- officialEvents（D-092，永遠中性）

    @Test
    void duplicateOfficialEventSymbolIs422OfficialEventInvalid() throws Exception {
        ObjectNode body = validBody();
        body.putArray("officialEvents").add(event("0050", "VERIFIED_NO_EVENT")).add(event("0050", "SOURCE_UNAVAILABLE"));

        SrppCaptureProblem problem = rejected(body);

        assertThat(problem.errors).containsExactly(Map.of("code", "OFFICIAL_EVENT_INVALID", "symbol", "0050"));
    }

    @Test
    void officialEventsAreSortedDowngradedAndNeverAffectScores() throws Exception {
        JsonNode baseline = JSON.readTree(capture(validBody()).body()).path("riskAssessment");
        ObjectNode body = validBody();
        body.putArray("officialEvents")
                .add(event("00878", "EVENT_FOUND", official("https://www.example.com/", "2026-10-12T09:00:00+08:00", "2026-09"),
                        official("https://www.twse.com.tw/x", "2026-10-12T09:00:00+08:00", "2026/09")))
                .add(event("0050", "VERIFIED_NO_EVENT"))
                .add(event("00713", "EVENT_FOUND", official("https://www.twse.com.tw/x", "2026-10-12T09:00:00+08:00", "2026/09")))
                .add(event("006208", "EVENT_FOUND"))
                .add(event("00919", "EVENT_FOUND", official("https://www.example.com/", "2026-10-12T09:00:00+08:00", "2026-09"),
                        official("https://www.twse.com.tw/announcement", "2026-10-12T09:00:00+08:00", "2026-10-12")))
                .add(event("2330", "SOURCE_UNAVAILABLE"));

        JsonNode response = JSON.readTree(capture(body).body());

        assertThat(response.path("riskAssessment")).isEqualTo(baseline);
        List<String> rendered = new ArrayList<>();
        response.path("officialEvents").forEach(item -> rendered.add(item.toString()));
        assertThat(rendered).containsExactly(
                "{\"sourceReceipts\":[],\"status\":\"VERIFIED_NO_EVENT\",\"symbol\":\"0050\"}",
                "{\"downgradeReason\":\"OFFICIAL_SOURCE_MISSING\",\"sourceReceipts\":[],\"status\":\"SOURCE_UNAVAILABLE\",\"symbol\":\"006208\"}",
                "{\"downgradeReason\":\"SOURCE_PERIOD_INVALID\",\"sourceReceipts\":[],\"status\":\"SOURCE_UNAVAILABLE\",\"symbol\":\"00713\"}",
                "{\"downgradeReason\":\"SOURCE_NOT_ALLOWED\",\"sourceReceipts\":[],\"status\":\"SOURCE_UNAVAILABLE\",\"symbol\":\"00878\"}",
                "{\"sourceReceipts\":[{\"host\":\"www.twse.com.tw\",\"kind\":\"OFFICIAL_PAGE\",\"period\":\"2026-10-12\","
                        + "\"provenance\":\"ATTESTED\"}],\"status\":\"EVENT_FOUND\",\"symbol\":\"00919\"}",
                "{\"sourceReceipts\":[],\"status\":\"SOURCE_UNAVAILABLE\",\"symbol\":\"2330\"}");
    }

    // ---------------------------------------------------------------- 並行輸家（481.8）

    private static DataIntegrityViolationException uniqueViolation(String constraint, String sqlState) {
        SQLException sql = new SQLException("ERROR: duplicate key value violates unique constraint \"" + constraint + "\"", sqlState);
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement", sql, constraint));
    }

    @Test
    void identityConflictRequiresSqlState23505AndTheNamedIdentityConstraint() {
        assertThat(EventEvidenceCaptureService.isIdentityConflict(
                uniqueViolation(SrppEventEvidenceBundle.IDENTITY_CONSTRAINT, "23505"))).isTrue();
        assertThat(EventEvidenceCaptureService.isIdentityConflict(new RuntimeException(new SQLException(
                "duplicate key value violates unique constraint \"srpp_event_evidence_bundle_identity_uq\"", "23505")))).isTrue();
        assertThat(EventEvidenceCaptureService.isIdentityConflict(
                uniqueViolation("srpp_event_evidence_bundle_pkey", "23505"))).isFalse();
        assertThat(EventEvidenceCaptureService.isIdentityConflict(
                uniqueViolation(SrppEventEvidenceBundle.IDENTITY_CONSTRAINT, "23503"))).isFalse();
        assertThat(EventEvidenceCaptureService.isIdentityConflict(new IllegalStateException("srpp_event_evidence_bundle_identity_uq")))
                .isFalse();
        assertThat(EventEvidenceCaptureService.isIdentityConflict(null)).isFalse();
    }

    @Test
    void concurrentLoserRereadsInAFreshTransactionAndReplays() throws Exception {
        SrppEventEvidenceBundle winner = persistedFirst(validBody());
        when(bundles.findIdentity(anyLong(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(bundles.saveAndFlush(any())).thenThrow(uniqueViolation(SrppEventEvidenceBundle.IDENTITY_CONSTRAINT, "23505"));
        org.mockito.Mockito.clearInvocations(transactionManager);

        EventEvidenceCaptureService.Result result = capture(validBody());

        assertThat(result.status()).isEqualTo(HttpStatus.OK);
        assertThat(JSON.readTree(result.body()).path("idempotentReplay").booleanValue()).isTrue();
        assertThat(result.eventBundleId()).isEqualTo(winner.getId().toString());
        verify(bundles, times(2)).findIdentity(anyLong(), any(), anyString(), anyString(), anyString(), anyString());
        verify(bundles, times(1)).saveAndFlush(any());
        verify(transactionManager, times(2)).getTransaction(any());
        verify(transactionManager, times(1)).rollback(any());
    }

    @Test
    void concurrentLoserWithDifferentContentGets409() throws Exception {
        SrppEventEvidenceBundle winner = persistedFirst(validBody());
        when(bundles.findIdentity(anyLong(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty(), Optional.of(winner));
        when(bundles.saveAndFlush(any())).thenThrow(uniqueViolation(SrppEventEvidenceBundle.IDENTITY_CONSTRAINT, "23505"));
        ObjectNode body = validBody();
        categoryOf(body, "fed").put("claimedScore", 2);

        assertThat(problem(body).code).isEqualTo("BUNDLE_CONTENT_CONFLICT");
    }

    @Test
    void loserThatStillFindsNothingIsAnUnexpectedErrorWithoutRetry() {
        when(bundles.saveAndFlush(any())).thenThrow(uniqueViolation(SrppEventEvidenceBundle.IDENTITY_CONSTRAINT, "23505"));

        assertThatThrownBy(() -> capture(validBody())).isInstanceOf(IllegalStateException.class)
                .isNotInstanceOf(SrppCaptureProblem.class);
        verify(bundles, times(1)).saveAndFlush(any());
        verify(bundles, times(2)).findIdentity(anyLong(), any(), anyString(), anyString(), anyString(), anyString());
    }

    @ParameterizedTest(name = "非識別衝突（{0}）原樣拋出成 500，不重讀")
    @ValueSource(strings = {"23503", "23514"})
    void otherIntegrityViolationsAreRethrown(String sqlState) {
        DataIntegrityViolationException failure = uniqueViolation(SrppEventEvidenceBundle.IDENTITY_CONSTRAINT, sqlState);
        when(bundles.saveAndFlush(any())).thenThrow(failure);

        assertThatThrownBy(() -> capture(validBody())).isSameAs(failure);
        verify(bundles, times(1)).findIdentity(anyLong(), any(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void settingsAreParsedFromCommaSeparatedValues() {
        assertThat(EventEvidenceCaptureService.csv(" fx, us-market ,,kr-market ")).containsExactly("fx", "us-market", "kr-market");
        assertThat(EventEvidenceCaptureService.csv("")).isEmpty();
        assertThat(new EventEvidenceCaptureService.Settings(Set.of(" fx "), Set.of(), 31, List.of(" BLS.gov ")).officialDomains())
                .containsExactly("bls.gov");
    }
}
