package com.steven.assets.service.srpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.srpp.SrppJcs;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Requirement 181／Task 481.3／481.4：事件證據擷取 request 的嚴格解析與正規化（純函式，無 IO）。
 *
 * <p>只負責 481.5 第 2 步：JSON 結構、型別、未知欄位、必填與長度上限，一律 400 {@code INVALID_REQUEST}。
 * 類別代碼／重複／分數範圍、引用是否存在、來源期間等屬於證據驗證（422），在這裡只確認型別，不判斷內容。
 * {@link #requestSha256()} 是正規化後 request 的 RFC 8785 JCS UTF-8 SHA-256：去除 {@code ownerEmail}、
 * {@code analysisProfile} 缺省補 {@code TW_DAILY}、每個 category 的 {@code conflicting} 缺省補 {@code false}、
 * {@code officialEvents} 缺省補 {@code []}。
 */
record EventEvidenceRequest(LocalDate tradingDate, String slot, String analysisProfile, String consumer,
                            String decisionId, String policyBundleSha256, String swaggerSha256,
                            List<Category> categories, List<OfficialEvent> officialEvents, String requestSha256) {

    static final String DEFAULT_PROFILE = "TW_DAILY";
    static final String KIND_NEWS_HEADLINE = "NEWS_HEADLINE";
    static final String KIND_API_RESPONSE = "API_RESPONSE";
    static final String KIND_OFFICIAL_PAGE = "OFFICIAL_PAGE";
    static final String EVENT_FOUND = "EVENT_FOUND";
    static final String VERIFIED_NO_EVENT = "VERIFIED_NO_EVENT";
    static final String SOURCE_UNAVAILABLE = "SOURCE_UNAVAILABLE";

    static final int MAX_CATEGORIES = 12;
    static final int MAX_CATEGORY_SOURCES = 20;
    static final int MAX_OFFICIAL_EVENTS = 100;
    static final int MAX_OFFICIAL_EVENT_SOURCES = 3;

    /** 與 BFF {@code SrppOrchestratedQuery.EMAIL} 相同的 pattern（不分大小寫、最大 254 字元）。 */
    static final Pattern EMAIL = Pattern.compile("^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", Pattern.CASE_INSENSITIVE);
    private static final int EMAIL_MAX = 254;
    private static final Pattern DATE = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");
    private static final Pattern DECISION_ID = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,99}$");
    private static final Pattern SHA256 = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern SYMBOL = Pattern.compile("^[0-9A-Z]{4,8}$");
    private static final Set<String> SLOTS = Set.of("09:05", "11:40");
    private static final Set<String> CONSUMERS = Set.of("Claude", "Codex");
    private static final Set<String> EVENT_STATUSES = Set.of(EVENT_FOUND, VERIFIED_NO_EVENT, SOURCE_UNAVAILABLE);

    private static final Set<String> TOP_REQUIRED = Set.of("tradingDate", "slot", "consumer", "decisionId",
            "policyBundleSha256", "swaggerSha256", "categories");
    private static final Set<String> TOP_OPTIONAL = Set.of("ownerEmail", "analysisProfile", "officialEvents");
    private static final Set<String> CATEGORY_REQUIRED = Set.of("code", "claimedScore", "sources");
    private static final Set<String> CATEGORY_OPTIONAL = Set.of("conflicting");
    private static final Set<String> NEWS_FIELDS = Set.of("kind", "source", "url", "category", "title", "publishedAt");
    private static final Set<String> API_FIELDS = Set.of("kind", "endpoint", "observedDate");
    private static final Set<String> OFFICIAL_FIELDS = Set.of("kind", "url", "retrievedAt", "period");
    private static final Set<String> EVENT_FIELDS = Set.of("symbol", "status", "sources");

    private static final int CODE_MAX = 64;
    private static final int SOURCE_MAX = 100;
    private static final int URL_MAX = 1024;
    private static final int TITLE_MAX = 500;
    private static final int CATEGORY_MAX = 32;
    private static final int ENDPOINT_MAX = 200;

    /** 單一引用來源；三種 kind 的欄位集合固定。 */
    sealed interface Source permits NewsHeadline, ApiResponse, OfficialPage {
        String kind();
    }

    /** {@code news_headline} 引用；{@code publishedAt} 的解析失敗屬 422 {@code CITATION_NOT_FOUND}，此處只確認是字串。 */
    record NewsHeadline(String source, String url, String category, String title, String publishedAt) implements Source {
        @Override public String kind() { return KIND_NEWS_HEADLINE; }
    }

    /** 9090 API 回應引用；{@code observedDate} 的解析與期間屬 422 {@code SOURCE_PERIOD_INVALID}。 */
    record ApiResponse(String endpoint, String observedDate) implements Source {
        @Override public String kind() { return KIND_API_RESPONSE; }
    }

    /** 官方頁面引用；網域、{@code retrievedAt} 與 {@code period} 的檢查屬 422。 */
    record OfficialPage(String url, String retrievedAt, String period) implements Source {
        @Override public String kind() { return KIND_OFFICIAL_PAGE; }
    }

    record Category(String code, int claimedScore, boolean conflicting, List<Source> sources) {}

    record OfficialEvent(String symbol, String status, List<OfficialPage> sources) {}

    static EventEvidenceRequest parse(String raw) {
        JsonNode root;
        try {
            root = SrppJcs.parseStrict(raw);
        } catch (RuntimeException e) {
            throw invalid();
        }
        if (!root.isObject()) throw invalid();
        fields(root, TOP_REQUIRED, TOP_OPTIONAL);

        JsonNode owner = root.get("ownerEmail");
        if (owner != null && (!owner.isTextual() || owner.textValue().length() > EMAIL_MAX
                || !EMAIL.matcher(owner.textValue()).matches())) throw invalid();

        String date = text(root.get("tradingDate"));
        if (!DATE.matcher(date).matches()) throw invalid();
        LocalDate tradingDate;
        try {
            tradingDate = LocalDate.parse(date);
        } catch (DateTimeParseException e) {
            throw invalid();
        }
        String slot = text(root.get("slot"));
        if (!SLOTS.contains(slot)) throw invalid();
        String profile = root.has("analysisProfile") ? text(root.get("analysisProfile")) : DEFAULT_PROFILE;
        if (!DEFAULT_PROFILE.equals(profile)) throw invalid();
        String consumer = text(root.get("consumer"));
        if (!CONSUMERS.contains(consumer)) throw invalid();
        String decisionId = text(root.get("decisionId"));
        if (!DECISION_ID.matcher(decisionId).matches()) throw invalid();
        String policy = text(root.get("policyBundleSha256"));
        String swagger = text(root.get("swaggerSha256"));
        if (!SHA256.matcher(policy).matches() || !SHA256.matcher(swagger).matches()) throw invalid();

        List<Category> categories = new ArrayList<>();
        for (JsonNode item : array(root.get("categories"), MAX_CATEGORIES)) categories.add(category(item));

        List<OfficialEvent> events = new ArrayList<>();
        if (root.has("officialEvents")) {
            for (JsonNode item : array(root.get("officialEvents"), MAX_OFFICIAL_EVENTS)) events.add(officialEvent(item));
        }

        return new EventEvidenceRequest(tradingDate, slot, profile, consumer, decisionId, policy, swagger,
                List.copyOf(categories), List.copyOf(events), normalizedSha256((ObjectNode) root));
    }

    private static Category category(JsonNode item) {
        if (!item.isObject()) throw invalid();
        fields(item, CATEGORY_REQUIRED, CATEGORY_OPTIONAL);
        String code = text(item.get("code"));
        if (code.length() > CODE_MAX) throw invalid();
        JsonNode score = item.get("claimedScore");
        if (!score.isIntegralNumber() || !score.canConvertToInt()) throw invalid();
        boolean conflicting = false;
        if (item.has("conflicting")) {
            JsonNode value = item.get("conflicting");
            if (!value.isBoolean()) throw invalid();
            conflicting = value.booleanValue();
        }
        List<Source> sources = new ArrayList<>();
        for (JsonNode source : array(item.get("sources"), MAX_CATEGORY_SOURCES)) sources.add(source(source));
        return new Category(code, score.intValue(), conflicting, List.copyOf(sources));
    }

    private static OfficialEvent officialEvent(JsonNode item) {
        if (!item.isObject()) throw invalid();
        fields(item, EVENT_FIELDS, Set.of());
        String symbol = text(item.get("symbol"));
        if (!SYMBOL.matcher(symbol).matches()) throw invalid();
        String status = text(item.get("status"));
        if (!EVENT_STATUSES.contains(status)) throw invalid();
        List<OfficialPage> pages = new ArrayList<>();
        for (JsonNode node : array(item.get("sources"), MAX_OFFICIAL_EVENT_SOURCES)) {
            Source source = source(node);
            // 非法組合：VERIFIED_NO_EVENT／SOURCE_UNAVAILABLE 不得帶來源；EVENT_FOUND 只接受 OFFICIAL_PAGE。
            if (!EVENT_FOUND.equals(status) || !(source instanceof OfficialPage page)) throw invalid();
            pages.add(page);
        }
        return new OfficialEvent(symbol, status, List.copyOf(pages));
    }

    private static Source source(JsonNode node) {
        if (!node.isObject()) throw invalid();
        String kind = text(node.get("kind"));
        return switch (kind) {
            case KIND_NEWS_HEADLINE -> {
                fields(node, NEWS_FIELDS, Set.of());
                yield new NewsHeadline(bounded(node.get("source"), SOURCE_MAX), bounded(node.get("url"), URL_MAX),
                        bounded(node.get("category"), CATEGORY_MAX), bounded(node.get("title"), TITLE_MAX),
                        text(node.get("publishedAt")));
            }
            case KIND_API_RESPONSE -> {
                fields(node, API_FIELDS, Set.of());
                yield new ApiResponse(bounded(node.get("endpoint"), ENDPOINT_MAX), text(node.get("observedDate")));
            }
            case KIND_OFFICIAL_PAGE -> {
                fields(node, OFFICIAL_FIELDS, Set.of());
                yield new OfficialPage(bounded(node.get("url"), URL_MAX), text(node.get("retrievedAt")),
                        text(node.get("period")));
            }
            default -> throw invalid();
        };
    }

    /** 正規化後 request 的 JCS SHA-256（等價重送——缺省與明寫預設值——得到同一雜湊）。 */
    private static String normalizedSha256(ObjectNode root) {
        ObjectNode normalized = root.deepCopy();
        normalized.remove("ownerEmail");
        if (!normalized.has("analysisProfile")) normalized.put("analysisProfile", DEFAULT_PROFILE);
        for (JsonNode category : normalized.get("categories")) {
            ObjectNode object = (ObjectNode) category;
            if (!object.has("conflicting")) object.put("conflicting", false);
        }
        if (!normalized.has("officialEvents")) normalized.putArray("officialEvents");
        return SrppJcs.hash(normalized);
    }

    /** 欄位集合必須是 required ⊆ actual ⊆ required ∪ optional。 */
    private static void fields(JsonNode node, Set<String> required, Set<String> optional) {
        for (String name : required) if (!node.has(name)) throw invalid();
        Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            if (!required.contains(name) && !optional.contains(name)) throw invalid();
        }
    }

    private static ArrayNode array(JsonNode node, int max) {
        if (node == null || !node.isArray() || node.size() > max) throw invalid();
        return (ArrayNode) node;
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isTextual()) throw invalid();
        return node.textValue();
    }

    /** 字串、非空白且不超過上限（以 UTF-16 code unit 計，與 DB varchar 的字元數一致於 BMP 範圍）。 */
    private static String bounded(JsonNode node, int max) {
        String value = text(node);
        if (value.isBlank() || value.length() > max) throw invalid();
        return value;
    }

    private static SrppCaptureProblem invalid() {
        return new SrppCaptureProblem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
    }
}
