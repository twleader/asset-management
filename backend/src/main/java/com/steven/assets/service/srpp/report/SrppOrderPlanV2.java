package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.dto.QuoteDetailDto;
import com.steven.assets.service.PriceQueryService;
import com.steven.assets.service.srpp.decision.*;
import com.steven.assets.service.srpp.decision.DecisionInputCapturePort.Frozen;
import com.steven.assets.srpp.SrppJcs;
import com.steven.assets.srpp.SrppTime;
import com.steven.assets.srpp.SupportedPolicy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;

import static com.steven.assets.service.srpp.decision.DecisionFacts.*;

/**
 * Task 626 order-plan v2: after the LLM verdicts, mechanically apply the consumer-declared risk mode, the D-230
 * quote refresh of pure quote-gap zero legs, the D-175 final quote age, and the per-currency D-152 pooled headroom.
 * Still a report recommendation only: it never authorizes, places or prepares an order. Its only IO is the
 * {@link MarketReads} port (cached read-only quote/NAV/persisted best-five and the persisted Fubon capture).
 */
final class SrppOrderPlanV2 {
    static final String VERSION = SrppProjectionRequest.V2;
    static final Set<String> EVIDENCE_GAPS = Set.of("D230_FRESH_QUOTE_REQUIRED", "QUERY_DEADLINE_PASSED", "LIVE_QUOTE_STALE", "POOLED_HEADROOM_UNAVAILABLE");
    static final Set<String> REDECIDABLE = Set.of("OFFICIAL_SHARED_PENDING_OR_RESERVATION_SOURCE_UNAVAILABLE", "CURRENT_REPORT_SHARED_FUNDS_EXHAUSTED");
    static final int MAX_READ_SYMBOLS = 25;
    static final Duration QUERY_WINDOW = Duration.ofMinutes(35), START_LIMIT = Duration.ofMinutes(60);
    static final String BOND = "BOND_D130_D195", PACE = "GLOBAL_EQUITY_PACE", STRATEGIC = "STRATEGIC_FUBON", EMERGENCY = "EMERGENCY_CATHAY";
    /** Every BUY strategy a FINAL package can publish, each bound to its original calculator's own fee rule. */
    static final Set<String> FEE_RULE_STRATEGIES = Set.of(STRATEGIC, EMERGENCY, "DIVIDEND_TOPUP", SrppReportMediumDipCalculator.STRATEGY,
            SrppReportMediumDipCalculator.PLAN_STRATEGY, SrppReportModuleCalculator.SATELLITE_009826_STRATEGY, SrppReportModuleCalculator.SATELLITE_VT_STRATEGY,
            "SHORT_TERM_D173", "BOND_SHORT_TERM_D185");
    private static final Set<String> EQUITY_STRATEGIES = Set.of("DIVIDEND_TOPUP", SrppReportMediumDipCalculator.STRATEGY, SrppReportMediumDipCalculator.PLAN_STRATEGY,
            SrppReportModuleCalculator.SATELLITE_009826_STRATEGY, SrppReportModuleCalculator.SATELLITE_VT_STRATEGY, "SHORT_TERM_D173");
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final BigDecimal THOUSAND = new BigDecimal("1000");

    private SrppOrderPlanV2() {}

    /** One read-only cached read of a Taiwan symbol. {@code failed} means the live quote itself could not be read. */
    record Read(String code, PriceQueryService.LivePrice quote, PriceQueryService.EtfNav nav, QuoteDetailDto.Response book, boolean failed) {}

    /** The only IO v2 may perform: cached live quotes, NAV, persisted best five and the persisted Fubon capture. */
    interface MarketReads {
        Map<String, Read> taiwan(List<String> codes);
        SrppReportFubonCapturePort.Capture fubon(long owner, LocalDate date, Instant at);
    }

    record Thresholds(int category, int total, int minimum, int cautious, String rubric, int categories, int maximaSum, int maximaMax) {}

    record Context(SrppProjectionService.Loaded loaded, Frozen frozen, Policy policy, JsonNode model, SupportedPolicy registered,
                   SrppProjectionRequest.OrderV2 request, Instant now, Clock clock, MarketReads reads, ObjectMapper mapper, long owner) {}

    /** A sealed refresh capture of one market body subset; reused within one request so each symbol is read at most once. */
    record Capture(String captureId, String sourceId, Instant capturedAt, String sha256, ObjectNode body) {}

    /** D-169 thresholds and category domain from the registered bundle; any missing or mistyped value is 409. */
    static Thresholds thresholds(JsonNode model) {
        JsonNode e = model.path("execution_policy"), rule = e.path("risk_score_decision_policy"), categories = e.path("risk_score_categories"), maxima = e.path("risk_score_category_maxima");
        int category = intValue(rule, "defensive_category_score"), total = intValue(rule, "defensive_total_score"), minimum = intValue(rule, "minimum_evaluated_categories"), cautious = intValue(rule, "cautious_total_score");
        JsonNode rubric = rule.get("rubric_schema");
        if (rubric == null || !rubric.isTextual() || rubric.textValue().isBlank() || !categories.isObject() || categories.isEmpty() || !maxima.isObject() || maxima.isEmpty() || maxima.size() != categories.size())
            throw new SrppReportProblem("POLICY_UNSUPPORTED");
        int sum = 0, max = 0;
        for (Iterator<String> it = maxima.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            if (!categories.has(key)) throw new SrppReportProblem("POLICY_UNSUPPORTED");
            int value = intValue(maxima, key);
            sum += value; max = Math.max(max, value);
        }
        return new Thresholds(category, total, minimum, cautious, rubric.textValue(), categories.size(), sum, max);
    }

    private static int intValue(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) throw new SrppReportProblem("POLICY_UNSUPPORTED");
        return value.intValue();
    }

    /** SRPP risk_evidence_evaluator.classify_risk_mode replayed from (max category score, total, assessed count). */
    static String classify(int maxCategory, int total, int assessed, Thresholds t) {
        if (maxCategory >= t.category() || total >= t.total() || assessed < t.minimum()) return "DEFENSIVE";
        return total >= t.cautious() ? "CAUTIOUS" : "NORMAL";
    }

    /** SRPP trade_blocker_policy.assess instrument class: a Taiwan code ending in B, or SGOV. */
    static boolean bondType(String market, String symbol) {
        return "SGOV".equals(symbol) || ("台股".equals(market) || "TW".equals(market)) && symbol.endsWith("B");
    }

    /** The original calculator's own fee for n units at the leg's limit; never a proportional fee. */
    static BigDecimal fee(String strategy, long n, BigDecimal limit, Policy p, JsonNode model) {
        if (!FEE_RULE_STRATEGIES.contains(strategy)) throw new IllegalStateException("FEE_RULE_UNMAPPED:" + strategy);
        if (n <= 0) return null;
        BigDecimal units = BigDecimal.valueOf(n), amount;
        switch (strategy) {
            case STRATEGIC, EMERGENCY -> { amount = units.multiply(THOUSAND).multiply(limit); return DailyDecisionCalculator.fee(p, amount); }
            case "DIVIDEND_TOPUP" -> { amount = units.multiply(THOUSAND).multiply(limit); return SrppReportTopupSizing.fee(amount, p.feeRate()); }
            case SrppReportMediumDipCalculator.STRATEGY, SrppReportMediumDipCalculator.PLAN_STRATEGY -> { amount = units.multiply(THOUSAND).multiply(limit); return SrppReportMediumDipCalculator.fee(p, amount); }
            case SrppReportModuleCalculator.SATELLITE_009826_STRATEGY -> { amount = units.multiply(THOUSAND).multiply(limit); return SrppReportModuleCalculator.satelliteLotFee(p, amount); }
            case SrppReportModuleCalculator.SATELLITE_VT_STRATEGY -> { return SrppReportModuleCalculator.VT_FEE_USD; }
            default -> { return SrppReportShortUsCalculator.buyFriction(limit, n, shortCosts(model, strategy)); }
        }
    }

    private static JsonNode shortCosts(JsonNode model, String strategy) {
        for (String key : List.of("short_term_opportunity_d171", "bond_short_term_opportunity_d185")) {
            JsonNode policy = model.path("execution_policy").path(key);
            if (strategy.equals(policy.path("ledger").path("strategy").asText())) return policy.path("scenario_costs");
        }
        throw new IllegalStateException("FEE_RULE_UNMAPPED:" + strategy);
    }

    /** Whole-share unit of one leg quantity: Taiwan lots are 1000 shares, unit legs are single shares. */
    private static BigDecimal unit(L leg) { return leg.lotsLeg ? THOUSAND : BigDecimal.ONE; }

    // ------------------------------------------------------------------------------------------------------------
    private static final class L {
        String id, module, action, market, symbol, strategy, currency, verdict, packageStatus;
        int index; JsonNode node; SrppProjectionRequest.Decision decision;
        boolean candidate, redecided, riskAffected, lotsLeg, unitAvailable;
        long base, q, rerunLots = -1, refreshCap = -1;
        BigDecimal limit, spot;
        final LinkedHashSet<String> reasons = new LinkedHashSet<>();
        final List<Capture> captures = new ArrayList<>();
        ObjectNode freshness;
        Long finalQty; BigDecimal finalLimit, finalAmount, finalFees, cost;
        boolean pending; // still needs refresh/freshness evaluation
    }

    static ObjectNode plan(Context c) {
        SrppProjectionRequest.OrderV2 q = c.request();
        SrppProjectionRequest.Order order = q.order();
        JsonNode root = c.loaded().root(), input = c.loaded().input();
        Thresholds t = thresholds(c.model());
        if (q.riskTotalScore() > t.maximaSum() || q.riskAssessedCount() > t.categories() || q.riskMaxCategoryScore() > t.maximaMax() || q.riskMaxCategoryScore() > q.riskTotalScore())
            throw new SrppReportProblem("INVALID_REQUEST");
        if (!classify(q.riskMaxCategoryScore(), q.riskTotalScore(), q.riskAssessedCount(), t).equals(q.riskMode())) throw new SrppReportProblem("INVALID_REQUEST");
        LocalDate date = order.common().date();
        Instant slotStart = LocalDateTime.of(date, LocalTime.parse(order.common().slot())).atZone(SrppTime.TW_ZONE).toInstant();
        Instant run = q.runStartedAt(), asOf = order.asOf(), now = c.now();
        if (run.isBefore(slotStart) || run.isAfter(asOf) || asOf.isAfter(now) || run.isAfter(slotStart.plus(START_LIMIT)) || asOf.isBefore(c.loaded().capturedAt()))
            throw new SrppReportProblem("INVALID_REQUEST");
        Instant deadline = run.plus(QUERY_WINDOW);
        if (!c.policy().bundleHash().equals(order.common().policy())) throw new SrppReportProblem("POLICY_UNSUPPORTED");
        boolean afterClose = "AFTER_CLOSE_INITIALIZATION".equals(input.path("sources").path("publication-context").path("mode").asText());

        List<L> legs = legs(root, afterClose);
        Map<String, SrppProjectionRequest.Decision> decisions = new LinkedHashMap<>();
        int previous = -1;
        for (var d : order.decisions()) {
            int position = -1;
            for (int i = 0; i < legs.size(); i++) if (legs.get(i).id.equals(d.legId())) { position = i; break; }
            if (position < 0 || position <= previous || decisions.put(d.legId(), d) != null) throw new SrppReportProblem("INVALID_REQUEST");
            previous = position;
        }
        Map<String, JsonNode> modules = new LinkedHashMap<>();
        for (JsonNode m : root.path("modules")) modules.put(m.path("moduleId").asText(), m);
        Map<String, Capture> captures = new TreeMap<>();
        IoBudget io = new IoBudget(c, deadline, captures);

        // Step 1 + 2: verdict and risk; package-only facts, no IO.
        Map<String, JsonNode> preCapacity = null;
        for (L leg : legs) {
            leg.decision = decisions.get(leg.id);
            leg.verdict = leg.decision == null ? "NOT_DECIDED" : leg.decision.verdict();
            if (leg.redecided) {
                if (preCapacity == null) preCapacity = preCapacity(c);
                redecide(leg, preCapacity);
            }
            if (!leg.candidate && !leg.redecided && "BUY".equals(leg.action) && leg.base == 0)
                stringsOf(leg.node.path("blockingReasons")).forEach(leg.reasons::add);
            verdict(leg);
            if ("BUY".equals(leg.action)) {
                if (afterClose) { leg.reasons.add(SrppReportModuleCalculator.AFTER_CLOSE_NON_EXECUTABLE); leg.q = 0; leg.pending = false; }
                if ("DEFENSIVE".equals(q.riskMode()) && leg.riskAffected) { leg.reasons.add("RISK_MODE_DEFENSIVE"); leg.q = 0; leg.pending = false; }
            }
        }
        // Step 3: D-230 refresh, one universe read per module, only for candidates that can still be positive.
        for (String moduleId : List.of(BOND, PACE)) {
            List<L> wanted = legs.stream().filter(l -> l.candidate && l.pending && moduleId.equals(l.module)).toList();
            if (wanted.isEmpty()) continue;
            refresh(c, moduleId, wanted, legs, io, deadline);
        }
        // Step 4: D-175 final age for every other positive BUY leg.
        for (L leg : legs) if ("BUY".equals(leg.action) && !leg.candidate && leg.q > 0) freshness(c, leg, io, deadline, asOf);
        for (L leg : legs) if ("CAUTIOUS".equals(q.riskMode()) && leg.riskAffected && leg.q > 0) leg.reasons.add("RISK_MODE_CAUTIOUS_DISCLOSED");
        // Step 5: per-currency pooled headroom and calculator day/week ceilings, fixed package order.
        ObjectNode funding = capacity(c, legs, modules, q);
        // Step 6: invariants and response.
        ObjectNode out = F.objectNode();
        out.put("schemaVersion", "2");
        out.put("projectionVersion", VERSION);
        out.set("identity", root.path("identity").deepCopy());
        SrppProjectionService.putBase(out, c.loaded());
        out.put("asOf", asOf.toString());
        out.put("runStartedAt", run.toString());
        out.put("queryDeadline", deadline.toString());
        ArrayNode normalized = out.putArray("normalizedDecisions");
        for (var d : order.decisions()) normalized.add(SrppProjectionService.decision(d));
        ObjectNode risk = out.putObject("riskMode");
        risk.put("mode", q.riskMode()); risk.put("status", "CONSUMER_DECLARED"); risk.put("totalScore", q.riskTotalScore()); risk.put("assessedCount", q.riskAssessedCount());
        risk.put("maxCategoryScore", q.riskMaxCategoryScore()); risk.put("receiptSha256", q.riskReceiptSha256()); risk.put("rubricSchema", t.rubric());
        ObjectNode th = risk.putObject("thresholds");
        th.put("defensiveCategoryScore", t.category()); th.put("defensiveTotalScore", t.total()); th.put("minimumEvaluatedCategories", t.minimum()); th.put("cautiousTotalScore", t.cautious());
        ObjectNode commitments = out.putObject("fundingCommitments");
        commitments.put("TWD", q.commitmentTwdText()); commitments.put("USD", q.commitmentUsdText());
        out.set("funding", funding);
        ArrayNode refreshCaptures = out.putArray("refreshCaptures");
        for (Capture capture : captures.values()) {
            ObjectNode n = refreshCaptures.addObject();
            n.put("captureId", capture.captureId()); n.put("sourceId", capture.sourceId()); n.put("capturedAt", capture.capturedAt().toString()); n.put("sha256", capture.sha256()); n.set("body", capture.body().deepCopy());
        }
        ArrayNode projected = out.putArray("legs");
        int complete = 0, unavailable = 0, notEvaluated = 0;
        for (L leg : legs) {
            invariants(leg);
            projected.add(project(c, leg));
            switch (outcome(leg.verdict, leg.reasons, leg.finalQty)) {
                case "COMPLETE" -> complete++;
                case "UNAVAILABLE" -> unavailable++;
                default -> notEvaluated++;
            }
        }
        ObjectNode coverage = out.putObject("coverage");
        coverage.put("requestedCount", legs.size()); coverage.put("completeCount", complete); coverage.put("partialCount", 0); coverage.put("unavailableCount", unavailable); coverage.put("notEvaluatedCount", notEvaluated);
        out.put("executionScope", "REPORT_RECOMMENDATION_ONLY");
        out.put("tradeAuthorization", false);
        out.put("placesOrders", false);
        out.putArray("receiptRefs");
        out.put("planId", planId(out));
        out.put("contentSha256", SrppJcs.sha256Hex(SrppJcs.canonicalize(out)));
        return out;
    }

    /** Task 626.15 content address: changes whenever any sealed refresh capture changes. */
    static String planId(JsonNode out) {
        ObjectNode address = F.objectNode();
        for (String key : List.of("projectionVersion", "identity", "base", "asOf", "runStartedAt", "normalizedDecisions", "riskMode", "fundingCommitments"))
            address.set(key, out.path(key).deepCopy());
        ArrayNode refs = address.putArray("refreshCaptures");
        for (JsonNode capture : out.path("refreshCaptures")) {
            ObjectNode ref = refs.addObject();
            ref.set("sourceId", capture.path("sourceId").deepCopy()); ref.set("sha256", capture.path("sha256").deepCopy()); ref.set("capturedAt", capture.path("capturedAt").deepCopy());
        }
        return SrppJcs.sha256Hex(SrppJcs.canonicalize(address));
    }

    /** Task 626.16 per-leg outcome, the exact rule the BFF re-derives. */
    static String outcome(String verdict, Collection<String> reasons, Long finalQty) {
        if (Set.of("WAIT", "VETO", "NOT_DECIDED").contains(verdict) || reasons.contains("REDUCE_BASE_UNIT_UNAVAILABLE")) return "NOT_EVALUATED";
        if (finalQty != null && finalQty == 0 && reasons.stream().anyMatch(EVIDENCE_GAPS::contains)) return "UNAVAILABLE";
        return "COMPLETE";
    }

    // ------------------------------------------------------------------------------------------------------------
    private static List<L> legs(JsonNode root, boolean afterClose) {
        List<L> out = new ArrayList<>();
        JsonNode modules = root.path("modules");
        if (!modules.isArray()) throw new SrppReportProblem("UPSTREAM_INVALID");
        for (JsonNode m : modules) {
            String module = SrppProjectionService.text(m, "moduleId");
            JsonNode rows = m.path("data").path("legs");
            if (!rows.isArray()) throw new SrppReportProblem("UPSTREAM_INVALID");
            for (int i = 0; i < rows.size(); i++) {
                JsonNode node = rows.get(i);
                String action = SrppProjectionService.text(node, "action"), market = SrppProjectionService.text(node, "market");
                if (!Set.of("BUY", "SELL", "NONE").contains(action) || !Set.of("台股", "美股").contains(market)) throw new SrppReportProblem("UPSTREAM_INVALID");
                boolean candidate = !afterClose && candidate(module, node);
                if ("NONE".equals(action) && !candidate) continue;
                L leg = new L();
                leg.id = module + "." + i; leg.module = module; leg.index = i; leg.node = node; leg.market = market;
                leg.action = candidate ? "BUY" : action; leg.symbol = SrppProjectionService.text(node, "symbol");
                leg.strategy = node.path("strategy").isTextual() ? node.path("strategy").textValue() : null;
                leg.currency = node.path("currency").isTextual() ? node.path("currency").textValue() : ("台股".equals(market) ? "TWD" : "USD");
                leg.packageStatus = node.path("status").asText();
                leg.candidate = candidate;
                JsonNode lots = SrppProjectionService.baseLots(node), units = SrppProjectionService.baseUnits(node);
                leg.unitAvailable = lots.isNull() != units.isNull() && (units.isNull() || new BigDecimal(units.textValue()).stripTrailingZeros().scale() <= 0);
                leg.lotsLeg = !lots.isNull();
                leg.base = !leg.unitAvailable ? -1 : leg.lotsLeg ? lots.longValue() : new BigDecimal(units.textValue()).longValueExact();
                leg.limit = decimal(node.path("limitPrice"));
                leg.redecided = !afterClose && "BUY".equals(action) && "BLOCKED".equals(leg.packageStatus) && leg.base == 0 && REDECIDABLE.contains(single(node.path("blockingReasons")));
                if ("BUY".equals(leg.action)) {
                    boolean bond = bondType(market, leg.symbol);
                    if ((BOND.equals(module) || "BOND_SHORT_TERM_D185".equals(leg.strategy)) && !bond || EQUITY_STRATEGIES.contains(leg.strategy) && bond)
                        throw new SrppReportProblem("UPSTREAM_INVALID");
                    leg.riskAffected = !bond;
                }
                if (SrppReportModuleCalculator.SATELLITE_VT_STRATEGY.equals(leg.strategy)) leg.spot = vtSpot(node);
                out.add(leg);
            }
        }
        return out;
    }

    /** Task 626.5 D-230 identity, the reasons being only that identity's quote capture gaps. */
    static boolean candidate(String module, JsonNode leg) {
        if (!"BLOCKED".equals(leg.path("status").asText()) || !leg.path("lots").isIntegralNumber() || leg.path("lots").longValue() != 0) return false;
        List<String> reasons = stringsOf(leg.path("blockingReasons"));
        if (reasons.isEmpty()) return false;
        String strategy = leg.path("strategy").asText();
        if (BOND.equals(module) && Set.of(STRATEGIC, EMERGENCY).contains(strategy) && "NONE".equals(leg.path("action").asText()))
            return DailyDecisionCalculator.QUOTE_REFRESH_GAPS.containsAll(reasons);
        return PACE.equals(module) && SrppReportModuleCalculator.SATELLITE_009826_STRATEGY.equals(strategy) && "009826".equals(leg.path("symbol").asText())
                && "BUY".equals(leg.path("action").asText()) && reasons.equals(List.of("QUOTE_UNVERIFIED"));
    }

    private static String single(JsonNode reasons) {
        List<String> values = stringsOf(reasons);
        return new LinkedHashSet<>(values).size() == 1 ? values.getFirst() : "";
    }

    private static BigDecimal vtSpot(JsonNode leg) {
        for (JsonNode r : leg.path("receipts")) if ("FX_CONVERSION".equals(r.path("ruleId").asText()))
            for (JsonNode v : r.path("values")) if ("spotSellingRate".equals(v.path("name").asText())) return decimal(v.path("value"));
        return null;
    }

    private static Map<String, JsonNode> preCapacity(Context c) {
        Map<String, JsonNode> out = new HashMap<>();
        try {
            JsonNode extra = c.frozen().input().path("sources");
            ArrayNode modules = SrppReportWireNormalizer.modules(SrppReportModuleCalculator.calculateSharedBeforeCapacity(c.frozen(), c.policy(), c.model(), c.registered(), extra));
            for (JsonNode m : modules) out.put(m.path("moduleId").asText(), m);
        } catch (RuntimeException unavailable) { /* not reproducible: the legs keep their package zero */ }
        return out;
    }

    /** Task 626.11: a leg zeroed only by the package shared-funds pass gets its pre-capacity quantity and price back. */
    private static void redecide(L leg, Map<String, JsonNode> preCapacity) {
        JsonNode module = preCapacity.get(leg.module), rows = module == null ? null : module.path("data").path("legs");
        JsonNode before = rows == null || leg.index >= rows.size() ? null : rows.get(leg.index);
        if (before == null || !leg.symbol.equals(before.path("symbol").asText()) || !Objects.equals(leg.strategy, before.path("strategy").asText(null))
                || !"ACTIONABLE_FOR_REPORT".equals(before.path("status").asText()) || !"BUY".equals(before.path("action").asText())) { leg.redecided = false; stringsOf(leg.node.path("blockingReasons")).forEach(leg.reasons::add); return; }
        JsonNode lots = SrppProjectionService.baseLots(before), units = SrppProjectionService.baseUnits(before);
        BigDecimal limit = decimal(before.path("limitPrice"));
        if (lots.isNull() == units.isNull() || limit == null || limit.signum() <= 0) { leg.redecided = false; stringsOf(leg.node.path("blockingReasons")).forEach(leg.reasons::add); return; }
        leg.lotsLeg = !lots.isNull();
        leg.base = leg.lotsLeg ? lots.longValue() : new BigDecimal(units.textValue()).longValueExact();
        leg.limit = limit;
        if (SrppReportModuleCalculator.SATELLITE_VT_STRATEGY.equals(leg.strategy)) leg.spot = vtSpot(before);
        leg.node = before;
    }

    /** Task 626.7 verdict quantity before any refresh. */
    private static void verdict(L leg) {
        if (!leg.unitAvailable && !leg.redecided) {
            leg.reasons.add("REDUCE_BASE_UNIT_UNAVAILABLE"); leg.finalQty = null; leg.q = 0; leg.pending = false;
            if (leg.decision != null && leg.decision.reason() != null && !"REDUCE".equals(leg.verdict)) leg.reasons.add(leg.decision.reason());
            return;
        }
        switch (leg.verdict) {
            case "NOT_DECIDED" -> { leg.reasons.add("LLM_DECISION_MISSING"); leg.q = 0; }
            case "WAIT", "VETO" -> { leg.reasons.add(leg.decision.reason()); leg.q = 0; }
            case "ADOPT" -> { leg.q = leg.base; leg.pending = true; }
            case "REDUCE" -> {
                BigDecimal amount = new BigDecimal(leg.decision.amount());
                if (amount.stripTrailingZeros().scale() > 0) { leg.reasons.add("REDUCE_AMOUNT_NOT_WHOLE_UNIT"); leg.q = 0; }
                else { leg.q = Math.min(leg.base, amount.longValueExact()); leg.pending = true; }
            }
            default -> throw new SrppReportProblem("INTERNAL_ERROR");
        }
        if (!leg.candidate) leg.pending = false;
    }

    /** Request-wide IO accounting: at most one read per symbol and 25 symbols, never after the 35-minute deadline. */
    private static final class IoBudget {
        final Context c; final Instant deadline; final Map<String, Capture> captures; final Map<String, Read> reads = new HashMap<>(); final Set<String> failed = new HashSet<>();
        IoBudget(Context c, Instant deadline, Map<String, Capture> captures) { this.c = c; this.deadline = deadline; this.captures = captures; }
        /** Reads the not-yet-read codes once; returns the latest read time of the requested codes, or null when none was read in time. */
        Instant read(List<String> codes) {
            List<String> fresh = codes.stream().filter(code -> !reads.containsKey(code) && !failed.contains(code)).distinct().toList();
            Set<String> counted = new HashSet<>(reads.keySet()); counted.addAll(failed);
            List<String> allowed = new ArrayList<>();
            for (String code : fresh) { if (counted.size() >= MAX_READ_SYMBOLS) { failed.add(code); continue; } counted.add(code); allowed.add(code); }
            if (!allowed.isEmpty()) readNow(allowed);
            Instant latest = null;
            for (String code : codes) { Capture capture = captures.get("market:台股:" + code); if (capture != null && reads.containsKey(code) && (latest == null || capture.capturedAt().isAfter(latest))) latest = capture.capturedAt(); }
            return latest;
        }
        private void readNow(List<String> allowed) {
            Map<String, Read> got;
            try { got = c.reads().taiwan(allowed); } catch (RuntimeException unavailable) { got = Map.of(); }
            Instant at = c.clock().instant();
            // Any read completing after the 35-minute deadline or outside the Taipei trading date counts as failed.
            boolean inTime = !at.isAfter(deadline) && at.atZone(SrppTime.TW_ZONE).toLocalDate().equals(c.request().order().common().date());
            for (String code : allowed) {
                Read r = got == null ? null : got.get(code);
                if (r == null || r.failed() || !inTime) { failed.add(code); continue; }
                reads.put(code, r);
                ObjectNode body = F.objectNode();
                body.put("market", "台股"); body.put("symbol", code); body.put("volumeUnit", "SHARES");
                body.set("quote", json(c.mapper(), r.quote())); body.set("nav", json(c.mapper(), r.nav())); body.set("book", json(c.mapper(), r.book()));
                String sourceId = "market:台股:" + code, sha = SrppJcs.sha256Hex(SrppJcs.canonicalize(body));
                captures.put(sourceId, new Capture("refresh:" + sourceId + ":" + sha, sourceId, at, sha, body));
            }
        }
    }

    private static JsonNode json(ObjectMapper mapper, Object value) { return value == null ? NullNode.instance : SrppReportModuleCalculator.normalize(mapper.valueToTree(value)); }

    /** Task 626.9: refresh one module universe, re-run its original calculator, take the same index/symbol/strategy leg. */
    private static void refresh(Context c, String moduleId, List<L> wanted, List<L> all, IoBudget io, Instant deadline) {
        if (c.now().isAfter(deadline)) {
            for (L leg : wanted) { stringsOf(leg.node.path("blockingReasons")).forEach(leg.reasons::add); leg.reasons.add("QUERY_DEADLINE_PASSED"); leg.q = 0; leg.pending = false; }
            return;
        }
        List<String> universe = BOND.equals(moduleId) ? c.policy().symbols() : List.of("009826");
        Instant refreshAt = io.read(universe);
        Frozen frame; ObjectNode rerun = null;
        if (refreshAt != null) {
            try {
                frame = refreshFrame(c, universe, io.reads, refreshAt);
                boolean fubon = wanted.stream().anyMatch(l -> stringsOf(l.node.path("blockingReasons")).stream().anyMatch(r -> r.startsWith("FUBON_")));
                if (fubon) {
                    SrppReportFubonCapturePort.Capture capture = c.reads().fubon(c.owner(), c.request().order().common().date(), refreshAt);
                    frame = SrppReportFubonOverlay.apply(frame, capture);
                    ObjectNode body = F.objectNode();
                    body.put("sourceId", "fubon-account"); body.put("status", capture.source().path("status").asText()); body.put("verified", capture.verified()); body.put("sourceSha256", SrppJcs.hash(capture.source()));
                    String sha = SrppJcs.sha256Hex(SrppJcs.canonicalize(body));
                    io.captures.put("fubon-account", new Capture("refresh:fubon-account:" + sha, "fubon-account", refreshAt, sha, body));
                }
                rerun = SrppReportModuleCalculator.recalculateModuleForRefresh(moduleId, frame, c.policy(), c.model(), frame.input().path("sources"));
            } catch (RuntimeException unavailable) { rerun = null; }
        }
        JsonNode rows = rerun == null ? null : SrppReportWireNormalizer.modules(F.arrayNode().add(rerun)).get(0).path("data").path("legs");
        for (L leg : wanted) {
            leg.pending = false;
            for (String code : universe) { Capture capture = io.captures.get("market:台股:" + code); if (capture != null) leg.captures.add(capture); }
            Capture fubon = io.captures.get("fubon-account"); if (fubon != null && rerun != null) leg.captures.add(fubon);
            boolean ownRead = io.reads.containsKey(leg.symbol);
            JsonNode own = null;
            if (rows != null) {
                int position = PACE.equals(moduleId) ? 0 : leg.index;
                if (position < rows.size()) own = rows.get(position);
                if (own != null && (!leg.symbol.equals(own.path("symbol").asText()) || !Objects.equals(leg.strategy, own.path("strategy").asText(null)))) own = null;
            }
            if (!ownRead || own == null) { stringsOf(leg.node.path("blockingReasons")).forEach(leg.reasons::add); leg.reasons.add("D230_FRESH_QUOTE_REQUIRED"); leg.q = 0; continue; }
            long lots = own.path("lots").isIntegralNumber() ? own.path("lots").longValue() : 0;
            if ("ACTIONABLE_FOR_REPORT".equals(own.path("status").asText()) && lots > 0) {
                long cap = PACE.equals(moduleId) ? paceCap(c, "009826") : refreshCap(c.model(), leg.strategy);
                long others = 0;
                for (L other : all) if (other != leg && sleeve(other) != null && sleeve(other).equals(sleeve(leg)) && other.q > 0) others += other.q;
                long r = Math.max(0, Math.min(lots, cap - others));
                leg.rerunLots = lots; leg.refreshCap = cap; leg.limit = decimal(own.path("limitPrice")); leg.node = own; leg.lotsLeg = true;
                if (r < lots) leg.reasons.add("SHARED_CAPACITY_REDUCED");
                leg.q = "REDUCE".equals(leg.verdict) ? Math.min(r, new BigDecimal(leg.decision.amount()).longValueExact()) : r;
            } else if (stringsOf(own.path("blockingReasons")).contains("NOT_SELECTED")) {
                stringsOf(own.path("blockingReasons")).forEach(leg.reasons::add); leg.reasons.add("REFRESH_NOT_SELECTED"); leg.q = 0; leg.rerunLots = 0;
            } else {
                stringsOf(own.path("blockingReasons")).forEach(leg.reasons::add); leg.reasons.add("D230_FRESH_QUOTE_REQUIRED"); leg.q = 0; leg.rerunLots = 0;
            }
        }
    }

    /** Refresh frame: only the module universe quote/book/NAV and their source receipts change; every other source stays frozen (D-200). */
    static Frozen refreshFrame(Context c, List<String> codes, Map<String, Read> reads, Instant refreshAt) {
        Frozen base = c.frozen();
        ObjectNode input = base.input(), vector = base.sourceVector(), sources = input.withObject("sources");
        Input old = base.facts();
        Map<String, Symbol> symbols = new TreeMap<>(old.symbols());
        Input validation = new Input(old.date(), old.slot(), refreshAt, List.of(), BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, Map.of(),
                new Budget(0, 0, 0, false, BigDecimal.ZERO, Map.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), null, null);
        for (String code : codes) {
            Read r = reads.get(code);
            if (r == null) continue;
            JsonNode rawQuote = json(c.mapper(), r.quote()), rawNav = json(c.mapper(), r.nav()), rawBook = json(c.mapper(), r.book());
            Quote quote = DecisionQuoteMapping.quote(code, r.quote(), r.nav());
            Book book = DecisionQuoteMapping.book(r.book(), code);
            Symbol s = symbols.get(code);
            if (s != null) symbols.put(code, new Symbol(s.code(), quote, book, s.closes(), s.distributions(), s.distributionVerified(), s.currentValue(), s.position(), s.pending(), s.radar(), s.valuation(), s.tradability(), s.mediumAction(), s.mediumCandidateAction(), s.intraday()));
            if (sources.has("quote:" + code)) {
                ObjectNode q = F.objectNode();
                q.set("rawQuote", rawQuote); q.set("rawNav", rawNav); q.put("rawVolumeUnit", "SHARES"); q.put("normalizedVolumeUnit", "LOTS"); q.set("normalized", json(c.mapper(), quote));
                sources.set("quote:" + code, q);
                receipt(vector, "quote:" + code, q, refreshAt, quote == null ? "NONE" : "COMPLETE", quote != null && DailyDecisionCalculator.quoteQualified(c.policy(), validation, quote) ? "VERIFIED" : "UNAVAILABLE");
            }
            if (sources.has("book:" + code)) {
                sources.set("book:" + code, rawBook);
                receipt(vector, "book:" + code, rawBook, refreshAt, r.book() == null ? "NONE" : "COMPLETE", DailyDecisionCalculator.bookQualified(c.policy(), validation, book) ? "VERIFIED" : "UNAVAILABLE");
            }
            String id = "market:台股:" + code;
            ObjectNode body = sources.path(id).isObject() ? (ObjectNode) sources.path(id).deepCopy() : F.objectNode().put("market", "台股").put("symbol", code).put("volumeUnit", "SHARES");
            body.set("quote", rawQuote); body.set("nav", rawNav); body.set("book", rawBook);
            sources.set(id, body);
            SrppReportExtraCaptureAdapter.SourceQuality quality = SrppReportExtraCaptureAdapter.assess(body, old.date(), refreshAt);
            receipt(vector, id, body, refreshAt, quality.coverage(), quality.validation());
        }
        Input facts = new Input(old.date(), old.slot(), refreshAt, old.sessions(), old.totalAssets(), old.totalDeposits(), old.termDeposits(), old.bondValue(), old.fx(), old.fxEvidence(), old.bearWindow(), symbols, old.budget(), old.subaccounts(), old.emergencyValue());
        input.put("capturedAt", refreshAt.toString());
        input.set("facts", SrppReportModuleCalculator.normalize(c.mapper().valueToTree(facts)));
        return new Frozen(facts, input, vector, base.assetSnapshotId(), base.assetGeneratedAt());
    }

    private static void receipt(ObjectNode vector, String id, JsonNode body, Instant at, String coverage, String validation) {
        String hash = SrppJcs.hash(body);
        ObjectNode n = vector.putObject(id);
        n.put("sourceId", id); n.put("revision", hash); n.put("capturedAt", at.toString()); n.put("sha256", hash); n.put("coverage", coverage); n.put("validation", validation);
    }

    /** SRPP REFRESH_LOT_CAPS for the two bond identities. */
    static long refreshCap(JsonNode model, String strategy) {
        JsonNode policy = model.path("execution_policy").path("bond_price_selection_d195").path(EMERGENCY.equals(strategy) ? "emergency_max_lots_per_day" : "strategic_max_lots_per_day");
        if (!policy.isIntegralNumber() || policy.longValue() < 0) throw new SrppReportProblem("POLICY_UNSUPPORTED");
        return policy.longValue();
    }

    /** 009826: floor(min(remainingDayLots, remainingWeekLots)) from the package t623 facts, independent of the quote. */
    private static long paceCap(Context c, String code) {
        JsonNode module = null;
        for (JsonNode m : c.loaded().root().path("modules")) if (PACE.equals(m.path("moduleId").asText())) module = m;
        BigDecimal day = factValue(module, "remainingDayLots", code), week = factValue(module, "remainingWeekLots", code);
        return day == null || week == null ? 0 : day.min(week).setScale(0, RoundingMode.FLOOR).max(BigDecimal.ZERO).longValueExact();
    }

    private static BigDecimal factValue(JsonNode module, String name, String subject) {
        if (module == null) return null;
        for (JsonNode fact : module.path("data").path("facts"))
            if (name.equals(fact.path("name").asText()) && Objects.equals(subject, fact.path("subject").isNull() ? null : fact.path("subject").asText()) && "EXACT".equals(fact.path("quality").asText()))
                return decimal(fact.path("value"));
        return null;
    }

    /** Calculator day/week sleeve of a BUY leg, or null when its calculator publishes no such ceiling. */
    private static String sleeve(L leg) {
        if (!"BUY".equals(leg.action)) return null;
        if (BOND.equals(leg.module)) return STRATEGIC.equals(leg.strategy) ? "BOND:STRATEGIC" : EMERGENCY.equals(leg.strategy) ? "BOND:EMERGENCY" : null;
        if (SrppReportModuleCalculator.SATELLITE_009826_STRATEGY.equals(leg.strategy)) return "PACE:009826";
        if (SrppReportModuleCalculator.SATELLITE_VT_STRATEGY.equals(leg.strategy)) return "PACE:VT";
        if ("DIVIDEND_TOPUP".equals(leg.strategy)) return "TOPUP";
        return null;
    }

    private static long sleeveCap(Context c, String sleeve, Map<String, JsonNode> modules) {
        return switch (sleeve) {
            case "BOND:STRATEGIC" -> refreshCap(c.model(), STRATEGIC);
            case "BOND:EMERGENCY" -> refreshCap(c.model(), EMERGENCY);
            case "PACE:009826" -> paceCap(c, "009826");
            case "PACE:VT" -> floorMin(factValue(modules.get(PACE), "remainingDayShares", "VT"), factValue(modules.get(PACE), "remainingWeekShares", "VT"));
            case "TOPUP" -> floorMin(factValue(modules.get("DIVIDEND_TOPUP"), "remainingDailyLots", null), factValue(modules.get("DIVIDEND_TOPUP"), "remainingWeeklyLots", null));
            default -> Long.MAX_VALUE;
        };
    }

    private static long floorMin(BigDecimal a, BigDecimal b) { return a == null || b == null ? 0 : a.min(b).setScale(0, RoundingMode.FLOOR).max(BigDecimal.ZERO).longValueExact(); }

    /** Task 626.10: frozen D-175 final age at asOf; a stale leg is re-read once inside the deadline, limit never re-priced. */
    private static void freshness(Context c, L leg, IoBudget io, Instant deadline, Instant asOf) {
        if (!"台股".equals(leg.market)) return; // US legs keep their original calculator's receipts; no D-175 Taiwan read
        JsonNode body = c.loaded().input().path("sources").path("market:台股:" + leg.symbol);
        Fresh frozen = fresh(body, c.frozen(), c.model(), asOf);
        if (frozen.pass()) { leg.freshness = frozen.node("PACKAGE"); return; }
        if (c.now().isAfter(deadline)) { leg.freshness = frozen.node("PACKAGE"); leg.reasons.add("LIVE_QUOTE_STALE"); leg.q = 0; return; }
        Instant at = io.read(List.of(leg.symbol));
        Read read = io.reads.get(leg.symbol);
        Capture capture = io.captures.get("market:台股:" + leg.symbol);
        if (read == null || capture == null) { leg.freshness = frozen.node("PACKAGE"); leg.reasons.add("LIVE_QUOTE_STALE"); leg.q = 0; return; }
        ObjectNode refreshed = body.isObject() ? (ObjectNode) body.deepCopy() : F.objectNode().put("market", "台股").put("symbol", leg.symbol).put("volumeUnit", "SHARES");
        refreshed.set("quote", capture.body().path("quote").deepCopy()); refreshed.set("nav", capture.body().path("nav").deepCopy()); refreshed.set("book", capture.body().path("book").deepCopy());
        Fresh again = fresh(refreshed, c.frozen(), c.model(), capture.capturedAt());
        leg.captures.add(capture);
        leg.freshness = again.node("REFRESH");
        if (!again.pass()) { leg.reasons.add("LIVE_QUOTE_STALE"); leg.q = 0; }
    }

    private record Fresh(boolean pass, String status, Integer age, Integer max) {
        ObjectNode node(String source) {
            ObjectNode n = F.objectNode();
            n.put("status", status); n.put("stage", "final");
            if (age == null) n.putNull("ageMinutes"); else n.put("ageMinutes", age);
            if (max == null) n.putNull("maxAgeMinutes"); else n.put("maxAgeMinutes", max);
            n.put("source", source);
            return n;
        }
    }

    private static Fresh fresh(JsonNode body, Frozen base, JsonNode model, Instant at) {
        Input old = base.facts();
        Input facts = new Input(old.date(), old.slot(), at, old.sessions(), old.totalAssets(), old.totalDeposits(), old.termDeposits(), old.bondValue(), old.fx(), old.fxEvidence(), old.bearWindow(), old.symbols(), old.budget(), old.subaccounts(), old.emergencyValue());
        Frozen frame = new Frozen(facts, base.input(), base.sourceVector(), base.assetSnapshotId(), base.assetGeneratedAt());
        Integer age = null;
        Instant updated = instant(body.path("quote").path("updatedAt").asText(""));
        if (updated != null && !updated.isAfter(at)) age = (int) Math.min(Integer.MAX_VALUE, Duration.between(updated, at).toMinutes());
        JsonNode policy = model.path("execution_policy").path("bond_trade_price_policy").path("quote_freshness_policy");
        try {
            String status = SrppReportModuleCalculator.quoteFreshness(body, frame, model, true);
            Integer max = switch (status) {
                case "LIVE_QUOTE_FRESH" -> policy.path("default_final_limit_max_minutes").isIntegralNumber() ? policy.path("default_final_limit_max_minutes").intValue() : null;
                case "MIDDAY_VOLUME_LE_1000_20M" -> policy.path("midday_volume_at_most_1000_max_minutes").isIntegralNumber() ? policy.path("midday_volume_at_most_1000_max_minutes").intValue() : null;
                case "MIDDAY_VOLUME_1001_1500_15M" -> policy.path("midday_volume_1001_through_1500_max_minutes").isIntegralNumber() ? policy.path("midday_volume_1001_through_1500_max_minutes").intValue() : null;
                default -> null;
            };
            return new Fresh(true, status, age, max);
        } catch (RuntimeException stale) {
            return new Fresh(false, "LIVE_QUOTE_STALE", age, null);
        }
    }

    private static Instant instant(String text) {
        try { return Instant.parse(text); } catch (DateTimeException a) {
            try { return OffsetDateTime.parse(text).toInstant(); } catch (DateTimeException b) {
                try { return LocalDateTime.parse(text).atZone(SrppTime.TW_ZONE).toInstant(); } catch (DateTimeException d) { return null; }
            }
        }
    }

    /** Task 626.11 pooled headroom per currency, consumed in fixed package order with calculator fee rules. */
    private static ObjectNode capacity(Context c, List<L> legs, Map<String, JsonNode> modules, SrppProjectionRequest.OrderV2 q) {
        JsonNode funding = modules.get("FUNDING_CAPACITY");
        Map<String, BigDecimal> start = new TreeMap<>(), remaining = new TreeMap<>(), used = new TreeMap<>();
        Map<String, String> unknown = new TreeMap<>();
        for (String currency : List.of("TWD", "USD")) {
            JsonNode fact = null;
            if (funding != null) for (JsonNode f : funding.path("data").path("facts")) if ("pooledPostBuyHeadroom".equals(f.path("name").asText()) && currency.equals(f.path("subject").asText())) fact = f;
            BigDecimal headroom = fact != null && "EXACT".equals(fact.path("quality").asText()) ? decimal(fact.path("value")) : null;
            if (headroom == null) { unknown.put(currency, fact != null && "POOLED_TERM_BELOW_FLOOR".equals(fact.path("reason").asText()) ? "POOLED_TERM_BELOW_FLOOR" : "POOLED_HEADROOM_UNAVAILABLE"); continue; }
            BigDecimal reserved = factValue(funding, "reservedFunds", currency);
            BigDecimal declared = "TWD".equals(currency) ? q.commitmentTwd() : q.commitmentUsd();
            BigDecimal h = headroom.subtract(declared.max(reserved == null ? BigDecimal.ZERO : reserved));
            start.put(currency, h); remaining.put(currency, h); used.put(currency, BigDecimal.ZERO);
        }
        Map<String, Long> sleeveUsed = new HashMap<>();
        for (L leg : legs) {
            if ("SELL".equals(leg.action)) { sell(leg); continue; }
            if (leg.finalQty == null && !leg.unitAvailable && !leg.redecided) continue;
            if (leg.q <= 0) { leg.finalQty = 0L; continue; }
            if (leg.limit == null || leg.limit.signum() <= 0) throw new SrppReportProblem("UPSTREAM_INVALID");
            boolean vt = SrppReportModuleCalculator.SATELLITE_VT_STRATEGY.equals(leg.strategy);
            if (vt && (leg.spot == null || leg.spot.signum() <= 0)) throw new SrppReportProblem("UPSTREAM_INVALID");
            String pool = vt ? "TWD" : leg.currency;
            if (!Set.of("TWD", "USD").contains(pool)) throw new SrppReportProblem("UPSTREAM_INVALID");
            if (unknown.containsKey(pool)) { leg.reasons.add(unknown.get(pool)); leg.finalQty = 0L; continue; }
            long limitQ = leg.q;
            String sleeve = sleeve(leg);
            if (sleeve != null) {
                long cap = sleeveCap(c, sleeve, modules), already = sleeveUsed.getOrDefault(sleeve, 0L);
                limitQ = Math.max(0, Math.min(limitQ, cap - already));
            }
            BigDecimal room = remaining.get(pool);
            long lo = 0, hi = limitQ;
            while (lo < hi) { long mid = lo + (hi - lo + 1) / 2; if (cost(c, leg, mid).compareTo(room) <= 0) lo = mid; else hi = mid - 1; }
            long n = lo;
            if (vt) {
                BigDecimal min = factValue(modules.get(PACE), "minOrderShares", "VT");
                if (min == null || BigDecimal.valueOf(n).compareTo(min) < 0) n = 0;
            }
            if (n < leg.q) leg.reasons.add("SHARED_CAPACITY_REDUCED");
            leg.finalQty = n;
            if (n > 0) {
                BigDecimal spend = cost(c, leg, n);
                remaining.put(pool, room.subtract(spend)); used.put(pool, used.get(pool).add(spend));
                leg.cost = spend;
                leg.finalLimit = leg.limit;
                leg.finalAmount = BigDecimal.valueOf(n).multiply(unit(leg)).multiply(leg.limit);
                leg.finalFees = fee(leg.strategy, n, leg.limit, c.policy(), c.model());
                if (sleeve != null) sleeveUsed.merge(sleeve, n, Long::sum);
            }
        }
        ObjectNode out = F.objectNode();
        for (String currency : List.of("TWD", "USD")) {
            ObjectNode n = out.putObject(currency);
            ArrayNode reasons;
            if (unknown.containsKey(currency)) {
                n.putNull("startHeadroom"); n.putNull("fundingUsed"); n.putNull("fundingHeadroomAfter"); reasons = n.putArray("reasonCodes"); reasons.add(unknown.get(currency));
            } else {
                BigDecimal after = start.get(currency).subtract(used.get(currency));
                // A known negative pooled headroom admits no positive cost: nothing is used, so `after` stays the known deficit.
                if (used.get(currency).signum() > 0 && after.signum() < 0) throw new SrppReportProblem("INTERNAL_ERROR");
                n.put("startHeadroom", SrppReportModuleCalculator.dec(start.get(currency))); n.put("fundingUsed", SrppReportModuleCalculator.dec(used.get(currency)));
                n.put("fundingHeadroomAfter", SrppReportModuleCalculator.dec(after)); n.putArray("reasonCodes");
            }
        }
        return out;
    }

    /** Cost of n units: amount + fee, or for VT the Cathay TWD sub-brokerage debit n x limitUsd x spot + 3 x spot. */
    static BigDecimal cost(Context c, L leg, long n) {
        if (n <= 0) return BigDecimal.ZERO;
        BigDecimal fee = fee(leg.strategy, n, leg.limit, c.policy(), c.model());
        if (SrppReportModuleCalculator.SATELLITE_VT_STRATEGY.equals(leg.strategy)) return BigDecimal.valueOf(n).multiply(leg.limit).multiply(leg.spot).add(fee.multiply(leg.spot));
        return BigDecimal.valueOf(n).multiply(unit(leg)).multiply(leg.limit).add(fee);
    }

    /** SELL legs only project the frozen base: never read, never charge capacity, never affected by the risk mode. */
    private static void sell(L leg) {
        if (!leg.unitAvailable) return;
        leg.finalQty = leg.q;
        if (leg.q > 0 && leg.limit != null && leg.limit.signum() > 0) {
            leg.finalLimit = leg.limit;
            leg.finalAmount = BigDecimal.valueOf(leg.q).multiply(unit(leg)).multiply(leg.limit);
            leg.finalFees = leg.q == leg.base ? decimal(leg.node.path("fees")) : null;
        }
    }

    /** Task 626.12: any violation is a 500, never a 200. */
    private static void invariants(L leg) {
        if (leg.finalQty == null) { if (leg.unitAvailable || leg.redecided) throw new SrppReportProblem("INTERNAL_ERROR"); return; }
        long f = leg.finalQty;
        if (f < 0) throw new SrppReportProblem("INTERNAL_ERROR");
        if (leg.candidate) { if (f > 0 && (f > leg.rerunLots || f > leg.refreshCap)) throw new SrppReportProblem("INTERNAL_ERROR"); }
        else if (f > leg.base) throw new SrppReportProblem("INTERNAL_ERROR");
        if ("SELL".equals(leg.action) && f != 0 && f != leg.base && !("REDUCE".equals(leg.verdict) && f == leg.q)) throw new SrppReportProblem("INTERNAL_ERROR");
        if (f > 0 && (leg.finalLimit != null || "BUY".equals(leg.action))) {
            if (leg.finalLimit == null || leg.finalAmount == null || leg.finalAmount.compareTo(BigDecimal.valueOf(f).multiply(unit(leg)).multiply(leg.finalLimit)) != 0)
                throw new SrppReportProblem("INTERNAL_ERROR");
        }
        if (f == 0 && (leg.finalAmount != null || leg.finalLimit != null)) throw new SrppReportProblem("INTERNAL_ERROR");
    }

    private static ObjectNode project(Context c, L leg) {
        ObjectNode o = F.objectNode();
        o.put("legId", leg.id); o.put("market", SrppProjectionService.market(leg.market)); o.put("symbol", leg.symbol); o.put("action", leg.action);
        if (leg.strategy == null) o.putNull("strategy"); else o.put("strategy", leg.strategy);
        o.put("refreshCandidate", leg.candidate); o.put("redecided", leg.redecided); o.put("verdict", leg.verdict); o.put("riskAffected", leg.riskAffected);
        if (leg.redecided) {
            if (leg.lotsLeg) { o.put("baseLots", leg.base); o.putNull("baseUnits"); } else { o.putNull("baseLots"); o.put("baseUnits", Long.toString(leg.base)); }
        } else { o.set("baseLots", SrppProjectionService.baseLots(leg.candidate ? zeroLots() : leg.node)); o.set("baseUnits", leg.candidate ? NullNode.instance : SrppProjectionService.baseUnits(leg.node)); }
        if (leg.finalQty == null) { o.putNull("finalLots"); o.putNull("finalUnits"); }
        else if (leg.lotsLeg) { o.put("finalLots", leg.finalQty); o.putNull("finalUnits"); }
        else { o.putNull("finalLots"); o.put("finalUnits", Long.toString(leg.finalQty)); }
        amount(o, "finalLimitPrice", leg.finalLimit); amount(o, "finalAmount", leg.finalAmount); amount(o, "fees", leg.finalFees);
        o.put("currency", leg.currency);
        ArrayNode reasons = o.putArray("reasonCodes"); leg.reasons.forEach(reasons::add);
        SrppProjectionService.QuoteProof quote = leg.candidate || leg.redecided ? null : SrppProjectionService.quoteProof(c.loaded(), new SrppProjectionService.Leg(leg.id, leg.module, leg.index, leg.node));
        if (quote == null) o.putNull("quoteCapture");
        else { ObjectNode capture = o.putObject("quoteCapture"); capture.put("sourceId", quote.sourceId()); capture.put("sourceRevision", quote.revision()); capture.put("sourceSha256", quote.sha256()); capture.put("capturedAt", quote.capturedAt()); ObjectNode receipt = capture.putObject("receiptRef"); receipt.put("kind", "QUOTE"); receipt.put("sha256", quote.sha256()); receipt.put("capturedAt", quote.capturedAt()); }
        ArrayNode refs = o.putArray("refreshCaptureRefs");
        Map<String, Capture> used = new TreeMap<>();
        for (Capture capture : leg.captures) used.put(capture.sourceId(), capture);
        for (Capture capture : used.values()) {
            ObjectNode ref = refs.addObject(); ref.put("kind", "REFRESH_QUOTE"); ref.put("captureId", capture.captureId()); ref.put("sha256", capture.sha256()); ref.put("capturedAt", capture.capturedAt().toString());
        }
        if (leg.freshness == null) o.putNull("freshness"); else o.set("freshness", leg.freshness);
        o.putNull("settlement"); o.put("executable", false);
        return o;
    }

    private static ObjectNode zeroLots() { return F.objectNode().put("lots", 0); }

    private static void amount(ObjectNode n, String key, BigDecimal value) { if (value == null) n.putNull(key); else n.put(key, SrppReportModuleCalculator.dec(value)); }

    static List<String> stringsOf(JsonNode array) {
        List<String> out = new ArrayList<>();
        if (array != null && array.isArray()) for (JsonNode v : array) if (v.isTextual()) out.add(v.textValue());
        return out;
    }

    private static BigDecimal decimal(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode() || !(n.isTextual() || n.isNumber())) return null;
        try { return new BigDecimal(n.asText()); } catch (NumberFormatException invalid) { return null; }
    }
}
