package com.steven.assets.srpp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.AssetService;
import com.steven.assets.service.MarketDataService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Requirement 163／Task 453.2–453.4：SRPP 共用計算結果的純唯讀讀取。
 *
 * <p>只讀 PostgreSQL 已發布 package、registry 與 owner 最新快照（供 freshness 比對），日曆只用
 * {@link MarketDataService#isTwTradingDayCachedOnly}。絕不計算、不補抓、不寫入；不依賴 Redis、行情、
 * 外部 HTTP 或 producer。所有 repository 查詢（含 evidence body）一律帶明確 ownerId（{@link CurrentUserContext#getEffectiveUserId()}）。
 * 只回傳領域結果 {@link SrppReadResult}（Ok／Problem code），HTTP status 與 problem body 由 controller 對照。
 */
@Service
public class SrppDailyContextReadService {
    private static final Pattern DATE = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");
    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern UUID_LOWER =
            Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Pattern SOURCE_ID = Pattern.compile("^[a-z][a-z0-9_-]{0,63}$");
    private static final JsonNodeFactory F = JsonNodeFactory.instance;

    private final CurrentUserContext currentUser;
    private final SrppPolicyRegistryService registry;
    private final SrppContextPackageRepository packages;
    private final SrppContextEvidenceRepository evidence;
    private final AssetSnapshotRepository snapshots;
    private final AssetService assets;
    private final MarketDataService marketData;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public SrppDailyContextReadService(CurrentUserContext currentUser, SrppPolicyRegistryService registry,
                                       SrppContextPackageRepository packages, SrppContextEvidenceRepository evidence,
                                       AssetSnapshotRepository snapshots, AssetService assets,
                                       MarketDataService marketData, ObjectMapper objectMapper) {
        this(currentUser, registry, packages, evidence, snapshots, assets, marketData, objectMapper,
                Clock.system(SrppTime.TW_ZONE));
    }

    SrppDailyContextReadService(CurrentUserContext currentUser, SrppPolicyRegistryService registry,
                                SrppContextPackageRepository packages, SrppContextEvidenceRepository evidence,
                                AssetSnapshotRepository snapshots, AssetService assets,
                                MarketDataService marketData, ObjectMapper objectMapper, Clock clock) {
        this.currentUser = currentUser;
        this.registry = registry;
        this.packages = packages;
        this.evidence = evidence;
        this.snapshots = snapshots;
        this.assets = assets;
        this.marketData = marketData;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    private record Query(LocalDate tradingDate, String slot, String bundleHash, boolean evidenceView,
                         UUID packageId, String sourceId) {}

    record Freshness(String status, Instant checkedAt, TreeSet<String> changedSourceIds, TreeSet<String> reasonCodes) {}

    @Transactional(readOnly = true)
    public SrppReadResult read(String tradingDate, String slot, String policyBundleSha256, String view,
                               String packageId, String sourceId) {
        Optional<Query> parsed = parse(tradingDate, slot, policyBundleSha256, view, packageId, sourceId);
        if (parsed.isEmpty()) return SrppReadResult.problem("INVALID_REQUEST");
        Query query = parsed.get();
        if (!currentUser.hasUser()) return SrppReadResult.problem("OWNER_UNAVAILABLE");
        long ownerId = currentUser.getEffectiveUserId();
        if (registry.find(query.bundleHash()).isEmpty()) return SrppReadResult.problem("POLICY_UNSUPPORTED");

        if (query.packageId() == null) return latest(query, ownerId);
        Optional<SrppContextPackage> found = packages.findByPackageIdAndOwner(query.packageId(), ownerId);
        if (found.isEmpty()) return SrppReadResult.problem("CONTEXT_NOT_FOUND");
        SrppContextPackage pkg = found.get();
        if (!pkg.getTradingDate().equals(query.tradingDate()) || !pkg.getSlot().equals(query.slot())
                || !pkg.getPolicyBundleSha256().equals(query.bundleHash())) {
            return SrppReadResult.problem("CONTEXT_IDENTITY_MISMATCH");
        }
        JsonNode context = SrppJcs.parseStrict(pkg.getContextJcs());
        if (query.evidenceView()) return evidenceResponse(pkg, context, query.sourceId(), ownerId);
        Freshness freshness = freshness(pkg, context, ownerId);
        if (freshness == null) return SrppReadResult.problem("INTERNAL_ERROR");
        return SrppReadResult.ok(summary(pkg, freshness));
    }

    private SrppReadResult latest(Query query, long ownerId) {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(SrppTime.TW_ZONE));
        if (!query.tradingDate().equals(now.toLocalDate())) return SrppReadResult.problem("INVALID_REQUEST");
        if (now.toLocalTime().isBefore(LocalTime.parse(query.slot()))) {
            return SrppReadResult.problem("CONTEXT_NOT_READY");
        }
        Optional<Boolean> trading = marketData.isTwTradingDayCachedOnly(query.tradingDate());
        if (trading.isEmpty()) return SrppReadResult.problem("CALENDAR_UNAVAILABLE");
        if (!trading.get()) return SrppReadResult.problem("NON_TRADING_DAY");
        Optional<SrppContextPackage> found = packages.findLatestForOwner(
                ownerId, query.tradingDate(), query.slot(), query.bundleHash());
        if (found.isEmpty()) return SrppReadResult.problem("CONTEXT_NOT_READY");
        SrppContextPackage pkg = found.get();
        Freshness freshness = freshness(pkg, SrppJcs.parseStrict(pkg.getContextJcs()), ownerId);
        if (freshness == null) return SrppReadResult.problem("INTERNAL_ERROR");
        return switch (freshness.status()) {
            case "STALE" -> SrppReadResult.problem("CONTEXT_STALE");
            case "UNKNOWN" -> SrppReadResult.problem("CONTEXT_NOT_READY");
            default -> SrppReadResult.ok(summary(pkg, freshness));
        };
    }

    /** @return null 表示時鐘異常（checkedAt 早於 generatedAt），呼叫端回 500，不得偽造。 */
    Freshness freshness(SrppContextPackage pkg, JsonNode context, long ownerId) {
        Instant checkedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant generatedAt = SrppTime.parse(context.path("generatedAt").textValue());
        if (checkedAt.isBefore(generatedAt)) return null;

        TreeSet<String> changed = new TreeSet<>();
        TreeSet<String> reasons = new TreeSet<>();
        boolean unverifiable = false;

        String packagedRevision = null;
        for (JsonNode source : context.path("sources")) {
            if ("assets".equals(source.path("sourceId").asText())) packagedRevision = source.path("revision").asText(null);
        }
        try {
            Optional<AssetSnapshot> latest = snapshots.findLatestWithStocksByOwnerUserId(ownerId);
            String current = latest.map(entity -> SrppSnapshotRevision.of(assets.getSnapshotDetail(entity))).orElse(null);
            if (current == null || !current.equals(packagedRevision)) {
                changed.add("assets");
                reasons.add("SOURCE_REVISION_CHANGED");
            }
        } catch (RuntimeException e) {
            unverifiable = true;
            reasons.add("SOURCE_UNVERIFIABLE");
        }

        Optional<Boolean> calendar = marketData.isTwTradingDayCachedOnly(pkg.getTradingDate());
        if (calendar.isEmpty()) {
            unverifiable = true;
            reasons.add("CALENDAR_UNVERIFIABLE");
        } else if (!calendar.get()) {
            changed.add("calendar");
            reasons.add("CALENDAR_CHANGED");
        }

        if (!changed.isEmpty()) return new Freshness("STALE", checkedAt, changed, reasons);
        if (unverifiable) return new Freshness("UNKNOWN", checkedAt, new TreeSet<>(), reasons);
        return new Freshness("CURRENT", checkedAt, new TreeSet<>(), new TreeSet<>());
    }

    private String summary(SrppContextPackage pkg, Freshness freshness) {
        String contextJcs = pkg.getContextJcs();
        ObjectNode fresh = F.objectNode();
        fresh.put("status", freshness.status());
        fresh.put("checkedAt", SrppTime.format(freshness.checkedAt()));
        ArrayNode changed = fresh.putArray("changedSourceIds");
        freshness.changedSourceIds().forEach(changed::add);
        ArrayNode reasons = fresh.putArray("reasonCodes");
        freshness.reasonCodes().forEach(reasons::add);
        // context 原文逐位元嵌入，不 parse 後重新序列化。
        return "{\"kind\":\"SUMMARY\",\"context\":" + contextJcs
                + ",\"contextContentSha256\":\"" + SrppJcs.sha256Hex(contextJcs) + "\""
                + ",\"freshness\":" + fresh + "}";
    }

    private SrppReadResult evidenceResponse(SrppContextPackage pkg, JsonNode context, String sourceId, long ownerId) {
        boolean available = false;
        for (JsonNode source : context.path("sources")) {
            if (sourceId.equals(source.path("sourceId").asText()) && "AVAILABLE".equals(source.path("state").asText())) {
                available = true;
            }
        }
        if (!available) return SrppReadResult.problem("SOURCE_EVIDENCE_NOT_FOUND");
        Optional<String> body = evidence.findBody(pkg.getPackageId(), sourceId, ownerId);
        if (body.isEmpty()) return SrppReadResult.problem("SOURCE_EVIDENCE_NOT_FOUND");
        String escaped;
        try {
            escaped = objectMapper.writeValueAsString(body.get());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("evidence body serialization failed", e);
        }
        return SrppReadResult.ok("{\"kind\":\"EVIDENCE\",\"packageId\":\"" + pkg.getPackageId().toString().toLowerCase()
                + "\",\"sourceId\":\"" + sourceId + "\",\"bodyMediaType\":\"application/json\",\"bodyEncoding\":\"UTF-8\""
                + ",\"body\":" + escaped + ",\"bodySha256\":\"" + SrppJcs.sha256Hex(body.get()) + "\"}");
    }

    private static Optional<Query> parse(String tradingDate, String slot, String bundleHash, String view,
                                         String packageId, String sourceId) {
        if (tradingDate == null || slot == null || bundleHash == null) return Optional.empty();
        if (!DATE.matcher(tradingDate).matches()) return Optional.empty();
        LocalDate date;
        try {
            date = LocalDate.parse(tradingDate);
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
        if (!"09:05".equals(slot) && !"11:40".equals(slot)) return Optional.empty();
        if (!HASH.matcher(bundleHash).matches()) return Optional.empty();
        boolean evidenceView;
        if (view == null || "summary".equals(view)) evidenceView = false;
        else if ("evidence".equals(view)) evidenceView = true;
        else return Optional.empty();
        UUID pkg = null;
        if (packageId != null) {
            if (!UUID_LOWER.matcher(packageId).matches()) return Optional.empty();
            pkg = UUID.fromString(packageId);
        }
        if (sourceId != null && !SOURCE_ID.matcher(sourceId).matches()) return Optional.empty();
        if (evidenceView && (pkg == null || sourceId == null)) return Optional.empty();
        if (!evidenceView && sourceId != null) return Optional.empty();
        return Optional.of(new Query(date, slot, bundleHash, evidenceView, pkg, sourceId));
    }
}
