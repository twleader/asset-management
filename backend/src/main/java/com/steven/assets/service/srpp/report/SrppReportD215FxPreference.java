package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.service.srpp.decision.DecisionInputCapturePort;
import com.steven.assets.model.ExchangeRateHistory;
import com.steven.assets.service.RadarCompletedPriceDrawdown;
import com.steven.assets.service.TradingRadarAssetProfileResolver.InstrumentKind;
import com.steven.assets.service.UsdFxPurchaseContext;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Task 625: report-path binding of D-215 over the existing {@link UsdFxPurchaseContext} formula, aligned field by field
 * with SRPP {@code scripts/usd_asset_fx_preference.py:evaluate}. Pure: frozen JSON, policy and the context only; no
 * Spring, DB or vendor I/O. Stricter than the radar context: LIVE only, unlisted TWD codes are UNKNOWN, source-document
 * whitelist, completed-session-bound exception price facts.
 */
public final class SrppReportD215FxPreference {
    public static final String KEY = "usd_asset_fx_preference_d215";
    public static final String NO_VETO = "NO_FX_PREFERENCE_VETO", EXCEPTION_REVIEW = "FX_PREFERENCE_EXCEPTION_REVIEW",
            WAIT = "WAIT_PREFERRED", UNKNOWN = "FX_EVIDENCE_UNKNOWN", NOT_APPLICABLE = "NOT_APPLICABLE";
    public static final String POLICY_UNAVAILABLE = "D215_FX_POLICY_UNAVAILABLE", ORDER_INVALID = "FX_TWO_YEAR_HISTORY_ORDER_INVALID";
    public static final String RULE = "D215_FX", CONTEXT_UNAVAILABLE = "D215_FX_CONTEXT_UNAVAILABLE";
    public static final String R_PASS_NORMAL = "D215_NO_FX_PREFERENCE_VETO", R_PASS_EXCEPTION = "D215_FX_PREFERENCE_EXCEPTION_REVIEW",
            R_WAIT = "D215_FX_WAIT_PREFERRED", R_UNKNOWN = "D215_FX_EVIDENCE_UNKNOWN";
    public static final Set<String> VALUE_NAMES = Set.of("fxSpotSelling", "fxRangeLow", "fxRangeHigh", "fxRangePosition", "fxAbsoluteHigh",
            "fxHigh", "assetHigh52w", "assetDrawdown52w", "cheapAssetException", "d215WaitCeilingLots", "d215WaitCeilingUnits", "d215WaitCeilingAmount");
    private static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    static final MathContext DISPLAY = new MathContext(28, RoundingMode.HALF_EVEN);
    private static final Set<String> SOURCES = Set.of("research/ETF_UNIVERSE.md", "docs/NAMED_DEEP_DRAWDOWN.md");

    /** Python-shaped result; a null field means the reference result has no such key. */
    public record Result(String status, String fxRisk, String rangeStatus, BigDecimal rangeLow, BigDecimal rangeHigh,
                         BigDecimal rangePosition, Boolean absoluteRateHigh, BigDecimal rate, Boolean cheapExceptionVerified,
                         String classificationStatus, String scope, List<String> reasons,
                         BigDecimal assetHigh52w, BigDecimal assetDrawdown52w) {
        public Result { reasons = List.copyOf(reasons); }
        public boolean pass() { return NO_VETO.equals(status) || EXCEPTION_REVIEW.equals(status); }
    }

    private SrppReportD215FxPreference() {}

    /** A pre-D-215 bundle has no key: callers emit nothing so the legacy output stays byte-identical. */
    public static boolean present(JsonNode model) {
        return model != null && model.path("execution_policy").has(KEY);
    }

    /** Exact Decimal binding of the values the Java formula hard-codes; any drift fails closed. */
    public static boolean policyValid(JsonNode p) {
        try {
            return p != null && p.isObject() && "D-215".equals(p.path("decision").textValue())
                    && p.path("enabled").isBoolean() && p.path("enabled").booleanValue()
                    && p.path("window_calendar_years").isIntegralNumber() && p.path("window_calendar_years").intValue() == 2
                    && equal(p.get("range_position_threshold"), "0.9") && equal(p.get("absolute_rate_strictly_above"), "32.8")
                    && equal(p.get("stock_exception_drawdown_fraction"), "0.3") && equal(p.get("etf_exception_drawdown_fraction"), "0.1")
                    && p.path("etf_exception_strictly_greater").isBoolean() && p.path("etf_exception_strictly_greater").booleanValue()
                    && p.path("twd_traded_bond_etfs_count_as_usd_assets").isBoolean()
                    && p.path("twd_traded_bond_etfs_count_as_usd_assets").booleanValue()
                    && "BANK_SPOT_SELLING".equals(p.path("rate_basis").textValue());
        } catch (RuntimeException invalid) { return false; }
    }

    private static boolean equal(JsonNode n, String expected) {
        if (n == null || !n.isNumber()) return false;
        return new BigDecimal(n.asText()).compareTo(new BigDecimal(expected)) == 0;
    }

    /**
     * 625.6: a spot-verified USD row after the completed date inside the window is an ordering defect (the reference
     * rejects it); the Java range only filters it, so the range becomes UNKNOWN. Legacy unverified rows never trigger this.
     */
    public static UsdFxPurchaseContext orderChecked(UsdFxPurchaseContext context, List<ExchangeRateHistory> rows) {
        if (context == null || context.data() == null) return context;
        var h = context.data().history();
        if (h == null || !h.available() || h.completedAsOfDate() == null || h.windowEnd() == null || rows == null) return context;
        boolean invalid = rows.stream().anyMatch(r -> r != null && "USD".equals(r.getCurrency()) && r.getRateDate() != null
                && r.getRateDate().isAfter(h.completedAsOfDate()) && !r.getRateDate().isAfter(h.windowEnd()) && r.isSpotBasisVerified());
        if (!invalid) return context;
        var d = context.data();
        var range = UsdFxPurchaseContext.HistoryRange.unavailable(h.windowEnd(), h.completedAsOfDate(), ORDER_INVALID);
        return new UsdFxPurchaseContext(context.stockCode(), context.market(), context.quoteCurrency(), context.instrumentKind(),
                context.typeVerified(), new UsdFxPurchaseContext.MarketData(d.quality(), d.currentRate(), d.currentAsOf(),
                d.currentSource(), range, d.unavailableReason()), context.evaluated());
    }

    /**
     * @param policy           the {@code usd_asset_fx_preference_d215} node of the frozen model
     * @param fact             own 52-week completed price fact (may be null / unavailable)
     * @param price            same-round verified quote price P of this leg
     * @param marketCompleted  last completed session of the asset's market calendar
     * @param sessionDate      the report session date
     */
    public static Result evaluate(JsonNode policy, String code, String market, String currency, UsdFxPurchaseContext context,
                                  RadarCompletedPriceDrawdown.Context fact, BigDecimal price,
                                  LocalDate marketCompleted, LocalDate sessionDate) {
        List<String> reasons = new ArrayList<>();
        BigDecimal high52 = fact == null || fact.status() != RadarCompletedPriceDrawdown.Status.AVAILABLE ? null : fact.high();
        BigDecimal drawdown = high52 == null ? null : fact.drawdownPercent();
        if (!policyValid(policy)) {
            reasons.add(POLICY_UNAVAILABLE);
            return unknown(null, null, reasons, high52, drawdown);
        }
        if (context == null || !context.evaluated()) {
            reasons.add(CONTEXT_UNAVAILABLE);
            return unknown(null, null, reasons, high52, drawdown);
        }
        // Classification authority: exact accepted policy row bound to the hashed embedded authority.
        List<JsonNode> matches = new ArrayList<>();
        for (JsonNode row : policy.path("verified_instruments"))
            if (code != null && code.equals(row.path("stock_code").asText()) && market != null && market.equals(row.path("market").asText()))
                matches.add(row);
        boolean authority = false;
        InstrumentKind kind = InstrumentKind.UNKNOWN;
        if (matches.size() > 1) { reasons.add("D215_TYPE_POLICY_DUPLICATE"); return unknown(null, null, reasons, high52, drawdown); }
        if (matches.size() == 1) {
            JsonNode row = matches.getFirst();
            try { kind = InstrumentKind.valueOf(row.path("instrument_kind").asText()); }
            catch (IllegalArgumentException invalid) { kind = InstrumentKind.UNKNOWN; }
            if (currency == null || !currency.equals(row.path("quote_currency").asText()) || kind == InstrumentKind.UNKNOWN) {
                reasons.add("D215_TYPE_POLICY_BINDING_MISMATCH"); return unknown(null, null, reasons, high52, drawdown);
            }
            if (!SOURCES.contains(row.path("source_document").asText())) {
                reasons.add("D215_TYPE_POLICY_SOURCE_INVALID"); return unknown(null, null, reasons, high52, drawdown);
            }
            if (context == null || !context.evaluated() || !context.typeVerified() || context.instrumentKind() != kind
                    || !code.equals(context.stockCode()) || !market.equals(context.market()) || !currency.equals(context.quoteCurrency())) {
                reasons.add("D215_TYPE_POLICY_SOURCE_HASH_MISMATCH"); return unknown(null, null, reasons, high52, drawdown);
            }
            authority = true;
        }
        String classification = authority ? "VERIFIED" : "UNKNOWN";
        if (!authority) {
            reasons.add("D215_TYPE_AUTHORITY_UNVERIFIED");
            if ("TWD".equals(currency)) return unknown(classification, null, reasons, high52, drawdown);
        }
        boolean twdBond = "TWD".equals(currency) && kind == InstrumentKind.BOND_ETF && authority;
        if (!"USD".equals(currency) && !twdBond)
            return new Result(NOT_APPLICABLE, NOT_APPLICABLE, "UNKNOWN", null, null, null, null, null, null,
                    classification, null, reasons, high52, drawdown);
        String scope = twdBond ? "TWD_TRADED_BOND_ETF" : "USD_DENOMINATED_ASSET";
        var data = context == null ? null : context.data();
        if (context == null || !context.evaluated() || !code.equals(context.stockCode()) || !market.equals(context.market())
                || data == null || !"LIVE".equals(data.quality()) || data.currentRate() == null || data.currentRate().signum() <= 0) {
            reasons.add("FX_CURRENT_RATE_UNVERIFIED");
            return unknown(classification, scope, reasons, high52, drawdown);
        }
        BigDecimal rate = data.currentRate();
        boolean absoluteHigh = rate.compareTo(new BigDecimal("32.8")) > 0;
        var h = data.history();
        Boolean rangeHigh = null;
        BigDecimal low = null, high = null, position = null;
        String rangeStatus = "UNKNOWN";
        if (h != null && h.available() && h.low() != null && h.high() != null && h.high().compareTo(h.low()) > 0) {
            low = h.low(); high = h.high();
            rangeHigh = rate.subtract(low).multiply(new BigDecimal("100")).compareTo(high.subtract(low).multiply(new BigDecimal("90"))) >= 0;
            position = rate.subtract(low).divide(high.subtract(low), DISPLAY);
            rangeStatus = "VERIFIED";
        } else reasons.add(h == null || h.unavailableReason() == null ? "FX_TWO_YEAR_RANGE_UNKNOWN" : h.unavailableReason());
        boolean isHigh = absoluteHigh || Boolean.TRUE.equals(rangeHigh);
        if (!isHigh && rangeHigh != null)
            return new Result(NO_VETO, "NORMAL", rangeStatus, low, high, position, absoluteHigh, rate, null,
                    classification, scope, reasons, high52, drawdown);
        String status = isHigh ? WAIT : UNKNOWN, risk = isHigh ? "HIGH" : "UNKNOWN";
        boolean cheap = false;
        if (fact == null || fact.status() != RadarCompletedPriceDrawdown.Status.AVAILABLE)
            reasons.add("D215_EXCEPTION_PRICE_UNAVAILABLE");
        else if (marketCompleted == null || sessionDate == null || fact.completedAsOfDate() == null
                || !fact.completedAsOfDate().equals(marketCompleted) || !fact.completedAsOfDate().isBefore(sessionDate))
            reasons.add("D215_EXCEPTION_PRICE_ASOF_MISMATCH");
        else {
            cheap = price != null && context.priceException(fact, price);
            if (!cheap) reasons.add("D215_EXCEPTION_NOT_REACHED");
        }
        if (cheap) status = EXCEPTION_REVIEW;
        return new Result(status, risk, rangeStatus, low, high, position, absoluteHigh, rate, cheap,
                classification, scope, reasons, high52, drawdown);
    }

    private static Result unknown(String classification, String scope, List<String> reasons, BigDecimal high52, BigDecimal drawdown) {
        return new Result(UNKNOWN, "UNKNOWN", "UNKNOWN", null, null, null, null, null, null, classification, scope, reasons, high52, drawdown);
    }

    /**
     * Task 625.10–625.14 post-gate on one already-built report leg (bond Candidate or the t623 VT leg), before sharedFunds.
     * Adds exactly one D215_FX receipt; a non-PASS result turns an ACTIONABLE leg into a BLOCKED zero leg and keeps the
     * original capacity only as d215WaitCeiling* receipt values. Never re-ranks, re-selects or releases funds.
     */
    static void gate(SrppReportModuleCalculator.M m, ObjectNode leg, DecisionInputCapturePort.Frozen f, JsonNode model,
                     JsonNode extra, BigDecimal price) {
        if (!present(model)) return;
        String symbol = leg.path("symbol").asText(), market = leg.path("market").asText(), currency = leg.path("currency").asText();
        boolean vt = SrppReportModuleCalculator.SATELLITE_VT_STRATEGY.equals(leg.path("strategy").asText());
        String marketRef = "market:" + market + ":" + symbol;
        List<String> refs = List.of("fx", "policy", marketRef);
        JsonNode policy = model.path("execution_policy").path(KEY);
        JsonNode body = extra == null ? null : extra.path(marketRef);
        if (body == null || body.isMissingNode()) body = extra == null ? null : extra.path("symbols").path(market + ":" + symbol);
        UsdFxPurchaseContext context = null;
        RadarCompletedPriceDrawdown.Context fact = null;
        LocalDate completed = null, session = f.facts().date();
        if (body != null && body.isObject()) {
            try {
                JsonNode raw = body.path("usdFxPurchase");
                if (raw.isObject() && raw.path("evaluated").asBoolean(false)) context = MAPPER.treeToValue(raw, UsdFxPurchaseContext.class);
            } catch (Exception invalid) { context = null; }
            completed = lastCompleted(body);
            fact = priceFact(body, symbol, market, price, session);
        }
        Result r = evaluate(policy, symbol, market, currency, context, fact, price, completed, session);
        List<String> unverified = new ArrayList<>();
        for (String ref : new TreeSet<>(refs))
            if (!"VERIFIED".equals(f.sourceVector().path(ref).path("validation").asText())) unverified.add("SOURCE_NOT_VERIFIED:" + ref);
        // An unverified referenced source always keeps the module out of COMPLETE, whatever the preference result.
        for (String u : unverified) m.missing(u + ":" + symbol);
        String status, reason;
        if (!policyValid(policy)) { status = "UNAVAILABLE"; reason = POLICY_UNAVAILABLE; }
        else if (r.pass()) {
            if (unverified.isEmpty()) { status = "PASS"; reason = NO_VETO.equals(r.status()) ? R_PASS_NORMAL : R_PASS_EXCEPTION; }
            else { status = "UNAVAILABLE"; reason = String.join(",", unverified); }
        } else if (WAIT.equals(r.status())) { status = "BLOCK"; reason = R_WAIT; }
        else { status = "UNAVAILABLE"; reason = R_UNKNOWN; }
        if (POLICY_UNAVAILABLE.equals(reason)) m.missing(POLICY_UNAVAILABLE + ":" + symbol);
        if (R_UNKNOWN.equals(reason)) m.missing(R_UNKNOWN + ":" + symbol);
        ObjectNode receipt = SrppReportModuleCalculator.receipt(RULE, status, reason, refs);
        ArrayNode values = (ArrayNode) receipt.path("values");
        String priceUnit = "台股".equals(market) ? "TWD" : "USD";
        String rateReason = "D215_FX_CURRENT_RATE_UNVERIFIED", rangeReason = "D215_FX_RANGE_UNKNOWN", priceReason = "D215_EXCEPTION_PRICE_UNAVAILABLE";
        values.add(SrppReportModuleCalculator.fact("fxSpotSelling", symbol, r.rate(), "TWD", rateReason));
        values.add(SrppReportModuleCalculator.fact("fxRangeLow", symbol, r.rangeLow(), "TWD", rangeReason));
        values.add(SrppReportModuleCalculator.fact("fxRangeHigh", symbol, r.rangeHigh(), "TWD", rangeReason));
        values.add(SrppReportModuleCalculator.fact("fxRangePosition", symbol, r.rangePosition(), "RATIO", rangeReason));
        values.add(SrppReportModuleCalculator.fact("fxAbsoluteHigh", symbol, bool(r.absoluteRateHigh()), "BOOLEAN", rateReason));
        values.add(SrppReportModuleCalculator.fact("fxHigh", symbol, "HIGH".equals(r.fxRisk()) ? BigDecimal.ONE : "NORMAL".equals(r.fxRisk()) ? BigDecimal.ZERO : null,
                "BOOLEAN", rangeReason));
        values.add(SrppReportModuleCalculator.fact("assetHigh52w", symbol, r.assetHigh52w(), priceUnit, priceReason));
        values.add(SrppReportModuleCalculator.fact("assetDrawdown52w", symbol, r.assetDrawdown52w(), "PERCENT", priceReason));
        values.add(SrppReportModuleCalculator.fact("cheapAssetException", symbol, bool(r.cheapExceptionVerified()), "BOOLEAN",
                "NORMAL".equals(r.fxRisk()) ? "D215_EXCEPTION_NOT_EVALUATED_FX_NORMAL" : "D215_EXCEPTION_NOT_EVALUATED"));
        m.refs.addAll(refs);
        if (!"PASS".equals(status) && "ACTIONABLE_FOR_REPORT".equals(leg.path("status").asText())) {
            BigDecimal limit = new BigDecimal(leg.path("limitPrice").asText());
            if (vt) {
                BigDecimal units = new BigDecimal(leg.path("units").asText());
                values.add(SrppReportModuleCalculator.fact("d215WaitCeilingUnits", symbol, units, "SHARES"));
                values.add(SrppReportModuleCalculator.fact("d215WaitCeilingAmount", symbol, limit.multiply(units), "USD"));
                leg.put("units", "0");
            } else {
                BigDecimal lots = BigDecimal.valueOf(leg.path("lots").asLong());
                values.add(SrppReportModuleCalculator.fact("d215WaitCeilingLots", symbol, lots, "LOTS"));
                values.add(SrppReportModuleCalculator.fact("d215WaitCeilingAmount", symbol, limit.multiply(new BigDecimal("1000")).multiply(lots), "TWD"));
                leg.put("action", "NONE");
                leg.put("lots", 0);
            }
            leg.put("status", "BLOCKED");
            for (String key : List.of("limitPrice", "amount", "fees", "rankingReason")) leg.putNull(key);
            SortedSet<String> blocking = new TreeSet<>();
            leg.path("blockingReasons").forEach(b -> blocking.add(b.asText()));
            blocking.add(reason);
            ArrayNode list = leg.putArray("blockingReasons");
            blocking.forEach(list::add);
        }
        ((ArrayNode) leg.path("receipts")).add(receipt);
    }

    private static BigDecimal bool(Boolean value) { return value == null ? null : value ? BigDecimal.ONE : BigDecimal.ZERO; }

    private static LocalDate lastCompleted(JsonNode body) {
        try {
            JsonNode sessions = body.path("completedSessions");
            if (!body.path("calendarVerified").asBoolean(false) || !sessions.isArray() || sessions.isEmpty()) return null;
            return LocalDate.parse(sessions.get(sessions.size() - 1).asText());
        } catch (RuntimeException invalid) { return null; }
    }

    /** Price-only trusted completed rows of this leg's own market body; anything doubtful yields no exception basis. */
    private static RadarCompletedPriceDrawdown.Context priceFact(JsonNode body, String symbol, String market, BigDecimal price, LocalDate session) {
        String basis = "台股".equals(market) ? "TW_SPLIT_STOCK_PRICE" : "STORED_PRICE_ONLY";
        try {
            if (price == null || !"AVAILABLE".equals(body.path("priceOnlyBasisStatus").asText()) || !basis.equals(body.path("priceOnlyBasis").asText())
                    || !body.path("priceOnlyDaily").isArray()) return RadarCompletedPriceDrawdown.Context.unavailable(symbol, market, "D215_PRICE_ONLY_BASIS_UNAVAILABLE");
            List<StockPriceHistory> rows = new ArrayList<>();
            for (JsonNode raw : body.path("priceOnlyDaily")) {
                if (!raw.path("trusted").asBoolean(false)) return RadarCompletedPriceDrawdown.Context.unavailable(symbol, market, "D215_PRICE_HISTORY_UNTRUSTED");
                StockPriceHistory row = new StockPriceHistory();
                row.setStockCode(symbol); row.setMarket(market); row.setTradingDate(LocalDate.parse(raw.path("tradingDate").asText()));
                if (session == null || !row.getTradingDate().isBefore(session)) return RadarCompletedPriceDrawdown.Context.unavailable(symbol, market, "D215_FUTURE_COMPLETED_PRICE");
                row.setHighPrice(new BigDecimal(raw.path("highPrice").asText())); row.setClosePrice(new BigDecimal(raw.path("closePrice").asText()));
                rows.add(row);
            }
            rows.sort(java.util.Comparator.comparing(StockPriceHistory::getTradingDate).reversed());
            return RadarCompletedPriceDrawdown.evaluate(symbol, market, rows, 0, price,
                    "台股".equals(market) ? RadarCompletedPriceDrawdown.PriceBasis.TW_SPLIT_STOCK_PRICE : RadarCompletedPriceDrawdown.PriceBasis.STORED_PRICE_ONLY,
                    Set.of(), false);
        } catch (RuntimeException invalid) {
            return RadarCompletedPriceDrawdown.Context.unavailable(symbol, market, "D215_PRICE_HISTORY_INVALID");
        }
    }
}
