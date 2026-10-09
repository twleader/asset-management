package com.steven.assets.service.srpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.model.News;
import com.steven.assets.model.SrppEventEvidenceBundle;
import com.steven.assets.repository.NewsHeadlineRepository;
import com.steven.assets.repository.SrppEventEvidenceBundleRepository;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.service.srpp.EventEvidenceRequest.ApiResponse;
import com.steven.assets.service.srpp.EventEvidenceRequest.Category;
import com.steven.assets.service.srpp.EventEvidenceRequest.NewsHeadline;
import com.steven.assets.service.srpp.EventEvidenceRequest.OfficialEvent;
import com.steven.assets.service.srpp.EventEvidenceRequest.OfficialPage;
import com.steven.assets.service.srpp.EventEvidenceRequest.Source;
import com.steven.assets.service.srpp.RiskEvidenceEvaluator.Assessment;
import com.steven.assets.service.srpp.RiskEvidenceEvaluator.CategoryInput;
import com.steven.assets.service.srpp.RiskEvidenceEvaluator.CategoryResult;
import com.steven.assets.srpp.SrppJcs;
import com.steven.assets.srpp.SrppPolicyRegistryService;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Requirement 181／Task 481：SRPP 事件證據擷取（變體 A：判讀在呼叫端的排程 LLM，伺服器只驗證引用並確定性計分）。
 *
 * <p>處理順序固定（481.5，第一個失敗即回應，任何失敗都不寫入、不佔用識別）：
 * request 嚴格解析（400）→ owner（{@link CurrentUserContext}，503）→ 日期與台股日曆（400／503／409）→
 * 規則包（409）→ 已發布 Swagger 身分（503／409）→ 以識別查詢既有 bundle（replay 200／409）→
 * 證據驗證（收集全部錯誤後一次 422）→ {@link RiskEvidenceEvaluator} 計分並 insert（201）。
 *
 * <p>並行（481.8）：本類方法<b>不加</b> {@code @Transactional}；「查詢既有 → 評分 → insert」由 {@link TransactionTemplate}
 * 包成單一 transaction，insert 以 {@code saveAndFlush} 在 callback 內執行，callback 內不 catch 任何例外。唯一鍵衝突
 * 穿出 {@code execute} 後才在外層判斷：只認 SQLState {@code 23505} 且指向 {@value SrppEventEvidenceBundle#IDENTITY_CONSTRAINT}
 * 者為並行輸家，於第二個全新的 {@code execute} 重讀；其他例外（含外鍵 {@code 23503}）原樣拋出成 500。
 *
 * <p>只讀 {@code news_headline}、政策 registry、台股日曆與本表；不注入任何 HTTP client、LLM client、快取寫入、
 * 排程、券商或資產／持股／現金／交易資料。結果恆為 {@code tradeAuthorization:false}、{@code placesOrders:false}；
 * {@code ATTESTED} 來源照實記錄為呼叫端聲明，不呈現為伺服器已驗證。對外 log 只寫 eventBundleId、雜湊、狀態、耗時與
 * 穩定 error code。
 */
@Service
public class EventEvidenceCaptureService {
    private static final Logger log = LoggerFactory.getLogger(EventEvidenceCaptureService.class);

    static final ZoneId TW = ZoneId.of("Asia/Taipei");
    static final int MAX_ERRORS = 200;
    private static final int SCHEMA_VERSION = 1;
    private static final int STALE_CALENDAR_DAYS = 3;
    private static final Duration CLOCK_TOLERANCE = Duration.ofMinutes(5);
    private static final String DB_VERIFIED = "DB_VERIFIED";
    private static final String ATTESTED = "ATTESTED";
    private static final String UNIQUE_VIOLATION = "23505";
    private static final DateTimeFormatter RECEIPT_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssXXX").withZone(TW);
    private static final Pattern DATE = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");
    private static final Pattern PERIOD = Pattern.compile(
            "^(?:19|20)\\d{2}(?:-(?:0[1-9]|1[0-2])(?:-(?:0[1-9]|[12]\\d|3[01]))?|-Q[1-4])$");
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}]+");

    /** 422 {@code errors[].code}。 */
    static final String UNKNOWN_CATEGORY = "UNKNOWN_CATEGORY";
    static final String MISSING_CATEGORY = "MISSING_CATEGORY";
    static final String DUPLICATE_CATEGORY = "DUPLICATE_CATEGORY";
    static final String SCORE_OUT_OF_RANGE = "SCORE_OUT_OF_RANGE";
    static final String CITATION_NOT_FOUND = "CITATION_NOT_FOUND";
    static final String SNAPSHOT_STALE = "SNAPSHOT_STALE";
    static final String SOURCE_NOT_ALLOWED = "SOURCE_NOT_ALLOWED";
    static final String SOURCE_PERIOD_INVALID = "SOURCE_PERIOD_INVALID";
    static final String SCORE_WITHOUT_EVIDENCE = "SCORE_WITHOUT_EVIDENCE";
    static final String OFFICIAL_EVENT_INVALID = "OFFICIAL_EVENT_INVALID";
    static final String OFFICIAL_SOURCE_MISSING = "OFFICIAL_SOURCE_MISSING";

    /**
     * 可設定的允許清單與期間（{@code srpp.event-evidence.*}）。預設值只是初始值，上線前須由 Steven 確認；
     * 清單為空時對應 kind 一律 {@code SOURCE_NOT_ALLOWED}。
     */
    public record Settings(Set<String> volatileCategories, Set<String> apiEndpoints, int apiObservedMaxAgeDays,
                           List<String> officialDomains) {
        public Settings {
            volatileCategories = clean(volatileCategories, false);
            apiEndpoints = clean(apiEndpoints, false);
            officialDomains = List.copyOf(clean(officialDomains, true));
            if (apiObservedMaxAgeDays < 0) throw new IllegalArgumentException("api-observed-max-age-days 不可為負");
        }

        private static Set<String> clean(java.util.Collection<String> values, boolean lowerCase) {
            Set<String> out = new LinkedHashSet<>();
            if (values != null) for (String value : values) {
                if (value == null || value.isBlank()) continue;
                String trimmed = value.trim();
                out.add(lowerCase ? trimmed.toLowerCase(Locale.ROOT) : trimmed);
            }
            return Set.copyOf(out);
        }
    }

    /** {@code body} 是完整回應本體（JCS）；{@code eventBundleId}／{@code eventBundleContentSha256} 只供 log。 */
    public record Result(HttpStatus status, String body, String eventBundleId, String eventBundleContentSha256) {}

    private final CurrentUserContext currentUser;
    private final MarketDataService marketData;
    private final SrppPolicyRegistryService policies;
    private final PublishedSwaggerIdentity swagger;
    private final SrppEventEvidenceBundleRepository bundles;
    private final NewsHeadlineRepository news;
    private final TransactionTemplate transactions;
    private final Settings settings;
    private final Clock clock;

    @Autowired
    public EventEvidenceCaptureService(
            CurrentUserContext currentUser, MarketDataService marketData,
            SrppPolicyRegistryService policies, PublishedSwaggerIdentity swagger,
            SrppEventEvidenceBundleRepository bundles, NewsHeadlineRepository news,
            PlatformTransactionManager transactionManager,
            @Value("${srpp.event-evidence.volatile-categories:fx,us-market,kr-market,kr-intraday}") String volatileCategories,
            @Value("${srpp.event-evidence.api-endpoints:/api/public/commodity-prices,/api/public/market-index,/api/public/exchange-rate/usd-twd}") String apiEndpoints,
            @Value("${srpp.event-evidence.api-observed-max-age-days:31}") int apiObservedMaxAgeDays,
            @Value("${srpp.event-evidence.official-domains:federalreserve.gov,bls.gov,bea.gov,treasury.gov,dgbas.gov.tw,stat.gov.tw,cbc.gov.tw,twse.com.tw,tpex.org.tw}") String officialDomains) {
        this(currentUser, marketData, policies, swagger, bundles, news, newTransactions(transactionManager),
                new Settings(Set.copyOf(csv(volatileCategories)), Set.copyOf(csv(apiEndpoints)), apiObservedMaxAgeDays,
                        csv(officialDomains)),
                Clock.system(TW));
    }

    /** 逗號分隔設定值；空字串＝空清單。 */
    static List<String> csv(String value) {
        if (value == null || value.isBlank()) return List.of();
        return java.util.Arrays.stream(value.split(",")).map(String::trim).filter(item -> !item.isEmpty()).distinct().toList();
    }

    EventEvidenceCaptureService(CurrentUserContext currentUser, MarketDataService marketData,
                                SrppPolicyRegistryService policies,
                                PublishedSwaggerIdentity swagger, SrppEventEvidenceBundleRepository bundles,
                                NewsHeadlineRepository news, TransactionTemplate transactions, Settings settings,
                                Clock clock) {
        this.currentUser = currentUser;
        this.marketData = marketData;
        this.policies = policies;
        this.swagger = swagger;
        this.bundles = bundles;
        this.news = news;
        this.transactions = transactions;
        this.settings = settings;
        this.clock = clock;
    }

    /** 每次 {@code execute} 都是全新 transaction（REQUIRES_NEW），不會被任何外層 transaction 吞併。 */
    static TransactionTemplate newTransactions(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    /** 刻意不加 {@code @Transactional}（481.8）。 */
    public Result capture(String raw) {
        long started = System.nanoTime();
        try {
            Result result = captureChecked(raw);
            log.info("SRPP 事件證據 capture eventBundleId={} eventBundleContentSha256={} status={} elapsedMs={}",
                    result.eventBundleId(), result.eventBundleContentSha256(), result.status().value(), elapsed(started));
            return result;
        } catch (SrppCaptureProblem problem) {
            log.info("SRPP 事件證據 capture 拒絕 status={} code={} elapsedMs={}", problem.status.value(), problem.code, elapsed(started));
            throw problem;
        }
    }

    private Result captureChecked(String raw) {
        EventEvidenceRequest request = EventEvidenceRequest.parse(raw);
        if (!currentUser.hasUser()) throw problem(HttpStatus.SERVICE_UNAVAILABLE, "OWNER_UNAVAILABLE");
        long ownerId = currentUser.getEffectiveUserId();

        if (!request.tradingDate().equals(LocalDate.now(clock.withZone(TW)))) {
            throw problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST");
        }
        Optional<Boolean> tradingDay = marketData.isTwTradingDayCachedOnly(request.tradingDate());
        if (tradingDay == null || tradingDay.isEmpty()) throw problem(HttpStatus.SERVICE_UNAVAILABLE, "CALENDAR_UNAVAILABLE");
        if (!tradingDay.get()) throw problem(HttpStatus.CONFLICT, "NON_TRADING_DAY");

        // 呼叫端提供的 hash 只當查詢鍵，不 echo 冒充支援。
        if (policies.find(request.policyBundleSha256()).isEmpty()) throw problem(HttpStatus.CONFLICT, "POLICY_UNSUPPORTED");
        Optional<String> published = swagger.sha256();
        if (published == null || published.isEmpty()) throw problem(HttpStatus.SERVICE_UNAVAILABLE, "CONTEXT_NOT_READY");
        if (!published.get().equals(request.swaggerSha256())) throw problem(HttpStatus.CONFLICT, "SWAGGER_MISMATCH");

        try {
            return transactions.execute(status -> captureOrReplay(ownerId, request));
        } catch (RuntimeException failure) {
            if (!isIdentityConflict(failure)) throw failure;
            // 並行輸家：第一個 transaction 已 rollback，在第二個全新的 transaction 重讀，不重試寫入。
            Result replay = transactions.execute(status -> findIdentity(ownerId, request)
                    .map(existing -> replay(existing, request))
                    .orElse(null));
            if (replay == null) throw new IllegalStateException("SRPP event evidence identity conflict without a readable bundle");
            return replay;
        }
    }

    /** transaction callback：查詢既有 → 證據驗證 → 計分 → insert。刻意不 catch 任何例外。 */
    private Result captureOrReplay(long ownerId, EventEvidenceRequest request) {
        Optional<SrppEventEvidenceBundle> existing = findIdentity(ownerId, request);
        if (existing.isPresent()) return replay(existing.get(), request);

        Evidence evidence = verify(request);
        List<CategoryInput> inputs = new ArrayList<>();
        for (String code : SrppRiskRubricV1.CATEGORY_CODES) {
            Category category = evidence.categories().get(code);
            inputs.add(new CategoryInput(code, category.claimedScore(), category.conflicting(),
                    evidence.receipts().get(code).size()));
        }
        Assessment assessment = RiskEvidenceEvaluator.evaluate(inputs);

        UUID id = UUID.randomUUID();
        ObjectNode content = content(id, request, assessment, evidence);
        String contentJcs = SrppJcs.canonicalize(content);
        bundles.saveAndFlush(new SrppEventEvidenceBundle(id, ownerId, request.tradingDate(), request.slot(),
                request.analysisProfile(), request.consumer(), request.decisionId(), request.policyBundleSha256(),
                request.swaggerSha256(), request.requestSha256(), contentJcs, clock.instant()));
        return response(HttpStatus.CREATED, content, true);
    }

    private Optional<SrppEventEvidenceBundle> findIdentity(long ownerId, EventEvidenceRequest request) {
        return bundles.findIdentity(ownerId, request.tradingDate(), request.slot(), request.analysisProfile(),
                request.consumer(), request.decisionId());
    }

    /** 481.5 第 7 步：metadata 不同 → 409；內容不同 → 409；皆同 → 200 回傳既有內容（不重新查 {@code news_headline}）。 */
    private Result replay(SrppEventEvidenceBundle existing, EventEvidenceRequest request) {
        if (!request.policyBundleSha256().equals(existing.getPolicyBundleSha256())
                || !request.swaggerSha256().equals(existing.getSwaggerSha256())) {
            throw problem(HttpStatus.CONFLICT, "BUNDLE_METADATA_MISMATCH");
        }
        if (!request.requestSha256().equals(existing.getRequestSha256())) {
            throw problem(HttpStatus.CONFLICT, "BUNDLE_CONTENT_CONFLICT");
        }
        return response(HttpStatus.OK, storedContent(existing), false);
    }

    /** 已保存收據必須仍符合契約（JCS、內容雜湊、識別與固定欄位），否則 502 {@code UPSTREAM_INVALID}。 */
    private static ObjectNode storedContent(SrppEventEvidenceBundle existing) {
        JsonNode node;
        try {
            node = SrppJcs.parseStrict(existing.getContentJcs());
        } catch (RuntimeException e) {
            throw problem(HttpStatus.BAD_GATEWAY, "UPSTREAM_INVALID");
        }
        if (!(node instanceof ObjectNode content)) throw problem(HttpStatus.BAD_GATEWAY, "UPSTREAM_INVALID");
        try {
            JsonNode hash = content.get("eventBundleContentSha256");
            ObjectNode unsigned = content.deepCopy();
            unsigned.remove("eventBundleContentSha256");
            JsonNode identity = content.path("identity");
            boolean valid = hash != null && hash.isTextual() && hash.textValue().equals(SrppJcs.hash(unsigned))
                    && SrppJcs.canonicalize(content).equals(existing.getContentJcs())
                    && !content.has("created") && !content.has("idempotentReplay")
                    && content.path("schemaVersion").isInt() && content.path("schemaVersion").intValue() == SCHEMA_VERSION
                    && existing.getId().toString().equals(content.path("eventBundleId").textValue())
                    && "FINAL".equals(content.path("status").textValue())
                    && content.path("tradeAuthorization").isBoolean() && !content.path("tradeAuthorization").booleanValue()
                    && content.path("placesOrders").isBoolean() && !content.path("placesOrders").booleanValue()
                    && existing.getTradingDate().toString().equals(identity.path("tradingDate").textValue())
                    && existing.getSlot().equals(identity.path("slot").textValue())
                    && existing.getAnalysisProfile().equals(identity.path("analysisProfile").textValue())
                    && existing.getConsumer().equals(identity.path("consumer").textValue())
                    && existing.getDecisionId().equals(identity.path("decisionId").textValue())
                    && existing.getPolicyBundleSha256().equals(identity.path("policyBundleSha256").textValue())
                    && existing.getSwaggerSha256().equals(identity.path("swaggerSha256").textValue());
            if (!valid) throw problem(HttpStatus.BAD_GATEWAY, "UPSTREAM_INVALID");
        } catch (IllegalArgumentException e) {
            throw problem(HttpStatus.BAD_GATEWAY, "UPSTREAM_INVALID");
        }
        return content;
    }

    // ---------------------------------------------------------------- 證據驗證（481.4／481.5 第 8 步）

    /** 驗證通過的證據：每個類別（依代碼）的 request 項與已驗證來源收據，以及官方事件揭露。 */
    private record Evidence(Map<String, Category> categories, Map<String, List<ObjectNode>> receipts,
                            List<ObjectNode> officialEvents) {}

    /** 單一 422 逐項錯誤；排序鍵為 (riskCategory 或 symbol, sourceIndex, code)。 */
    private record EvidenceError(String code, String riskCategory, String symbol, Integer sourceIndex) {
        String key() { return riskCategory != null ? riskCategory : symbol; }

        Map<String, Object> body() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("code", code);
            if (riskCategory != null) out.put("riskCategory", riskCategory);
            if (symbol != null) out.put("symbol", symbol);
            if (sourceIndex != null) out.put("sourceIndex", sourceIndex);
            return out;
        }
    }

    static final Comparator<String> NULLS_FIRST = Comparator.nullsFirst(Comparator.<String>naturalOrder());
    private static final Comparator<EvidenceError> ERROR_ORDER = Comparator
            .comparing(EvidenceError::key, NULLS_FIRST)
            .thenComparing(error -> error.sourceIndex() == null ? -1 : error.sourceIndex())
            .thenComparing(EvidenceError::code)
            .thenComparing(error -> error.riskCategory() == null ? 1 : 0);

    /** 單一來源的檢查結果：通過時有收據，否則依檢查順序列出失敗碼。 */
    private record SourceCheck(ObjectNode receipt, List<String> failures) {
        boolean passed() { return failures.isEmpty(); }
    }

    private Evidence verify(EventEvidenceRequest request) {
        TreeSet<EvidenceError> errors = new TreeSet<>(ERROR_ORDER);
        Map<String, Optional<News>> newsCache = new HashMap<>();
        Map<String, Integer> occurrences = new HashMap<>();
        for (Category category : request.categories()) {
            if (SrppRiskRubricV1.known(category.code())) occurrences.merge(category.code(), 1, Integer::sum);
        }

        Map<String, Category> categories = new HashMap<>();
        Map<String, List<ObjectNode>> receipts = new HashMap<>();
        for (Category category : request.categories()) {
            String code = category.code();
            if (!SrppRiskRubricV1.known(code)) {
                errors.add(new EvidenceError(UNKNOWN_CATEGORY, code, null, null));
                continue;
            }
            if (occurrences.get(code) > 1) errors.add(new EvidenceError(DUPLICATE_CATEGORY, code, null, null));
            if (category.claimedScore() < 0 || category.claimedScore() > SrppRiskRubricV1.max(code)) {
                errors.add(new EvidenceError(SCORE_OUT_OF_RANGE, code, null, null));
            }
            List<ObjectNode> verified = new ArrayList<>();
            for (int index = 0; index < category.sources().size(); index++) {
                SourceCheck check = check(category.sources().get(index), request.tradingDate(), newsCache);
                if (check.passed()) {
                    verified.add(check.receipt());
                } else {
                    for (String failure : check.failures()) errors.add(new EvidenceError(failure, code, null, index));
                }
            }
            if (RiskEvidenceEvaluator.scoreWithoutEvidence(category.claimedScore(), category.conflicting(), verified.size())) {
                errors.add(new EvidenceError(SCORE_WITHOUT_EVIDENCE, code, null, null));
            }
            categories.putIfAbsent(code, category);
            receipts.putIfAbsent(code, List.copyOf(verified));
        }
        for (String code : SrppRiskRubricV1.CATEGORY_CODES) {
            if (!occurrences.containsKey(code)) errors.add(new EvidenceError(MISSING_CATEGORY, code, null, null));
        }

        Map<String, Integer> symbols = new HashMap<>();
        for (OfficialEvent event : request.officialEvents()) symbols.merge(event.symbol(), 1, Integer::sum);
        symbols.forEach((symbol, count) -> {
            if (count > 1) errors.add(new EvidenceError(OFFICIAL_EVENT_INVALID, null, symbol, null));
        });

        if (!errors.isEmpty()) {
            List<Map<String, Object>> body = new ArrayList<>();
            for (EvidenceError error : errors) {
                if (body.size() == MAX_ERRORS) break;
                body.add(error.body());
            }
            throw new SrppCaptureProblem(HttpStatus.UNPROCESSABLE_ENTITY, "EVIDENCE_REJECTED", body,
                    errors.size() > MAX_ERRORS);
        }
        return new Evidence(categories, receipts, officialEvents(request));
    }

    /**
     * D-092 官方事件揭露：永遠中性，不影響分數、結果或 gate。{@code EVENT_FOUND} 沒有任何通過驗證的
     * {@code OFFICIAL_PAGE} 時降級為 {@code SOURCE_UNAVAILABLE} 並記 {@code downgradeReason}（沒有來源＝
     * {@code OFFICIAL_SOURCE_MISSING}；否則取第一筆來源的第一個失敗碼）。依 symbol 升冪輸出。
     */
    private List<ObjectNode> officialEvents(EventEvidenceRequest request) {
        List<OfficialEvent> sorted = new ArrayList<>(request.officialEvents());
        sorted.sort(Comparator.comparing(OfficialEvent::symbol));
        List<ObjectNode> out = new ArrayList<>();
        for (OfficialEvent event : sorted) {
            ObjectNode node = JsonNodeFactory.instance.objectNode();
            node.put("symbol", event.symbol());
            ArrayNode receipts = JsonNodeFactory.instance.arrayNode();
            String status = event.status();
            String downgrade = null;
            if (EventEvidenceRequest.EVENT_FOUND.equals(status)) {
                String firstFailure = null;
                for (OfficialPage page : event.sources()) {
                    SourceCheck check = checkOfficialPage(page, request.tradingDate());
                    if (check.passed()) receipts.add(check.receipt());
                    else if (firstFailure == null) firstFailure = check.failures().get(0);
                }
                if (receipts.isEmpty()) {
                    status = EventEvidenceRequest.SOURCE_UNAVAILABLE;
                    downgrade = event.sources().isEmpty() ? OFFICIAL_SOURCE_MISSING : firstFailure;
                }
            }
            node.put("status", status);
            node.set("sourceReceipts", receipts);
            if (downgrade != null) node.put("downgradeReason", downgrade);
            out.add(node);
        }
        return out;
    }

    private SourceCheck check(Source source, LocalDate tradingDate, Map<String, Optional<News>> newsCache) {
        return switch (source) {
            case NewsHeadline headline -> checkNewsHeadline(headline, tradingDate, newsCache);
            case ApiResponse api -> checkApiResponse(api, tradingDate);
            case OfficialPage page -> checkOfficialPage(page, tradingDate);
        };
    }

    /**
     * {@code NEWS_HEADLINE}：以 {@code sha256(source|url|category)} 查 {@code news_headline.dedupe_key}；一般 category 另比對
     * 正規化 title 與截斷到秒的 {@code published_at}，volatile category（固定 URL、每輪就地覆寫）只驗存在；兩者都須
     * {@code fetched_at} 不早於交易日往前 3 個日曆日 00:00（Asia/Taipei）。{@code DB_VERIFIED} 只代表該列存在且在新鮮度
     * 視窗內，不代表與風險類別相關。
     */
    private SourceCheck checkNewsHeadline(NewsHeadline headline, LocalDate tradingDate, Map<String, Optional<News>> newsCache) {
        Instant claimed;
        try {
            claimed = OffsetDateTime.parse(headline.publishedAt()).toInstant();
        } catch (DateTimeParseException e) {
            return failed(CITATION_NOT_FOUND);
        }
        String dedupeKey = dedupeKey(headline.source(), headline.url(), headline.category());
        Optional<News> found = newsCache.computeIfAbsent(dedupeKey, news::findByDedupeKey);
        if (found == null || found.isEmpty()) return failed(CITATION_NOT_FOUND);
        News row = found.get();
        if (row.getPublishedAt() == null || row.getFetchedAt() == null) throw problem(HttpStatus.BAD_GATEWAY, "UPSTREAM_INVALID");

        List<String> failures = new ArrayList<>();
        if (!settings.volatileCategories().contains(headline.category())) {
            boolean sameTitle = row.getTitle() != null && normalizeTitle(row.getTitle()).equals(normalizeTitle(headline.title()));
            boolean sameTime = row.getPublishedAt().truncatedTo(ChronoUnit.SECONDS)
                    .equals(claimed.truncatedTo(ChronoUnit.SECONDS));
            if (!sameTitle || !sameTime) failures.add(CITATION_NOT_FOUND);
        }
        Instant freshnessFloor = tradingDate.minusDays(STALE_CALENDAR_DAYS).atStartOfDay(TW).toInstant();
        if (row.getFetchedAt().isBefore(freshnessFloor)) failures.add(SNAPSHOT_STALE);
        if (!failures.isEmpty()) return new SourceCheck(null, List.copyOf(failures));

        ObjectNode receipt = receipt(EventEvidenceRequest.KIND_NEWS_HEADLINE, DB_VERIFIED,
                RECEIPT_TIME.format(row.getPublishedAt().truncatedTo(ChronoUnit.SECONDS)));
        receipt.put("dedupeKey", dedupeKey);
        return new SourceCheck(receipt, List.of());
    }

    /** {@code API_RESPONSE}：endpoint 在允許清單；{@code observedDate} 介於交易日往前 N 天至交易日（含）。 */
    private SourceCheck checkApiResponse(ApiResponse api, LocalDate tradingDate) {
        List<String> failures = new ArrayList<>();
        if (!settings.apiEndpoints().contains(api.endpoint())) failures.add(SOURCE_NOT_ALLOWED);
        LocalDate observed = strictDate(api.observedDate());
        if (observed == null || observed.isAfter(tradingDate)
                || observed.isBefore(tradingDate.minusDays(settings.apiObservedMaxAgeDays()))) {
            failures.add(SOURCE_PERIOD_INVALID);
        }
        if (!failures.isEmpty()) return new SourceCheck(null, List.copyOf(failures));
        ObjectNode receipt = receipt(EventEvidenceRequest.KIND_API_RESPONSE, ATTESTED, observed.toString());
        receipt.put("endpoint", api.endpoint());
        return new SourceCheck(receipt, List.of());
    }

    /**
     * {@code OFFICIAL_PAGE}：{@code https} 且 host 等於允許網域或其子網域；{@code retrievedAt} 落在交易日當日（Asia/Taipei）
     * 且不晚於伺服器現在加 5 分鐘；{@code period} 符合 SRPP {@code RISK_SOURCE_PERIOD_RE} 的嚴格子集。伺服器不重抓外部網站，
     * 收據 provenance 為 {@code ATTESTED}。
     */
    private SourceCheck checkOfficialPage(OfficialPage page, LocalDate tradingDate) {
        List<String> failures = new ArrayList<>();
        String host = allowedHost(page.url());
        if (host == null) failures.add(SOURCE_NOT_ALLOWED);
        boolean periodValid = PERIOD.matcher(page.period()).matches();
        try {
            Instant retrieved = OffsetDateTime.parse(page.retrievedAt()).toInstant();
            periodValid &= retrieved.atZone(TW).toLocalDate().equals(tradingDate)
                    && !retrieved.isAfter(clock.instant().plus(CLOCK_TOLERANCE));
        } catch (DateTimeParseException e) {
            periodValid = false;
        }
        if (!periodValid) failures.add(SOURCE_PERIOD_INVALID);
        if (!failures.isEmpty()) return new SourceCheck(null, List.copyOf(failures));
        ObjectNode receipt = receipt(EventEvidenceRequest.KIND_OFFICIAL_PAGE, ATTESTED, page.period());
        receipt.put("host", host);
        return new SourceCheck(receipt, List.of());
    }

    /** 回傳允許的小寫 host；不是 https、無法解析或不在允許清單時為 null。 */
    private String allowedHost(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return null;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return null;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        for (String domain : settings.officialDomains()) {
            if (host.equals(domain) || host.endsWith("." + domain)) return host;
        }
        return null;
    }

    private static LocalDate strictDate(String value) {
        if (value == null || !DATE.matcher(value).matches()) return null;
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static SourceCheck failed(String code) {
        return new SourceCheck(null, List.of(code));
    }

    private static ObjectNode receipt(String kind, String provenance, String period) {
        ObjectNode receipt = JsonNodeFactory.instance.objectNode();
        receipt.put("kind", kind);
        receipt.put("provenance", provenance);
        receipt.put("period", period);
        return receipt;
    }

    /**
     * 與 external-materials-service {@code NewsPoller.dedupeKey}（private static）相同的公式：
     * {@code sha256(source + "|" + url + "|" + category)}，UTF-8、小寫 hex。
     *
     * <p><b>必須同步的對應實作</b>：external-materials-service 的 {@code NewsPoller#dedupeKey}（寫入
     * {@code news_headline.dedupe_key} 的一方）。兩者分屬不同 Maven artifact 無法共用，登錄為
     * {@code spec/steering/structure.md} §3.2 第 4 條具名例外之六；兩邊各以同一組三個黃金向量測試釘住
     * （本模組 {@code NewsHeadlineDedupeKeyTest}、ext {@code NewsPollerDedupeKeyTest}），任一側改動公式必須同 commit 跟上。
     */
    static String dedupeKey(String source, String url, String category) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((source + "|" + url + "|" + category).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** title 比對前的正規化：NFKC、{@code 臺}→{@code 台}、連續空白折疊為單一空白、trim（與 SRPP 端 normalize 一致）。 */
    static String normalizeTitle(String title) {
        String normalized = Normalizer.normalize(title, Normalizer.Form.NFKC).replace('臺', '台');
        return WHITESPACE.matcher(normalized).replaceAll(" ").strip();
    }

    // ---------------------------------------------------------------- 回應（481.10／481.11）

    /** 不含 {@code created}／{@code idempotentReplay} 的完整回應本體，含 {@code eventBundleContentSha256}。 */
    private ObjectNode content(UUID id, EventEvidenceRequest request, Assessment assessment, Evidence evidence) {
        JsonNodeFactory json = JsonNodeFactory.instance;
        ObjectNode root = json.objectNode();
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("eventBundleId", id.toString());
        root.put("status", "FINAL");
        ObjectNode identity = root.putObject("identity");
        identity.put("tradingDate", request.tradingDate().toString());
        identity.put("slot", request.slot());
        identity.put("analysisProfile", request.analysisProfile());
        identity.put("consumer", request.consumer());
        identity.put("decisionId", request.decisionId());
        identity.put("policyBundleSha256", request.policyBundleSha256());
        identity.put("swaggerSha256", request.swaggerSha256());
        root.put("rubricSha256", SrppRiskRubricV1.rubricSha256());
        ObjectNode risk = root.putObject("riskAssessment");
        ArrayNode categories = risk.putArray("categories");
        for (CategoryResult result : assessment.categories()) {
            ObjectNode category = categories.addObject();
            category.put("code", result.code());
            category.put("score", result.score());
            category.put("assessed", result.assessed());
            category.put("evidenceStatus", result.evidenceStatus().name());
            ArrayNode receipts = category.putArray("sourceReceipts");
            evidence.receipts().get(result.code()).forEach(receipts::add);
        }
        risk.put("totalScore", assessment.totalScore());
        risk.put("assessedCount", assessment.assessedCount());
        risk.put("riskMode", assessment.riskMode().name());
        ArrayNode events = root.putArray("officialEvents");
        evidence.officialEvents().forEach(events::add);
        root.put("tradeAuthorization", false);
        root.put("placesOrders", false);
        root.put("eventBundleContentSha256", SrppJcs.hash(root));
        return root;
    }

    /** 回傳時才補 {@code created}／{@code idempotentReplay}；本體以 JCS 序列化（首次與 replay 只差這兩欄）。 */
    private static Result response(HttpStatus status, ObjectNode content, boolean created) {
        ObjectNode body = content.deepCopy();
        body.put("created", created);
        body.put("idempotentReplay", !created);
        return new Result(status, SrppJcs.canonicalize(body), content.path("eventBundleId").textValue(),
                content.path("eventBundleContentSha256").textValue());
    }

    // ---------------------------------------------------------------- 並行輸家判斷（481.8）

    /**
     * 只有「SQLState 23505 且指向具名識別 constraint」才是並行輸家。沿 cause chain 檢查 {@link SQLException}
     * 與 Hibernate {@link ConstraintViolationException#getConstraintName()}；外鍵違反（23503）等其他例外一律不是。
     * 不 import 驅動專屬的例外類別（{@code org.postgresql} 為 runtime scope）。
     */
    static boolean isIdentityConflict(Throwable failure) {
        boolean uniqueViolation = false;
        boolean identityConstraint = false;
        Set<Throwable> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                identityConstraint |= mentionsIdentity(violation.getConstraintName());
                if (UNIQUE_VIOLATION.equals(violation.getSQLState())) uniqueViolation = true;
            }
            if (cause instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) {
                uniqueViolation = true;
                identityConstraint |= mentionsIdentity(sql.getMessage());
            }
        }
        return uniqueViolation && identityConstraint;
    }

    private static boolean mentionsIdentity(String text) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(SrppEventEvidenceBundle.IDENTITY_CONSTRAINT);
    }

    private static SrppCaptureProblem problem(HttpStatus status, String code) {
        return new SrppCaptureProblem(status, code);
    }

    private static long elapsed(long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }
}
