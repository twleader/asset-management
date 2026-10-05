package com.steven.assets.srpp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.dto.LatestAssetsDto;
import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.service.PriceQueryService;
import com.steven.assets.service.TradingRadarSnapshotStore;
import com.steven.assets.util.MarketZones;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;

/** Owner-scoped, read-only endpoints for the on-demand SRPP API. */
@Service
public class SrppOrchestratedReadService {
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final Duration LIVE_MAX_AGE = Duration.ofMinutes(5);
    private static final Set<String> QUOTE_MARKETS = Set.of("台股", "美股", "英股");
    private static final Set<String> TRUSTED_TW_CLOSE_SOURCES = Set.of(
            "TWSE_MI_INDEX", "TPEX_DAILY_CLOSE", "FINMIND_TW_CLOSE");
    private static final Pattern UUID_LOWER = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final Set<String> CALCULATION_IDS = Set.of(
            "ASSET_RECONCILIATION", "ALLOCATION_GAP", "CASH_INCOME",
            "FUNDING_CAPACITY", "COMPLETED_TECHNICALS", "SYMBOL_RULE_FACTS");
    private static final Map<String, String> MODULES = Map.of(
            "ASSET_RECONCILIATION", "assets",
            "ALLOCATION_GAP", "allocation",
            "CASH_INCOME", "cashIncome",
            "FUNDING_CAPACITY", "funding",
            "COMPLETED_TECHNICALS", "completedTechnicals");

    private final CurrentUserContext currentUser;
    private final SrppPolicyRegistryService policies;
    private final SrppContextPackageRepository packages;
    private final SrppContextEvidenceRepository evidence;
    private final MarketDataService marketData;
    private final PriceQueryService priceQuery;
    private final TradingRadarSnapshotStore radarSnapshots;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public SrppOrchestratedReadService(CurrentUserContext currentUser, SrppPolicyRegistryService policies,
                                       SrppContextPackageRepository packages, SrppContextEvidenceRepository evidence,
                                       MarketDataService marketData, PriceQueryService priceQuery,
                                       TradingRadarSnapshotStore radarSnapshots, ObjectMapper mapper) {
        this(currentUser, policies, packages, evidence, marketData, priceQuery, radarSnapshots, mapper, Clock.system(TAIPEI));
    }

    SrppOrchestratedReadService(CurrentUserContext currentUser, SrppPolicyRegistryService policies,
                                SrppContextPackageRepository packages, SrppContextEvidenceRepository evidence,
                                MarketDataService marketData, PriceQueryService priceQuery,
                                TradingRadarSnapshotStore radarSnapshots, ObjectMapper mapper, Clock clock) {
        this.currentUser = currentUser;
        this.policies = policies;
        this.packages = packages;
        this.evidence = evidence;
        this.marketData = marketData;
        this.priceQuery = priceQuery;
        this.radarSnapshots = radarSnapshots;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SrppReadResult context(String tradingDateText, String slot, String policyHash) {
        LocalDate date = parseDate(tradingDateText);
        if (date == null || !validSlot(slot) || !validHash(policyHash)) return problem("INVALID_REQUEST");
        if (!currentUser.hasUser()) return problem("OWNER_UNAVAILABLE");
        Optional<SupportedPolicy> policy = policies.find(policyHash);
        if (policy.isEmpty()) return problem("POLICY_UNSUPPORTED");
        LocalDate today = LocalDate.now(clock.withZone(TAIPEI));
        if (!today.equals(date) || LocalTime.now(clock.withZone(TAIPEI)).isBefore(LocalTime.parse(slot))) {
            return problem("CONTEXT_NOT_READY");
        }
        Optional<Boolean> tradingDay = marketData.isTwTradingDayCachedOnly(date);
        if (tradingDay == null || tradingDay.isEmpty()) return problem("CONTEXT_NOT_READY");
        if (!tradingDay.get()) return problem("INVALID_REQUEST");
        Optional<SrppContextPackage> found = packages.findLatestForOwner(
                currentUser.getEffectiveUserId(), date, slot, policyHash);
        if (found.isEmpty()) return problem("CONTEXT_NOT_READY");
        return packageContext(found.get(), currentUser.getEffectiveUserId());
    }

    @Transactional(readOnly = true)
    public SrppReadResult calculations(String contextId, List<String> calculationIds, List<String> stockCodes) {
        if (!validContextId(contextId) || !validCalculationIds(calculationIds) || !validStockCodes(stockCodes)) {
            return problem("INVALID_REQUEST");
        }
        Optional<SrppContextPackage> found = ownedPackage(contextId);
        if (found.isEmpty()) return problem("CONTEXT_NOT_FOUND");
        SrppContextPackage pkg = found.get();
        if (policies.find(pkg.getPolicyBundleSha256()).isEmpty()) return problem("POLICY_UNSUPPORTED");
        ValidatedContext context = validatePackage(pkg, currentUser.getEffectiveUserId());
        if (context == null) return problem("CONTEXT_STALE");
        if (stockCodes != null && !stockCodes.isEmpty() && !allowed(context.assetsBody(), stockCodes)) {
            return problem("INVALID_REQUEST");
        }
        Optional<SupportedPolicy> policy = policies.find(pkg.getPolicyBundleSha256());
        if (policy.isEmpty()) return problem("POLICY_UNSUPPORTED");
        LatestAssetsDto.Response assets;
        try {
            assets = mapper.readValue(context.assetsBody(), LatestAssetsDto.Response.class);
        } catch (JsonProcessingException invalid) {
            return problem("CONTEXT_STALE");
        }
        SrppModuleCalculator.Result computed = SrppModuleCalculator.calculate(assets, policy.get(), pkg.getTradingDate());
        if (computed.rejectReason().isPresent()) return problem("CONTEXT_STALE");
        JsonNode calculated = computed.modules();
        ObjectNode response = common(pkg, context.sourceVector());
        response.put("formulaSetSha256", policy.get().formulaSetSha256());
        ArrayNode results = response.putArray("calculations");
        int complete = 0;
        int partial = 0;
        int unavailable = 0;
        for (String id : calculationIds) {
            ObjectNode item = results.addObject();
            item.put("calculationId", id);
            item.put("formulaVersion", policy.get().formulaVersion());
            item.put("formulaSetSha256", policy.get().formulaSetSha256());
            String moduleName = MODULES.get(id);
            JsonNode module = moduleName == null ? null : calculated.path(moduleName);
            if (module == null || module.isMissingNode()) {
                item.put("status", "UNAVAILABLE");
                ObjectNode emptyData = F.objectNode();
                if ("SYMBOL_RULE_FACTS".equals(id)) emptyData.set("symbols", F.arrayNode());
                item.set("data", emptyData);
                item.putArray("reasonCodes").add("CALCULATION_NOT_ENABLED");
                item.putArray("sourceIds");
                unavailable++;
                continue;
            }
            String status = module.path("status").asText("UNAVAILABLE");
            item.put("status", status);
            JsonNode data = module.path("data");
            item.set("data", data.isObject() ? data.deepCopy() : F.objectNode());
            item.set("reasonCodes", copyArray(module.path("reasonCodes")));
            item.set("sourceIds", copyArray(module.path("sourceIds")));
            switch (status) {
                case "COMPLETE" -> complete++;
                case "PARTIAL" -> partial++;
                default -> unavailable++;
            }
        }
        response.set("coverage", coverage(calculationIds.size(), complete, partial, unavailable));
        return json(response);
    }

    @Transactional(readOnly = true)
    public SrppReadResult marketFacts(String contextId, List<String> stockCodes, Set<String> include) {
        if (!validContextId(contextId) || !validStockCodes(stockCodes) || stockCodes == null || stockCodes.isEmpty()
                || include == null || include.isEmpty() || !Set.of("quote", "radar").containsAll(include)) {
            return problem("INVALID_REQUEST");
        }
        Optional<SrppContextPackage> found = ownedPackage(contextId);
        if (found.isEmpty()) return problem("CONTEXT_NOT_FOUND");
        SrppContextPackage pkg = found.get();
        if (policies.find(pkg.getPolicyBundleSha256()).isEmpty()) return problem("POLICY_UNSUPPORTED");
        ValidatedContext context = validatePackage(pkg, currentUser.getEffectiveUserId());
        if (context == null) return problem("CONTEXT_STALE");
        Map<String, String> markets = allowedMarkets(context.assetsBody(), stockCodes);
        if (markets == null) return problem("INVALID_REQUEST");

        Set<PriceQueryService.PriceKey> priceKeys = markets.entrySet().stream()
                .map(entry -> new PriceQueryService.PriceKey(entry.getKey(), entry.getValue()))
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<PriceQueryService.PriceKey, Optional<PriceQueryService.LivePrice>> quotes = include.contains("quote")
                ? priceQuery.getLiveBatch(priceKeys, Map.of()) : Map.of();
        TradingRadarDto.Response current = null;
        try {
            if (include.contains("radar")) {
                current = radarSnapshots.latest(currentUser.getEffectiveUserId())
                        .map(node -> {
                            try { return mapper.treeToValue(node, TradingRadarDto.Response.class); }
                            catch (JsonProcessingException invalid) { throw new IllegalArgumentException(invalid); }
                        })
                        .orElse(null);
            }
        } catch (RuntimeException unavailable) {
            // This route reads only the already-persisted owner radar snapshot. A missing/corrupt snapshot
            // degrades radar facts per symbol; it never falls back to assembling or refreshing radar inputs.
        }
        List<TradingRadarDto.StockDecision> candidates = current == null || current.stocks() == null
                ? List.of() : current.stocks();
        ObjectNode response = common(pkg, F.arrayNode());
        ArrayNode symbols = response.putArray("symbols");
        TreeMap<String, ObjectNode> quoteProjection = new TreeMap<>();
        TreeMap<String, ObjectNode> radarProjection = new TreeMap<>();
        String quoteAsOf = null;
        String radarAsOf = null;
        for (String code : stockCodes) {
            String market = markets.get(code);
            PriceQueryService.LivePrice quote = quotes.getOrDefault(
                    new PriceQueryService.PriceKey(code, market), Optional.empty()).orElse(null);
            TradingRadarDto.StockDecision stock = candidates.stream()
                    .filter(item -> code.equals(item.stockCode()) && market.equals(item.market())).findFirst().orElse(null);
            ObjectNode item = symbols.addObject();
            item.put("market", market);
            item.put("stockCode", code);
            boolean quoteIncluded = include.contains("quote");
            boolean radarIncluded = include.contains("radar");
            QuoteFact quoteFact = quoteIncluded ? quoteFact(quote, market, marketData, clock)
                    : new QuoteFact("UNAVAILABLE", null);
            String quoteStatus = quoteFact.status();
            String quoteDataAsOf = quoteFact.dataAsOf();
            boolean quoteAvailable = quoteIncluded && !"UNAVAILABLE".equals(quoteStatus);
            item.put("quoteStatus", quoteStatus);
            if (quoteIncluded) {
                putNullable(item, "quoteDataAsOf", quoteDataAsOf);
                item.put("quoteSourceId", quoteAvailable ? "CANONICAL_MARKET_READ" : null);
                ObjectNode lastPrice = item.putObject("lastPrice");
                putDecimal(lastPrice, "value", quoteAvailable ? quote.price() : null);
                lastPrice.put("unit", market.equals("台股") ? "TWD_PER_SHARE" : "LOCAL_CURRENCY_PER_SHARE");
                lastPrice.put("quality", quoteAvailable ? "EXACT" : "UNAVAILABLE");
                reasons(lastPrice, quoteAvailable ? List.of() : List.of("QUOTE_UNAVAILABLE"));
                if (!quoteAvailable) lastPrice.putArray("sourceIds");
                else lastPrice.putArray("sourceIds").add("CANONICAL_MARKET_READ");
                ObjectNode projected = F.objectNode();
                projected.put("market", market);
                projected.put("stockCode", code);
                projected.put("quoteStatus", quoteStatus);
                putNullable(projected, "quoteDataAsOf", quoteDataAsOf);
                projected.put("quoteSourceId", quoteAvailable ? "CANONICAL_MARKET_READ" : null);
                projected.set("lastPrice", lastPrice.deepCopy());
                quoteProjection.put(market + ":" + code, projected);
                if (quoteAvailable) quoteAsOf = earliestAsOf(quoteAsOf, quoteDataAsOf);
            } else {
                item.putNull("quoteDataAsOf");
                item.putNull("quoteSourceId");
                item.putNull("lastPrice");
            }
            String radarDataAsOf = radarIncluded && stock != null ? sourceDate(stock.asOfDate()) : null;
            boolean radarAvailable = radarIncluded && stock != null && radarDataAsOf != null;
            item.put("radarStatus", radarAvailable ? "AVAILABLE" : "UNAVAILABLE");
            if (radarAvailable) {
                putNullable(item, "radarDataAsOf", radarDataAsOf);
                item.put("radarSourceId", "TRADING_RADAR_SNAPSHOT");
                ObjectNode facts = radarFacts(stock);
                item.set("radarFacts", facts);
                ObjectNode projected = F.objectNode();
                projected.put("market", market);
                projected.put("stockCode", code);
                projected.put("radarStatus", "AVAILABLE");
                putNullable(projected, "radarDataAsOf", radarDataAsOf);
                projected.put("radarSourceId", "TRADING_RADAR_SNAPSHOT");
                projected.set("radarFacts", facts.deepCopy());
                radarProjection.put(market + ":" + code, projected);
                radarAsOf = earliestAsOf(radarAsOf, radarDataAsOf);
            } else {
                item.putNull("radarDataAsOf");
                item.putNull("radarSourceId");
                item.set("radarFacts", F.objectNode());
                if (radarIncluded) {
                    ObjectNode projected = F.objectNode();
                    projected.put("market", market);
                    projected.put("stockCode", code);
                    projected.put("radarStatus", "UNAVAILABLE");
                    projected.putNull("radarDataAsOf");
                    projected.putNull("radarSourceId");
                    projected.set("radarFacts", F.objectNode());
                    radarProjection.put(market + ":" + code, projected);
                }
            }
            ArrayNode reasonCodes = item.putArray("reasonCodes");
            if (quoteIncluded && "UNAVAILABLE".equals(quoteStatus)) reasonCodes.add("QUOTE_UNAVAILABLE");
            if (radarIncluded && stock == null) reasonCodes.add("RADAR_UNAVAILABLE");
            item.put("status", marketSymbolStatus(quoteStatus, radarAvailable, include));
        }
        response.set("sourceVector", marketVector(quoteProjection, quoteAsOf, radarProjection, radarAsOf));
        int complete = 0;
        int partial = 0;
        int unavailable = 0;
        for (JsonNode symbol : symbols) {
            switch (symbol.path("status").asText()) {
                case "COMPLETE" -> complete++;
                case "PARTIAL" -> partial++;
                default -> unavailable++;
            }
        }
        response.set("coverage", coverage(stockCodes.size(), complete, partial, unavailable));
        response.put("marketCaptureId", marketCaptureId(response));
        return json(response);
    }

    private SrppReadResult packageContext(SrppContextPackage pkg, long ownerId) {
        ValidatedContext context = validatePackage(pkg, ownerId);
        if (context == null) return problem("CONTEXT_STALE");
        ObjectNode response = common(pkg, context.sourceVector());
        response.put("formulaSetSha256", context.formulaSetSha256());
        response.put("capturedAt", pkg.getGeneratedAt().toString());
        ArrayNode allowed = response.putArray("allowedStockCodes");
        Map<String, String> scoped = allowedMarkets(context.assetsBody(), null);
        if (scoped == null) return problem("CONTEXT_STALE");
        scoped.keySet().stream().sorted().forEach(allowed::add);
        return json(response);
    }

    private record ValidatedContext(JsonNode context, String assetsBody, ArrayNode sourceVector, String formulaSetSha256) {}

    private ValidatedContext validatePackage(SrppContextPackage pkg, long ownerId) {
        try {
            JsonNode context = SrppJcs.parseStrict(pkg.getContextJcs());
            if (!pkg.getPackageId().toString().equals(context.path("packageId").asText())
                    || !pkg.getTradingDate().toString().equals(context.path("tradingDate").asText())
                    || !pkg.getSlot().equals(context.path("slot").asText())
                    || !pkg.getPolicyBundleSha256().equals(context.path("policy").path("policyBundleSha256").asText())) return null;
            ArrayNode vector = F.arrayNode();
            String assetsBody = null;
            for (JsonNode source : context.path("sources")) {
                String sourceId = source.path("sourceId").asText(null);
                if (sourceId == null || !"AVAILABLE".equals(source.path("state").asText())) return null;
                Optional<String> body = evidence.findBody(pkg.getPackageId(), sourceId, ownerId);
                if (body.isEmpty() || !SrppJcs.sha256Hex(body.get()).equals(source.path("bodySha256").asText())) return null;
                if ("assets".equals(sourceId)) assetsBody = body.get();
                ObjectNode revision = F.objectNode();
                revision.put("sourceId", sourceId);
                revision.put("revision", source.path("revision").asText());
                revision.put("dataAsOf", source.path("dataAsOf").asText());
                revision.put("bodySha256", source.path("bodySha256").asText());
                vector.add(revision);
            }
            if (assetsBody == null || vector.isEmpty()) return null;
            Optional<SupportedPolicy> policy = policies.find(pkg.getPolicyBundleSha256());
            if (policy.isEmpty()
                    || !policy.get().formulaVersion().equals(context.path("policy").path("formulaVersion").asText())
                    || !policy.get().formulaSetSha256().equals(context.path("policy").path("formulaSetSha256").asText())) return null;
            return new ValidatedContext(context, assetsBody, vector, policy.get().formulaSetSha256());
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private Optional<SrppContextPackage> ownedPackage(String contextId) {
        if (!currentUser.hasUser()) return Optional.empty();
        return packages.findByPackageIdAndOwner(UUID.fromString(contextId), currentUser.getEffectiveUserId());
    }

    private static ObjectNode common(SrppContextPackage pkg, ArrayNode sourceVector) {
        ObjectNode response = F.objectNode();
        response.put("schemaVersion", "1.0");
        response.put("contextId", pkg.getPackageId().toString().toLowerCase());
        response.put("tradingDate", pkg.getTradingDate().toString());
        response.put("slot", pkg.getSlot());
        response.put("policyBundleSha256", pkg.getPolicyBundleSha256());
        response.set("sourceVector", sourceVector.deepCopy());
        return response;
    }

    static ObjectNode coverage(int requested, int success, int partial, int unavailable) {
        ObjectNode coverage = F.objectNode();
        coverage.put("status", success == requested ? "COMPLETE" : "PARTIAL");
        coverage.put("requestedCount", requested);
        coverage.put("successCount", success);
        coverage.put("partialCount", partial);
        coverage.put("unavailableCount", unavailable);
        return coverage;
    }

    static String marketSymbolStatus(String quoteStatus, boolean radarAvailable, Set<String> include) {
        List<String> requestedStatuses = new java.util.ArrayList<>(2);
        if (include.contains("quote")) requestedStatuses.add(switch (quoteStatus) {
            case "LIVE", "CLOSE_FALLBACK" -> "COMPLETE";
            case "STALE" -> "PARTIAL";
            default -> "UNAVAILABLE";
        });
        if (include.contains("radar")) requestedStatuses.add(radarAvailable ? "COMPLETE" : "UNAVAILABLE");
        if (requestedStatuses.isEmpty()) throw new IllegalArgumentException("INCLUDE_REQUIRED");
        if (requestedStatuses.stream().allMatch("COMPLETE"::equals)) return "COMPLETE";
        if (requestedStatuses.stream().allMatch("UNAVAILABLE"::equals)) return "UNAVAILABLE";
        return "PARTIAL";
    }

    static String marketCaptureId(ObjectNode capture) {
        ObjectNode identity = capture.deepCopy();
        identity.remove("marketCaptureId");
        identity.remove("contextContentSha256");
        String fingerprint = SrppJcs.hash(identity);
        return UUID.nameUUIDFromBytes(fingerprint.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .toString().toLowerCase();
    }

    private static ObjectNode radarFacts(TradingRadarDto.StockDecision stock) {
        ObjectNode out = F.objectNode();
        putDecimal(out, "changePercent", stock.changePercent());
        putDecimal(out, "weeklyMa", stock.weeklyMa());
        putDecimal(out, "monthlyMa", stock.monthlyMa());
        putDecimal(out, "quarterlyMa", stock.quarterlyMa());
        putDecimal(out, "annualMa", stock.annualMa());
        putDecimal(out, "kValue", stock.kValue());
        putDecimal(out, "dValue", stock.dValue());
        putNullable(out, "asOfDate", stock.asOfDate());
        return out;
    }

    private ArrayNode marketVector(Map<String, ObjectNode> quoteProjection, String quoteAsOf,
                                   Map<String, ObjectNode> radarProjection, String radarAsOf) {
        ArrayNode result = F.arrayNode();
        addMarketRevision(result, "CANONICAL_MARKET_READ", quoteProjection, quoteAsOf);
        addMarketRevision(result, "TRADING_RADAR_SNAPSHOT", radarProjection, radarAsOf);
        return result;
    }

    private void addMarketRevision(ArrayNode vector, String sourceId, Map<String, ObjectNode> projection, String dataAsOf) {
        if (!projection.isEmpty() && dataAsOf != null) {
            ArrayNode body = F.arrayNode();
            projection.values().forEach(item -> body.add(item));
            String digest = SrppJcs.hash(body);
            ObjectNode revision = F.objectNode();
            revision.put("sourceId", sourceId);
            revision.put("revision", digest);
            revision.put("dataAsOf", dataAsOf);
            revision.put("bodySha256", digest);
            vector.add(revision);
        }
    }

    private static ArrayNode copyArray(JsonNode node) {
        ArrayNode out = F.arrayNode();
        if (node != null && node.isArray()) node.forEach(out::add);
        return out;
    }

    private static void putDecimal(ObjectNode target, String key, java.math.BigDecimal value) {
        if (value == null) target.putNull(key); else target.put(key, SrppDecimal.format(value));
    }

    private static void putNullable(ObjectNode target, String key, String value) {
        if (value == null) target.putNull(key); else target.put(key, value);
    }

    private static void reasons(ObjectNode target, List<String> values) {
        ArrayNode array = target.putArray("reasonCodes");
        values.forEach(array::add);
    }

    private static void setContentHash(ObjectNode response) {
        response.remove("contextContentSha256");
        response.put("contextContentSha256", SrppJcs.hash(response));
    }

    private SrppReadResult json(ObjectNode response) {
        setContentHash(response);
        return SrppReadResult.ok(SrppJcs.canonicalize(response));
    }

    private static SrppReadResult problem(String code) { return SrppReadResult.problem(code); }
    record QuoteFact(String status, String dataAsOf) {}

    static QuoteFact quoteFact(PriceQueryService.LivePrice quote, String market,
                               MarketDataService marketData, Clock clock) {
        QuoteFact unavailable = new QuoteFact("UNAVAILABLE", null);
        if (quote == null || quote.price() == null || quote.price().signum() <= 0
                || quote.quoteStatus() == null || quote.source() == null || quote.source().isBlank()
                || !QUOTE_MARKETS.contains(market)
                || !market.equals(quote.market())) return unavailable;
        String sourceDate = sourceDate(quote.tradingDate());
        if (sourceDate == null) return unavailable;
        LocalDate tradingDate = LocalDate.parse(sourceDate);
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(MarketZones.resolve(market)));
        if (tradingDate.isAfter(now.toLocalDate())) return unavailable;
        if (Set.of("CLOSE_FALLBACK", "CLOSE", "VERIFIED_CLOSE", "PREVIOUS_CLOSE")
                .contains(quote.quoteStatus())) {
            boolean trustedClose = !"台股".equals(market) || TRUSTED_TW_CLOSE_SOURCES.contains(quote.source());
            return Boolean.TRUE.equals(quote.closed()) && trustedClose
                    ? new QuoteFact("CLOSE_FALLBACK", sourceDate) : unavailable;
        }
        if (!"LIVE".equals(quote.quoteStatus()) && !"STALE".equals(quote.quoteStatus())) return unavailable;
        OffsetDateTime observed = sourceTime(quote.updatedAt());
        if (observed == null) return unavailable;
        Instant observedAt = observed.toInstant();
        ZonedDateTime observedLocal = observedAt.atZone(MarketZones.resolve(market));
        if (!tradingDate.equals(observedLocal.toLocalDate()) || observedAt.isAfter(clock.instant())) return unavailable;
        String asOf = observed.toString();
        if (!"LIVE".equals(quote.quoteStatus()) || !Boolean.FALSE.equals(quote.closed())) {
            return new QuoteFact("STALE", asOf);
        }
        LocalTime open = MarketZones.openTime(market);
        LocalTime close = MarketZones.closeTime(market);
        boolean inSession = !now.toLocalTime().isBefore(open) && now.toLocalTime().isBefore(close)
                && !observedLocal.toLocalTime().isBefore(open) && observedLocal.toLocalTime().isBefore(close);
        if (!tradingDate.equals(now.toLocalDate()) || !inSession
                || Duration.between(observedAt, clock.instant()).compareTo(LIVE_MAX_AGE) > 0) {
            return new QuoteFact("STALE", asOf);
        }
        try {
            Optional<Boolean> known = marketData.isTradingDayCachedOnly(market, tradingDate);
            return known != null && known.orElse(false)
                    ? new QuoteFact("LIVE", asOf) : new QuoteFact("STALE", asOf);
        } catch (RuntimeException unknownCalendar) {
            return new QuoteFact("STALE", asOf);
        }
    }

    private static OffsetDateTime sourceTime(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) return null;
        try { return OffsetDateTime.parse(timestamp); }
        catch (RuntimeException noOffset) {
            try { return LocalDateTime.parse(timestamp).atZone(TAIPEI).toOffsetDateTime(); }
            catch (RuntimeException malformed) { return null; }
        }
    }

    private static String sourceDate(String date) {
        if (date == null || !date.matches("\\d{4}-\\d{2}-\\d{2}")) return null;
        try { return LocalDate.parse(date).toString(); }
        catch (RuntimeException malformed) { return null; }
    }

    static String earliestAsOf(String previous, String candidate) {
        if (candidate == null) return previous;
        if (previous == null) return candidate;
        boolean previousDate = previous.length() == 10;
        boolean candidateDate = candidate.length() == 10;
        if (previousDate != candidateDate) {
            String previousDay = previousDate ? previous : OffsetDateTime.parse(previous).toLocalDate().toString();
            String candidateDay = candidateDate ? candidate : OffsetDateTime.parse(candidate).toLocalDate().toString();
            return previousDay.compareTo(candidateDay) <= 0 ? previousDay : candidateDay;
        }
        if (previousDate) return previous.compareTo(candidate) <= 0 ? previous : candidate;
        return OffsetDateTime.parse(previous).toInstant().isBefore(OffsetDateTime.parse(candidate).toInstant())
                ? previous : candidate;
    }

    private static boolean validCalculationIds(List<String> ids) {
        return ids != null && !ids.isEmpty() && ids.size() <= 6 && new LinkedHashSet<>(ids).size() == ids.size()
                && CALCULATION_IDS.containsAll(ids);
    }

    private static boolean validStockCodes(List<String> codes) {
        return codes == null || (codes.size() <= 100 && new LinkedHashSet<>(codes).size() == codes.size()
                && codes.stream().allMatch(code -> code != null && code.matches("[A-Za-z0-9.\\-]{1,12}")));
    }

    private static boolean allowed(String body, List<String> codes) { return allowedMarkets(body, codes) != null; }

    private static Map<String, String> allowedMarkets(String body, List<String> requested) {
        try {
            JsonNode parsed = SrppJcs.parseStrict(body);
            Map<String, Set<String>> matches = new TreeMap<>();
            for (JsonNode stock : parsed.path("snapshot").path("stocks")) {
                String code = stock.path("stockCode").asText(null);
                String market = stock.path("market").asText(null);
                if (code != null && market != null) matches.computeIfAbsent(code, ignored -> new LinkedHashSet<>()).add(market);
            }
            Map<String, String> exact = new TreeMap<>();
            matches.forEach((code, markets) -> { if (markets.size() == 1) exact.put(code, markets.iterator().next()); });
            if (requested == null) return exact;
            Map<String, String> result = new LinkedHashMap<>();
            for (String code : requested) {
                String market = exact.get(code);
                if (market == null) return null;
                result.put(code, market);
            }
            return result;
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static boolean validContextId(String value) { return value != null && UUID_LOWER.matcher(value).matches(); }
    private static boolean validHash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private static boolean validSlot(String value) { return "09:05".equals(value) || "11:40".equals(value); }
    private static LocalDate parseDate(String value) {
        try { return value != null && value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}") ? LocalDate.parse(value) : null; }
        catch (RuntimeException invalid) { return null; }
    }
}
