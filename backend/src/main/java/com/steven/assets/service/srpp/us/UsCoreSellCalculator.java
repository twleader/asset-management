package com.steven.assets.service.srpp.us;

import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.service.srpp.OrdinaryEquitySellGate;
import com.fasterxml.jackson.databind.node.*;
import java.math.*;
import java.time.*;
import java.util.*;

/** Pure D-174 US port. The producer owns identity, persisted-state provenance and transaction fences. */
public final class UsCoreSellCalculator {
    public static final String VERSION = "US_D174_D186_V1";
    private static final JsonNodeFactory J = JsonNodeFactory.instance;
    private static final ZoneId NY = ZoneId.of("America/New_York"), TW = ZoneId.of("Asia/Taipei");
    private static final BigDecimal ZERO = BigDecimal.ZERO, HUNDRED = new BigDecimal("100");
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final String LEGACY_SEMANTICS = "167dd0095d9be9e576b476102800bcd17ef266fd6d8d932ce949af94e60e85ca";
    // Frozen D-205 allowlist, accepted only with the identical observation semantics above.
    private static final Set<String> LEGACY = Set.of("65dc1a1ebbbdce6bc172e158c6f75df037426480afbc3d1a569c1e3607cd5e98", "b22a185b7616ea08a66fbeacf4e46ceb0f0597fbe0cbae9efeff93949768a9d9");
    private UsCoreSellCalculator() {}
    private static final class Gap extends IllegalArgumentException { Gap(String code) { super(code); } }
    private static void require(boolean condition, String code) { if (!condition) throw new Gap(code); }
    public static String watchPolicyFingerprint(JsonNode policy) {
        ObjectNode value = J.objectNode();
        for (String key : List.of("decision", "scope", "arming", "non_distributing_symbols")) {
            require(policy.has(key), "CORE_SELL_POLICY_UNVERIFIED"); value.set(key, policy.get(key));
        }
        return UsPythonJson.hash(value);
    }
    /** Current states may use canonical decimal strings; imported raw hashes remain separately preserved. */
    public static ObjectNode normalizeWatchState(JsonNode value, String code, String day, JsonNode policy) {
        require(value != null && value.isObject(), "WATCH_STATE_UNVERIFIED"); ObjectNode state = value.deepCopy();
        LocalDate armed = date(state.path("armed_date").asText()), high = date(state.path("high_date").asText()), basis = date(state.path("basis_date").asText());
        require(code.equals(state.path("stock_code").asText()) && "美股".equals(state.path("market").asText()) && positive(state.get("high_water")) && n(state.get("high_water")).compareTo(new BigDecimal("1e-12")) >= 0
            && !armed.isAfter(high) && !high.isAfter(basis) && !basis.isAfter(date(day)) && !state.path("evidence_sha256").asText().isBlank(), "WATCH_STATE_UNVERIFIED");
        String target = watchPolicyFingerprint(policy), source = state.path("policy_sha256").asText();
        if (!target.equals(source)) {
            ObjectNode previous = policy.deepCopy(); previous.remove("ordinary_equity_high_sell_d211");
            ObjectNode priorD208 = previous.deepCopy(); priorD208.remove("high_zone_reversal_d209");
            require(UsPythonJson.hash(policy).equals(source) || UsPythonJson.hash(previous).equals(source)
                || UsPythonJson.hash(priorD208).equals(source) || LEGACY_SEMANTICS.equals(target) && LEGACY.contains(source), "WATCH_STATE_UNVERIFIED");
            state.put("policy_sha256", target);
            state.putObject("policy_migration").put("decision", "D-205").put("from", source).put("to", target)
                .put("original_evidence_sha256", state.path("evidence_sha256").asText()).put("scope", "OBSERVATION_ONLY_NO_TRADE_AUTHORIZATION");
        }
        return state;
    }
    public static ObjectNode calculate(JsonNode input, JsonNode model, JsonNode verifiedLedger, String consumer) {
        ObjectNode out = J.objectNode(); out.put("decision", "D-174").put("places_orders", false).put("requires_daily_hard_gates", true);
        ArrayNode results = out.putArray("results"), reasons = out.putArray("reasonCodes"); ObjectNode updates = out.putObject("watch_updates"); ArrayNode stale = out.putArray("watch_stale_symbols");
        JsonNode policy = model.path("execution_policy").path("core_sell_guard_d174");
        boolean ledgerValid = verifiedLedger != null && verifiedLedger.isObject() && "D-174".equals(verifiedLedger.path("decision").asText())
            && consumer.equals(verifiedLedger.path("consumer").asText()) && verifiedLedger.path("states").isObject();
        if (!ledgerValid) {
            out.putNull("stateBefore").putNull("stateAfter").put("coverage", "UNAVAILABLE"); reasons.add("WATCH_STATE_SOURCE_UNAVAILABLE");
            for (JsonNode item : input.path("items")) { ObjectNode row = base(item, null); row.put("reason", "WATCH_STATE_SOURCE_UNAVAILABLE"); results.add(row); }
            return out;
        }
        ObjectNode before = verifiedLedger.path("states").deepCopy(), after = before.deepCopy(); out.set("stateBefore", before);
        require(input.path("items").isArray() && "美股".equals(input.path("market").asText()), "WATCH_INPUT_UNVERIFIED");
        Set<String> seen = new HashSet<>(); boolean complete = true, any = input.path("items").isEmpty();
        for (JsonNode item : input.path("items")) {
            String code = item.path("stock_code").asText(); require(!code.isBlank() && seen.add(code), "DUPLICATE_CORE_SELL_CANDIDATE");
            ObjectNode row = evaluate(item, input, policy, before.get(code)); results.add(row);
            if (row.path("watch_refreshed").asBoolean() && row.path("next_state").isObject()) after.set(code, row.get("next_state"));
            if ("SELL_TIMING_UNVERIFIED".equals(row.path("status").asText())) { complete = false; addUnique(reasons, row.path("reason").asText()); any |= row.path("watch_refreshed").asBoolean(); } else any = true;
        }
        after.fields().forEachRemaining(entry -> {
            JsonNode row = null; for (JsonNode candidate : results) if (entry.getKey().equals(candidate.path("stock_code").asText())) row = candidate;
            boolean updated = row != null && row.path("watch_refreshed").asBoolean(); ObjectNode update = updates.putObject(entry.getKey());
            update.put("status", updated ? "UPDATED" : "NOT_UPDATED"); update.set("basis_date", entry.getValue().path("basis_date"));
            if (updated) update.putNull("reason"); else { update.put("reason", row == null ? "WATCH_NOT_EVALUATED" : row.path("reason").asText()); stale.add(entry.getKey()); }
        });
        out.set("stateAfter", after); out.put("coverage", complete ? "COMPLETE" : any ? "PARTIAL" : "UNAVAILABLE"); return out;
    }
    private static ObjectNode base(JsonNode item, JsonNode state) {
        ObjectNode out = J.objectNode(); out.set("stock_code", item.path("stock_code"));
        out.put("decision", "D-174").put("status", "SELL_TIMING_UNVERIFIED").put("timing_pass", false).put("places_orders", false).put("requires_daily_hard_gates", true);
        out.set("next_state", state == null ? NullNode.instance : state.deepCopy()); out.putNull("reason"); return out;
    }
    public static ObjectNode evaluate(JsonNode item, JsonNode input, JsonNode policy, JsonNode before) {
        ObjectNode out = base(item, before); String code = item.path("stock_code").asText(), day = input.path("trading_date").asText();
        try {
            require(policy.path("enabled").asBoolean() && "D-174".equals(policy.path("decision").asText()), "CORE_SELL_POLICY_UNVERIFIED");
            require(Set.of("CORE_REDUCE", "CORE_EXIT").contains(item.path("policy_direction").asText()), "OUTSIDE_CORE_SELL_SCOPE");
            require(!code.isBlank(), "EXACT_SELECTOR_MISMATCH"); Instant at = instant(input.path("asof").asText());
            require(at.atZone(NY).toLocalDate().toString().equals(day), "ASOF_DATE_MISMATCH");
            CalendarEvidence calendar = new CalendarEvidence(input.path("calendar")); List<String> expected = calendar.completed(day, policy.path("kd_corporate_action_lookback").asInt());
            JsonNode quote = item.path("quote"), radar = item.has("radar") ? item.get("radar") : NullNode.instance, stock = radar.path("stock");
            require(code.equals(quote.path("stockCode").asText()) && "美股".equals(quote.path("market").asText()), "EXACT_SELECTOR_MISMATCH");
            out.set("quote_freshness", quoteFreshness(quote, day, at, input.path("slot").asText(), input.path("session")));
            require(positive(quote.get("price")) && positive(quote.get("highPrice")) && n(quote.get("highPrice")).compareTo(n(quote.get("price"))) >= 0, "LIVE_SELL_PRICE_UNVERIFIED");
            out.put("price_basis", "LAST_TRADE"); out.set("reference_price", quote.get("price"));
            ObjectNode evidenceQuote = J.objectNode();
            for (String key : List.of("stockCode", "market", "tradingDate", "price", "highPrice", "updatedAt", "quoteStatus", "closed", "source", "marketData", "dividendHistory")) evidenceQuote.set(key, quote.has(key) ? quote.get(key) : NullNode.instance);
            ObjectNode evidence = J.objectNode(); evidence.set("quote", evidenceQuote); evidence.set("radar", radar);
            String evidenceHash = UsPythonJson.hash(evidence); ObjectNode state = null; List<Event> events = null;
            if (before != null && !before.isNull()) {
                Refresh refreshed = refresh(normalizeWatchState(before, code, day, policy), quote, day, policy, calendar, expected, evidenceHash);
                state = refreshed.state(); events = refreshed.events(); out.set("next_state", state); out.put("watch_refreshed", true);
                ObjectNode observation = out.putObject("watch_observation"); observation.put("basis_date", day).put("trade_authorization", false); observation.set("distribution_adjustments", eventNodes(events));
            }
            require(radar.isObject() && stock.isObject(), "EXACT_RADAR_UNVERIFIED");
            require(code.equals(stock.path("stockCode").asText()) && "美股".equals(stock.path("market").asText()), "EXACT_SELECTOR_MISMATCH");
            long radarAge = Duration.between(instant(radar.path("generatedAt").asText()), at).toMillis();
            require(!radar.path("ruleVersion").asText().isBlank() && stock.path("dataComplete").asBoolean() && day.equals(stock.path("asOfDate").asText())
                && "LIVE".equals(stock.path("quoteStatus").asText()) && radarAge >= 0 && radarAge <= 3_600_000, "EXACT_RADAR_UNVERIFIED");
            actionPolicy(radar, stock, policy);
            JsonNode ext = stock.path("extendedIndicators"); BigDecimal k = domain(stock.get("kValue"), ZERO, HUNDRED), d = domain(stock.get("dValue"), ZERO, HUNDRED), rsi = domain(ext.get("rsi5"), ZERO, HUNDRED), wr = domain(ext.get("wr9"), ZERO, HUNDRED), bias = n(ext.get("bias10")), position = domain(stock.get("week52Position"), ZERO, BigDecimal.ONE);
            int hits = (k.compareTo(new BigDecimal("90")) > 0 && d.compareTo(new BigDecimal("80")) > 0 ? 1 : 0) + (rsi.compareTo(new BigDecimal("80")) >= 0 ? 1 : 0) + (bias.compareTo(new BigDecimal("8")) >= 0 ? 1 : 0) + (wr.compareTo(new BigDecimal("20")) <= 0 ? 1 : 0);
            boolean armed = position.compareTo(new BigDecimal("0.95")) >= 0 && hits >= 2;
            out.set("week52_position", stock.get("week52Position")); out.put("overheat_hits", hits).put("overheat_watch", armed);
            if (state == null && !armed) { out.put("status", "NOT_ARMED").put("reason", "OVERHEAT_WATCH_NOT_MET"); return out; }
            if (state == null) {
                state = J.objectNode(); state.put("stock_code", code).put("market", "美股").put("policy_sha256", watchPolicyFingerprint(policy)).put("armed_date", day).put("high_date", day).put("basis_date", day).set("high_water", quote.get("highPrice")); state.put("evidence_sha256", evidenceHash);
                Refresh refreshed = refresh(state, quote, day, policy, calendar, expected, evidenceHash); state = refreshed.state(); events = refreshed.events();
            }
            out.set("next_state", state); out.put("watch_refreshed", true).put("armed_date", state.path("armed_date").asText()).put("high_date", state.path("high_date").asText());
            out.set("high_water", state.get("high_water")); out.set("distribution_adjustments", eventNodes(events));
            JsonNode daily = quote.path("marketData").path("chart").path("series").path("daily"); int count = daily.path("dates").size();
            require(daily.path("k").isArray() && daily.path("d").isArray() && daily.path("k").size() == count && daily.path("d").size() == count, "COMPLETED_KD_OR_OHLC_UNVERIFIED");
            List<String> confirmation = expected.subList(expected.size() - 3, expected.size()); List<BigDecimal> ks = new ArrayList<>(), ds = new ArrayList<>();
            try { for (String date : confirmation) { int index = indexOf(daily.path("dates"), date); ks.add(domain(daily.path("k").get(index), ZERO, HUNDRED)); ds.add(domain(daily.path("d").get(index), ZERO, HUNDRED)); } }
            catch (Gap missingKd) { throw new Gap("COMPLETED_KD_OR_OHLC_UNVERIFIED"); }
            ArrayNode dates = out.putArray("kd_dates"), kvals = out.putArray("completed_k"), dvals = out.putArray("completed_d");
            for (String date : confirmation) { int index = indexOf(daily.path("dates"), date); dates.add(date); kvals.add(daily.path("k").get(index)); dvals.add(daily.path("d").get(index)); }
            BigDecimal high = n(state.get("high_water")), pullback = high.subtract(n(quote.get("price"))).divide(high, MC), threshold = n(policy.get("pullback_fraction"));
            out.put("pullback_pct", pullback.multiply(HUNDRED).doubleValue()).put("required_pullback_pct", threshold.multiply(HUNDRED).doubleValue());
            String protection;
            try { protection = OrdinaryEquitySellGate.reason(code, "美股", policy, ks.get(2), ds.get(2), stock); }
            catch (IllegalArgumentException missing) { throw new Gap(missing.getMessage()); }
            if (protection != null) { out.put("status", "SELL_DIRECTION_BLOCKED").put("reason", protection); return out; }
            require(events.stream().noneMatch(event -> event.cash().signum() > 0 && event.date().compareTo(expected.getFirst()) > 0), "KD_CORPORATE_ACTION_BASIS_UNVERIFIED");
            boolean afterHigh = confirmation.get(1).compareTo(state.path("high_date").asText()) > 0;
            boolean weak = ks.get(0).compareTo(ks.get(1)) > 0 && ks.get(1).compareTo(ks.get(2)) > 0 && ks.get(2).compareTo(ds.get(2)) < 0 && ds.get(2).compareTo(ds.get(1)) < 0;
            boolean liveWeak = k.compareTo(d) < 0; out.put("trend_reversal", afterHigh && weak && liveWeak);
            Set<String> compatible = new HashSet<>(); policy.path("compatible_sell_actions").forEach(value -> compatible.add(value.asText()));
            if (!compatible.contains(stock.path("action").asText()) || !compatible.contains(stock.path("candidateAction").asText())) out.put("status", "SELL_DIRECTION_BLOCKED").put("reason", "RADAR_SELL_CONFLICT");
            else if (!afterHigh || !weak || !liveWeak) out.put("status", "ARMED_HOLD").put("reason", "WAIT_CONFIRMED_TREND_REVERSAL");
            else if (pullback.compareTo(threshold) < 0) out.put("status", "WAIT_PULLBACK").put("reason", "HIGH_WATER_PULLBACK_NOT_MET");
            else out.put("status", "CORE_SELL_READY").put("timing_pass", true).put("reason", "CONFIRMED_REVERSAL_AND_PULLBACK");
        } catch (RuntimeException failure) { out.put("status", "SELL_TIMING_UNVERIFIED").put("timing_pass", false).put("reason", failure instanceof Gap ? failure.getMessage() : "SELL_TIMING_EVIDENCE_MISSING"); }
        return out;
    }
    public static ObjectNode quoteFreshness(JsonNode quote, String day, Instant at, String slot, JsonNode session) {
        Instant quoted = instant(quote.path("updatedAt").asText()), open = instant(session.path("open").asText()), close = instant(session.path("close").asText());
        long age = Duration.between(quoted, at).toMillis();
        require(!at.isBefore(open) && at.isBefore(close) && !quoted.isBefore(open) && quoted.isBefore(close)
            && at.atZone(NY).toLocalDate().toString().equals(day) && quoted.atZone(NY).toLocalDate().toString().equals(day), "US_SESSION_UNVERIFIED");
        require(Set.of("21:45", "22:45").contains(slot) && "美股".equals(quote.path("market").asText()) && day.equals(quote.path("tradingDate").asText())
            && at.atZone(NY).toLocalDate().toString().equals(day) && quoted.atZone(NY).toLocalDate().toString().equals(day)
            && open.atZone(NY).toLocalDate().toString().equals(day) && open.atZone(NY).toLocalTime().equals(LocalTime.of(9, 30))
            && close.isAfter(open) && !close.atZone(NY).toLocalTime().isAfter(LocalTime.of(16, 0)) && close.atZone(NY).toLocalDate().toString().equals(day)
            && !at.isBefore(open) && at.isBefore(close) && !quoted.isBefore(open) && quoted.isBefore(close)
            && positive(quote.get("price")) && n(quote.get("price")).compareTo(new BigDecimal("1e-12")) >= 0 && "LIVE".equals(quote.path("quoteStatus").asText()) && quote.path("closed").isBoolean() && !quote.path("closed").asBoolean()
            && quote.path("source").isTextual() && !quote.path("source").asText().isBlank() && age >= 0 && age <= 300_000, "LIVE_SELL_PRICE_UNVERIFIED");
        return J.objectNode().put("decision", "D-108").put("status", "US_REGULAR_LAST_TRADE_5M").put("slot", slot).put("stage", "final").put("age_minutes", age / 60_000d).put("max_age_minutes", 5);
    }
    private record Event(String date, BigDecimal cash) {}
    private record Refresh(ObjectNode state, List<Event> events) {}
    private static Refresh refresh(ObjectNode before, JsonNode quote, String day, JsonNode policy, CalendarEvidence calendar, List<String> expected, String evidenceHash) {
        String basis = before.path("basis_date").asText(), start = basis.compareTo(expected.getFirst()) < 0 ? basis : expected.getFirst();
        List<String> history = calendar.sessions(start, day); history = history.subList(0, history.size() - 1);
        JsonNode chart = quote.path("marketData").path("chart"), daily = chart.path("series").path("daily"), dates = daily.path("dates");
        require("AVAILABLE".equals(chart.path("status").asText()) && dates.isArray() && !dates.isEmpty() && daily.path("closes").isArray() && daily.path("closes").size() == dates.size()
            && day.equals(chart.path("requestedEnd").asText()) && chart.path("requestedStart").asText().compareTo(dates.get(0).asText()) <= 0 && dates.get(0).asText().compareTo(history.getFirst()) <= 0, "COMPLETED_CLOSES_UNVERIFIED");
        date(chart.path("requestedStart").asText()); String prior = ""; List<String> completed = new ArrayList<>();
        for (JsonNode entry : dates) { String value = entry.asText(); date(value); require(value.compareTo(prior) > 0 && value.compareTo(day) <= 0, "COMPLETED_CLOSES_UNVERIFIED"); if (value.compareTo(day) < 0) completed.add(value); prior = value; }
        require(completed.size() >= history.size() && completed.subList(completed.size() - history.size(), completed.size()).equals(history), "COMPLETED_CLOSES_UNVERIFIED");
        for (String key : List.of("highs", "lows")) require(daily.path(key).isArray() && daily.path(key).size() == dates.size(), "COMPLETED_KD_OR_OHLC_UNVERIFIED");
        for (String value : history) { int index = indexOf(dates, value); require(positive(daily.path("lows").get(index)) && positive(daily.path("highs").get(index)) && positive(daily.path("closes").get(index)), "COMPLETED_KD_OR_OHLC_UNVERIFIED"); BigDecimal low = n(daily.path("lows").get(index)), high = n(daily.path("highs").get(index)), close = n(daily.path("closes").get(index)); require(low.compareTo(close) <= 0 && close.compareTo(high) <= 0, "COMPLETED_KD_OR_OHLC_UNVERIFIED"); }
        List<Event> events = events(quote, policy, history.getFirst(), day);
        for (String value : history) require(n(daily.path("closes").get(indexOf(dates, value))).subtract(cashAfter(events, value)).signum() > 0, "ADJUSTED_CLOSE_INVALID");
        BigDecimal high = n(before.get("high_water")).subtract(cashAfter(events, basis)); require(high.signum() > 0, "WATCH_PRICE_BASIS_UNVERIFIED"); String highDate = before.path("high_date").asText();
        for (String value : history) if (value.compareTo(basis) >= 0) { BigDecimal candidate = n(daily.path("highs").get(indexOf(dates, value))).subtract(cashAfter(events, value)); if (candidate.compareTo(high) > 0) { high = candidate; highDate = value; } }
        if (n(quote.get("highPrice")).compareTo(high) > 0) { high = n(quote.get("highPrice")); highDate = day; }
        ObjectNode state = before.deepCopy(); state.put("high_water", high.doubleValue()).put("high_date", highDate).put("basis_date", day).put("evidence_sha256", evidenceHash); return new Refresh(state, events);
    }
    private static List<Event> events(JsonNode quote, JsonNode policy, String first, String day) {
        JsonNode history = quote.path("dividendHistory"), rows = history.path("rows"); String code = quote.path("stockCode").asText(); boolean non = false;
        for (JsonNode symbol : policy.path("non_distributing_symbols")) if (code.equals(symbol.asText())) non = true;
        boolean identity = code.equals(history.path("stockCode").asText()) && "美股".equals(history.path("market").asText());
        boolean knownEmpty = non && identity && rows.isArray() && rows.isEmpty() && history.path("source").isNull() && "查無資料".equals(history.path("message").asText());
        require(knownEmpty || identity && history.path("source").isTextual() && !history.path("source").asText().isBlank() && history.path("message").isNull() && rows.isArray(), "DISTRIBUTION_HISTORY_UNVERIFIED");
        require(!rows.isEmpty() || non, "DISTRIBUTION_HISTORY_UNVERIFIED"); TreeMap<String, BigDecimal> events = new TreeMap<>();
        for (JsonNode row : rows) {
            String ex = row.path("exDividendDate").asText(); date(ex);
            if (row.hasNonNull("exRightsDate")) { String rights = row.path("exRightsDate").asText(); date(rights); require(!(rights.compareTo(first) > 0 && rights.compareTo(day) <= 0), "STOCK_DIVIDEND_ADJUSTMENT_UNSUPPORTED"); }
            if (!(ex.compareTo(first) > 0 && ex.compareTo(day) <= 0)) continue;
            require(!row.hasNonNull("stockDividend") || n(row.get("stockDividend")).signum() == 0, "STOCK_DIVIDEND_ADJUSTMENT_UNSUPPORTED"); BigDecimal cash = n(row.get("cashDividend")); require(cash.signum() >= 0, "CASH_DISTRIBUTION_UNVERIFIED");
            BigDecimal previous = events.putIfAbsent(ex, cash); require(previous == null || previous.compareTo(cash) == 0, "DISTRIBUTION_HISTORY_CONFLICT");
        }
        return events.entrySet().stream().map(entry -> new Event(entry.getKey(), entry.getValue())).toList();
    }
    private static BigDecimal cashAfter(List<Event> events, String day) { return events.stream().filter(event -> event.date().compareTo(day) > 0).map(Event::cash).reduce(ZERO, BigDecimal::add); }
    private static ArrayNode eventNodes(List<Event> events) { ArrayNode rows = J.arrayNode(); if (events != null) for (Event event : events) rows.add(J.objectNode().put("ex_date", event.date()).put("cash", event.cash().doubleValue())); return rows; }
    static void actionPolicy(JsonNode radar, JsonNode stock, JsonNode policy) {
        String version = radar.path("actionPolicyVersion").asText(); boolean accepted = false; for (JsonNode value : policy.path("radar_action_policy_compatibility").path("accepted_versions")) if (version.equals(value.asText())) accepted = true; require(accepted, "EXACT_RADAR_UNVERIFIED");
        if (!"EVIDENCE_GATE_V2".equals(version)) return; JsonNode verification = stock.path("officialSma20Verification"), cfg = policy.path("radar_action_policy_compatibility").path("v2_official_sma20_verification"); require(verification.isObject() && cfg.path("required_gate_applied").isBoolean() && !cfg.path("required_gate_applied").asBoolean(), "RADAR_V2_OFFICIAL_SMA20_UNVERIFIED");
        String status = verification.path("status").asText(); require(!"CONFLICT".equals(status), "RADAR_OFFICIAL_SMA20_CONFLICT"); boolean valid = false; for (JsonNode value : cfg.path("accepted_statuses")) if (status.equals(value.asText())) valid = true; require(valid && verification.path("gateApplied").isBoolean() && !verification.path("gateApplied").asBoolean(), "RADAR_V2_OFFICIAL_SMA20_UNVERIFIED");
    }
    private static final class CalendarEvidence {
        private final TreeMap<String, Boolean> days = new TreeMap<>();
        CalendarEvidence(JsonNode rows) { require(rows.isArray(), "TRADING_CALENDAR_UNVERIFIED"); for (JsonNode row : rows) { String day = row.path("date").asText(); date(day); require(row.path("us").isBoolean() && days.put(day, row.path("us").asBoolean()) == null, "TRADING_CALENDAR_UNVERIFIED"); } }
        List<String> completed(String day, int count) { require(count >= 3 && Boolean.TRUE.equals(days.get(day)), "TRADING_CALENDAR_UNVERIFIED"); List<String> before = days.headMap(day, false).entrySet().stream().filter(Map.Entry::getValue).map(Map.Entry::getKey).toList(); require(before.size() >= count, "TRADING_CALENDAR_UNVERIFIED"); List<String> out = before.subList(before.size() - count, before.size()); sessions(out.getFirst(), day); return out; }
        List<String> sessions(String start, String end) { List<String> out = new ArrayList<>(); for (LocalDate day = date(start), last = date(end); !day.isAfter(last); day = day.plusDays(1)) { String value = day.toString(); require(days.containsKey(value), "TRADING_CALENDAR_UNVERIFIED"); if (days.get(value)) out.add(value); } return out; }
    }
    private static int indexOf(JsonNode dates, String target) { for (int i = 0; i < dates.size(); i++) if (target.equals(dates.get(i).asText())) return i; throw new Gap("COMPLETED_CLOSES_UNVERIFIED"); }
    private static LocalDate date(String value) { try { LocalDate day = LocalDate.parse(value); require(day.toString().equals(value), "WATCH_STATE_UNVERIFIED"); return day; } catch (DateTimeException failure) { throw new Gap("WATCH_STATE_UNVERIFIED"); } }
    static Instant instant(String value) { try { String raw = value.replaceFirst("\\[[^\\[\\]]+\\]$", ""); try { return OffsetDateTime.parse(raw).toInstant(); } catch (DateTimeException missingOffset) { return LocalDateTime.parse(raw).atZone(TW).toInstant(); } } catch (DateTimeException failure) { throw new Gap("LIVE_SELL_PRICE_UNVERIFIED"); } }
    private static boolean positive(JsonNode value) { try { return n(value).signum() > 0; } catch (Gap failure) { return false; } }
    static BigDecimal n(JsonNode value) { try { require(value != null && !value.isNull() && !value.isBoolean() && (value.isNumber() || value.isTextual()), "RADAR_INDICATORS_UNVERIFIED"); return new BigDecimal(value.asText()); } catch (NumberFormatException failure) { throw new Gap("RADAR_INDICATORS_UNVERIFIED"); } }
    private static BigDecimal domain(JsonNode value, BigDecimal low, BigDecimal high) { BigDecimal number = n(value); require(number.compareTo(low) >= 0 && number.compareTo(high) <= 0, "RADAR_INDICATORS_UNVERIFIED"); return number; }
    private static void addUnique(ArrayNode list, String value) { for (JsonNode entry : list) if (value.equals(entry.asText())) return; list.add(value); }
}
