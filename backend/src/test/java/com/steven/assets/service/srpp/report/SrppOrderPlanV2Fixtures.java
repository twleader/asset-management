package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.dto.QuoteDetailDto;
import com.steven.assets.model.AppUser;
import com.steven.assets.repository.SrppReportRepository;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.service.PriceQueryService;
import com.steven.assets.service.srpp.PublishedSwaggerIdentity;
import com.steven.assets.service.srpp.decision.DecisionFacts;
import com.steven.assets.service.srpp.decision.DecisionFacts.*;
import com.steven.assets.service.srpp.decision.DecisionInputCapturePort.Frozen;
import com.steven.assets.srpp.SrppJcs;
import com.steven.assets.srpp.SrppPolicyRegistryService;
import com.steven.assets.srpp.SupportedPolicy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.util.LinkedMultiValueMap;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import static com.steven.assets.service.srpp.report.SrppReportFixtures.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Task 626 v2 fixtures: genuine FINAL packages from the real publisher and fake cached read-only market reads. */
final class SrppOrderPlanV2Fixtures {
    private SrppOrderPlanV2Fixtures() {}
    static final List<String> BONDS = List.of("00865B", "00719B", "00697B");

    /** A publication-faithful body that {@link SrppReportExtraCaptureAdapter#assess} verifies once a live quote is present. */
    static void verifiable(ObjectNode body, String code, Instant at, BigDecimal price, boolean withQuote) {
        body.put("market", "台股").put("symbol", code).put("volumeUnit", "SHARES");
        if (withQuote) body.set("quote", SrppReportModuleCalculator.normalize(MAPPER.valueToTree(live(code, price, at, 1_000_000L, "LIVE"))));
        else body.putNull("quote");
        body.set("nav", SrppReportModuleCalculator.normalize(MAPPER.valueToTree(nav(code, price))));
        body.set("book", SrppReportModuleCalculator.normalize(MAPPER.valueToTree(book(code, price, at))));
        ObjectNode radar = body.putObject("radar");
        radar.put("ruleVersion", "TEST_RULE").put("actionPolicyVersion", "EVIDENCE_GATE_V1").put("generatedAt", at.minusSeconds(60).toString());
        radar.putObject("stock").put("stockCode", code).put("market", "台股").put("asOfDate", DATE.toString()).put("dataComplete", true).put("quoteStatus", "LIVE");
        body.putArray("daily").addObject().put("tradingDate", DATE.minusDays(1).toString()).put("closePrice", "10").put("trusted", true);
        body.putArray("dividends");
        body.putArray("indicators").addObject().put("tradingDate", DATE.minusDays(1).toString());
        body.putArray("completedSessions").add(DATE.minusDays(1).toString());
        body.put("calendarVerified", true).put("lookbackComplete", true);
    }

    static PriceQueryService.LivePrice live(String code, BigDecimal price, Instant updated, long volumeShares, String status) {
        return new PriceQueryService.LivePrice(code, code, "台股", price, BigDecimal.TEN, price.subtract(BigDecimal.TEN), BigDecimal.ZERO, price, price, price, price, price,
                volumeShares, DATE.toString(), updated.toString(), false, "FUBON", status);
    }
    static PriceQueryService.EtfNav nav(String code, BigDecimal price) { return new PriceQueryService.EtfNav(code, "台股", price, BigDecimal.ZERO, "20261008 09:00:00", "TWSE"); }
    static QuoteDetailDto.Response book(String code, BigDecimal price, Instant at) {
        List<QuoteDetailDto.OrderBookLevel> levels = new ArrayList<>();
        for (int i = 0; i < 5; i++) levels.add(new QuoteDetailDto.OrderBookLevel(i + 1, price.subtract(new BigDecimal("0.01").multiply(BigDecimal.valueOf(i + 1))), 10L, price.add(new BigDecimal("0.01").multiply(BigDecimal.valueOf(i + 1))), 10L));
        return new QuoteDetailDto.Response(code, code, "台股", true, true, "FUBON_BOOKS", null, at, at, "OPEN", price, BigDecimal.TEN, price, price, price, price, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, 100L, 100L, BigDecimal.ZERO, 0L, 0L, BigDecimal.ZERO, BigDecimal.ZERO, 50L, 50L, levels);
    }

    /** The qualified fixture frame with Taiwan term deposits (D-152 pooled headroom known), optional satellite sources and symbol edits. */
    static Frozen frame(boolean satellite, boolean vtActionable, UnaryOperator<Map<String, Symbol>> symbols, Consumer<ObjectNode> sources) throws Exception {
        return frame("09:05", AT, satellite, vtActionable, symbols, sources);
    }
    static Frozen frame(String slot, Instant at, boolean satellite, boolean vtActionable, UnaryOperator<Map<String, Symbol>> symbols, Consumer<ObjectNode> sources) throws Exception {
        Frozen f = SrppReportFixtures.qualified(slot, at, true);
        if (satellite) f = SrppSatelliteBuildFixtures.withSatellite(f, at);
        f = SrppSatelliteBuildFixtures.mutate(f, s -> {
            for (JsonNode d : s.path("assets").path("snapshot").path("deposits")) if ("TERM".equals(d.path("depositType").asText())) ((ObjectNode) d).put("depositType", "定存");
            for (String code : BONDS) verifiable(s.withObject("market:台股:" + code), code, at, price(code), true);
            if (satellite) verifiable(s.withObject("market:台股:009826"), "009826", at, new BigDecimal("10.05"), true);
            if (vtActionable) D215Golden.addNormal(s, DATE, at);
            if (sources != null) sources.accept(s);
        });
        Input old = f.facts();
        Map<String, Symbol> changed = symbols == null ? old.symbols() : symbols.apply(new TreeMap<>(old.symbols()));
        Input facts = new Input(old.date(), old.slot(), old.capturedAt(), old.sessions(), old.totalAssets(), old.totalDeposits(), old.termDeposits(), old.bondValue(), old.fx(), old.fxEvidence(), old.bearWindow(), changed, old.budget(), old.subaccounts(), old.emergencyValue());
        ObjectNode input = f.input();
        input.set("facts", SrppReportModuleCalculator.normalize(MAPPER.valueToTree(facts)));
        return new Frozen(facts, input, f.sourceVector(), f.assetSnapshotId(), f.assetGeneratedAt());
    }

    static BigDecimal price(String code) { return new BigDecimal(code.equals("00865B") ? "9.8" : code.equals("00719B") ? "9.85" : "9.9"); }

    static Frozen withValidation(Frozen f, String id, String validation) { return SrppSatelliteBuildFixtures.withValidation(f, id, validation); }

    static Frozen withFacts(Frozen f, UnaryOperator<Input> change) {
        Input facts = change.apply(f.facts());
        ObjectNode input = f.input();
        input.set("facts", SrppReportModuleCalculator.normalize(MAPPER.valueToTree(facts)));
        return new Frozen(facts, input, f.sourceVector(), f.assetSnapshotId(), f.assetGeneratedAt());
    }

    static Symbol quote(Symbol s, String status, BigDecimal premium) {
        Quote q = s.quote();
        return withQuote(s, new Quote(q.price(), q.previousClose(), q.volume(), q.date(), q.updatedAt(), q.source(), status, false, premium, premium == null ? null : q.premiumDate()));
    }
    static Symbol withQuote(Symbol s, Quote q) { return new Symbol(s.code(), q, s.book(), s.closes(), s.distributions(), s.distributionVerified(), s.currentValue(), s.position(), s.pending(), s.radar(), s.valuation(), s.tradability(), s.mediumAction(), s.mediumCandidateAction(), s.intraday()); }
    static Symbol value(Symbol s, BigDecimal value) { return new Symbol(s.code(), s.quote(), s.book(), s.closes(), s.distributions(), s.distributionVerified(), value, s.position(), s.pending(), s.radar(), s.valuation(), s.tradability(), s.mediumAction(), s.mediumCandidateAction(), s.intraday()); }
    static Symbol evidence(Symbol s, Evidence position, Evidence pending) { return new Symbol(s.code(), s.quote(), s.book(), s.closes(), s.distributions(), s.distributionVerified(), s.currentValue(), position, pending, s.radar(), s.valuation(), s.tradability(), s.mediumAction(), s.mediumCandidateAction(), s.intraday()); }

    /** The real publisher and validator over an in-memory repository, the calculation port evaluating {@code model}, extra = frozen sources. */
    static SrppReportRepository.Package publish(Frozen frozen, JsonNode model) {
        var repository = mock(SrppReportRepository.class); var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(i -> new SimpleTransactionStatus());
        SrppReportCalculationPort port = new SrppReportCalculationPort() {
            final PureSrppReportCalculations pure = new PureSrppReportCalculations();
            public ArrayNode shared(Frozen input, Policy policy, JsonNode ignored, SupportedPolicy supported, JsonNode extra) { return pure.shared(input, policy, model, supported, extra); }
            public ObjectNode core(Frozen input, JsonNode ignored, JsonNode state, String consumer) { return pure.core(input, model, state, consumer); }
            public ArrayNode compose(ArrayNode shared, ObjectNode core) { return pure.compose(shared, core); }
        };
        var publisher = new SrppReportPublisher(repository, AUTHORITY, port, MAPPER, manager, "sha256:" + "f".repeat(64));
        var scope = new SrppReportRepository.Scope(frozen.input().path("ownerUserId").asLong(), frozen.facts().date(), frozen.facts().slot(), supported().bundleHash(), SWAGGER, SrppReportModuleCalculator.VERSION);
        return publisher.publish(scope, frozen, supported(), (ObjectNode) frozen.input().path("sources"), states(scope.owner(), frozen.facts().slot()), frozen.facts().capturedAt())
                .stream().filter(p -> p.consumer().equals("Codex")).findFirst().orElseThrow();
    }

    /** Fake cached reads: code -> (price, status, updatedAt); a code mapped to null is an absent cache entry. */
    static final class Market {
        final Map<String, PriceQueryService.LivePrice> quotes = new HashMap<>();
        final Map<String, BigDecimal> premium = new HashMap<>();
        final Instant at;
        Market(Instant at) { this.at = at; for (String code : BONDS) put(code, price(code)); put("009826", new BigDecimal("10.05")); put("0050", new BigDecimal("100")); }
        Market put(String code, BigDecimal price) { quotes.put(code, live(code, price, at.minusSeconds(30), 1_000_000L, "LIVE")); return this; }
        Market status(String code, String status) { var q = quotes.get(code); quotes.put(code, live(code, q.price(), at.minusSeconds(30), 1_000_000L, status)); return this; }
        Market missing(String code) { quotes.put(code, null); return this; }
    }

    record Harness(SrppProjectionService service, PriceQueryService prices, MarketDataService market, SrppReportFubonCapturePort fubon) {}

    static Harness harness(SrppReportRepository.Package saved, JsonNode model, Market fake, Instant now) {
        CurrentUserContext owner = mock(CurrentUserContext.class); MarketDataService calendar = mock(MarketDataService.class);
        SrppPolicyRegistryService registry = mock(SrppPolicyRegistryService.class); PublishedSwaggerIdentity swagger = mock(PublishedSwaggerIdentity.class); SrppReportRepository repository = mock(SrppReportRepository.class);
        PriceQueryService prices = mock(PriceQueryService.class); SrppReportFubonCapturePort fubon = mock(SrppReportFubonCapturePort.class);
        when(calendar.isTwTradingDayCachedOnly(DATE)).thenReturn(Optional.of(true)); when(owner.hasUser()).thenReturn(true); when(owner.getStatus()).thenReturn(AppUser.STATUS_ACTIVE); when(owner.getEffectiveUserId()).thenReturn(saved.scope().owner());
        when(registry.find(supported().bundleHash())).thenReturn(Optional.of(supported())); when(swagger.sha256()).thenReturn(Optional.of(SWAGGER)); when(repository.byId(saved.scope().owner(), saved.id())).thenReturn(Optional.of(saved));
        when(prices.getLiveBatch(anySet(), anyMap())).thenAnswer(call -> {
            Set<PriceQueryService.PriceKey> keys = call.getArgument(0); Map<PriceQueryService.PriceKey, Optional<PriceQueryService.LivePrice>> out = new HashMap<>();
            for (var key : keys) out.put(key, Optional.ofNullable(fake.quotes.get(key.stockCode())));
            return out;
        });
        when(prices.getEtfNav(anyString(), anyString())).thenAnswer(call -> { var q = fake.quotes.get((String) call.getArgument(0)); return q == null ? Optional.empty() : Optional.of(new PriceQueryService.EtfNav(q.stockCode(), "台股", q.price(), fake.premium.getOrDefault(q.stockCode(), BigDecimal.ZERO), "20261008 09:00:00", "TWSE")); });
        when(calendar.getQuoteDetail(anyString(), anyString())).thenAnswer(call -> { var q = fake.quotes.get((String) call.getArgument(0)); return q == null ? null : book(q.stockCode(), q.price(), fake.at.minusSeconds(30)); });
        var service = new SrppProjectionService(owner, calendar, registry, swagger, AUTHORITY, repository, Clock.fixed(now, ZoneOffset.UTC)).withV2(prices, fubon, MAPPER, model);
        return new Harness(service, prices, calendar, fubon);
    }

    static LinkedMultiValueMap<String, String> v2(SrppReportRepository.Package saved, String decisions, String mode, int total, int assessed, int max, Instant run, Instant asOf, String twd, String usd) {
        var values = new LinkedMultiValueMap<String, String>();
        values.add("consumer", "Codex"); values.add("tradingDate", DATE.toString()); values.add("reportSlot", saved.scope().slot());
        values.add("policyBundleSha256", supported().bundleHash()); values.add("swaggerSha256", SWAGGER);
        values.add("calculationRunId", saved.id().toString()); values.add("decisions", decisions); values.add("asOf", asOf.toString());
        values.add("projectionVersion", "SRPP_ORDER_PLAN_PROJECTION_V2"); values.add("riskMode", mode); values.add("riskTotalScore", Integer.toString(total));
        values.add("riskAssessedCount", Integer.toString(assessed)); values.add("riskMaxCategoryScore", Integer.toString(max)); values.add("riskReceiptSha256", "c".repeat(64));
        values.add("runStartedAt", run.toString()); values.add("fundingCommitments", "TWD~" + twd + ",USD~" + usd);
        return values;
    }

    static LinkedMultiValueMap<String, String> normal(SrppReportRepository.Package saved, String decisions) { return v2(saved, decisions, "NORMAL", 4, 6, 2, AT, AT.plusSeconds(60), "0", "0"); }

    /** ADOPT for every v2 leg in package order, from the package itself. */
    static String adoptAll(SrppReportRepository.Package saved, Set<String> exclude) {
        JsonNode root = SrppJcs.parseStrict(saved.content());
        List<String> out = new ArrayList<>();
        for (JsonNode m : root.path("modules")) {
            JsonNode legs = m.path("data").path("legs");
            for (int i = 0; i < legs.size(); i++) {
                JsonNode leg = legs.get(i);
                String id = m.path("moduleId").asText() + "." + i;
                if (exclude.contains(id)) continue;
                if (!"NONE".equals(leg.path("action").asText()) || SrppOrderPlanV2.candidate(m.path("moduleId").asText(), leg)) out.add(id + "~ADOPT");
            }
        }
        return String.join(",", out);
    }

    static JsonNode leg(JsonNode plan, String id) {
        for (JsonNode leg : plan.path("legs")) if (id.equals(leg.path("legId").asText())) return leg;
        throw new AssertionError(id + " not in " + plan.path("legs"));
    }
    static boolean has(JsonNode plan, String id) { for (JsonNode leg : plan.path("legs")) if (id.equals(leg.path("legId").asText())) return true; return false; }
    static JsonNode packageLeg(SrppReportRepository.Package saved, String module, int index) {
        for (JsonNode m : SrppJcs.parseStrict(saved.content()).path("modules")) if (module.equals(m.path("moduleId").asText())) return m.path("data").path("legs").get(index);
        throw new AssertionError(module);
    }
    static BigDecimal fact(SrppReportRepository.Package saved, String module, String name, String subject) {
        for (JsonNode m : SrppJcs.parseStrict(saved.content()).path("modules")) if (module.equals(m.path("moduleId").asText()))
            for (JsonNode f : m.path("data").path("facts")) if (name.equals(f.path("name").asText()) && Objects.equals(subject, f.path("subject").isNull() ? null : f.path("subject").asText()))
                return f.path("value").isNull() ? null : new BigDecimal(f.path("value").asText());
        throw new AssertionError(name);
    }
    static DecisionFacts.Evidence pass() { return new DecisionFacts.Evidence("PASS", "ISOLATED_OFFICIAL_EVIDENCE_FIXTURE"); }
}
