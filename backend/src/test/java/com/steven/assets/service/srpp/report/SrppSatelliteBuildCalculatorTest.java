package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.model.ExchangeRateHistory;
import com.steven.assets.service.srpp.decision.DecisionFacts;
import com.steven.assets.service.srpp.decision.DecisionInputCapturePort.Frozen;
import com.steven.assets.srpp.SrppJcs;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Task 623: Java satellite build formulas versus the original SRPP Python golden, field by field. */
class SrppSatelliteBuildCalculatorTest {
    static final ObjectMapper MAPPER = new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
    static final List<String> CODES_009826 = List.of("BEFORE_EARLIEST_BUY_DATE", "BUILD_WINDOW_CLOSED", "QUOTE_UNVERIFIED", "TOTAL_ASSETS_UNVERIFIED",
            "HOLDING_UNVERIFIED", "PACE_STATE_UNVERIFIED", "DAILY_LOT_LIMIT", "WEEKLY_LOT_LIMIT", "MONTHLY_CAP", "TARGET_WEIGHT_REACHED", "WITHIN_PACE_AND_TARGET");
    static final List<String> CODES_VT = List.of("BEFORE_EARLIEST_BUY_DATE", "BUILD_WINDOW_CLOSED", "QUOTE_UNVERIFIED", "FX_CONVERSION_UNVERIFIED",
            "TOTAL_ASSETS_UNVERIFIED", "HOLDING_UNVERIFIED", "PACE_STATE_UNVERIFIED", "DAILY_SHARE_LIMIT", "WEEKLY_SHARE_LIMIT", "MONTHLY_CAP",
            "TARGET_WEIGHT_REACHED", "BELOW_MIN_ORDER_SHARES", "WITHIN_PACE_AND_TARGET");
    static final Set<String> MIDS_WINDOW_FIELDS = Set.of("evidence.fx_halved", "evidence.fx_status", "evidence.fx_month_max_mid",
            "evidence.monthly_cap_twd_effective", "evidence.monthly_baseline_twd_effective");

    static JsonNode golden() throws Exception {
        return MAPPER.readTree(SrppSatelliteBuildCalculatorTest.class.getResourceAsStream("/srpp/satellite-build-golden.json"));
    }

    @Test void everyOriginalSrppGoldenCaseMatchesFieldByField() throws Exception {
        JsonNode golden = golden();
        assertThat(golden.path("schema").asText()).isEqualTo("SATELLITE_BUILD_ORIGINAL_SRPP_GOLDEN_V1");
        assertThat(golden.path("calculatorSha256").asText()).matches("[a-f0-9]{64}");
        Map<String, Integer> counts = new HashMap<>();Map<String, Set<String>> codes = new HashMap<>();
        for (JsonNode c : golden.path("cases")) {
            String kind = c.path("kind").asText(), name = c.path("name").asText();JsonNode in = c.path("input");
            counts.merge(kind, 1, Integer::sum);
            if (c.has("knownDifference")) { knownMidsWindowDifference(golden, c); continue; }
            JsonNode actual = switch (kind) {
                case "009826" -> SrppSatelliteBuildCalculator.build009826(in.get("quote"), in.path("trading_date").asText(), in.get("held_value_twd"),
                        in.get("total_assets_twd"), in.get("transactions"), rates(in.path("usd_twd_rates")), policy(golden, c, "009826"));
                case "VT" -> SrppSatelliteBuildCalculator.buildVt(in.get("quote"), in.path("trading_date").asText(),
                        in.path("us_session_date").isTextual() ? in.path("us_session_date").asText() : null, in.get("held_value_twd"),
                        in.get("total_assets_twd"), in.get("transactions"), rates(in.path("usd_twd_rates")), in.get("spot_selling_rate"), policy(golden, c, "VT"));
                case "api009826" -> SrppSatelliteBuildCalculator.apiInputs009826(in.get("assets"), in.get("fx"), in.path("trading_date").asText());
                case "apiVt" -> SrppSatelliteBuildCalculator.apiInputsVt(in.get("assets"), in.get("fx"), in.path("trading_date").asText());
                default -> throw new AssertionError(kind);
            };
            assertThat(differences(c.path("expected"), actual, "")).as(kind + ":" + name).isEmpty();
            if (Set.of("009826", "VT").contains(kind)) c.path("expected").path("reasons").forEach(r -> codes.computeIfAbsent(kind, k -> new HashSet<>()).add(r.asText()));
        }
        assertThat(counts.get("009826")).isGreaterThanOrEqualTo(10);
        assertThat(counts.get("VT")).isGreaterThanOrEqualTo(10);
        assertThat(codes.get("009826")).containsAll(CODES_009826);
        assertThat(codes.get("VT")).containsAll(CODES_VT);
    }

    /** 9090 validates only current-month mids; SRPP's BFF 502s on any bad row in its two-year window (rates=[], halved). */
    static void knownMidsWindowDifference(JsonNode golden, JsonNode c) {
        assertThat(c.path("knownDifference").asText()).isEqualTo("KNOWN_DIFFERENCE_MIDS_WINDOW");
        JsonNode in = c.path("input");LocalDate date = LocalDate.parse(in.path("trading_date").asText());
        List<ExchangeRateHistory> rows = new ArrayList<>();
        for (JsonNode r : in.path("fx_rows")) {
            ExchangeRateHistory row = new ExchangeRateHistory();row.setCurrency("USD");row.setRateDate(LocalDate.parse(r.path("date").asText()));
            row.setBuyRate(new BigDecimal(r.path("buyRate").asText()));row.setSellRate(new BigDecimal(r.path("sellRate").asText()));rows.add(row);
        }
        List<ExchangeRateHistory> month = rows.stream().filter(r -> !r.getRateDate().isBefore(date.withDayOfMonth(1))).toList();
        ObjectNode mids = SrppSatelliteBuildCapture.midsSource(date, month);
        assertThat(mids.path("status").asText()).isEqualTo("VERIFIED");
        assertThat(SrppSatelliteBuildCapture.midsSource(date, rows).path("status").asText()).as("an out-of-window row is outside this source").isEqualTo("UNAVAILABLE");
        Frozen frozen = frozen(date, Instant.parse(in.path("captured_at").asText()), in, mids);
        var shape = SrppSatelliteBuildCalculator.toPythonShape(frozen);
        boolean lots = "api009826".equals(c.path("kind").asText());
        ObjectNode actual = lots ? SrppSatelliteBuildCalculator.calculate009826(shape, golden.path("policy").path("009826"))
                : SrppSatelliteBuildCalculator.calculateVt(shape, golden.path("policy").path("VT"));
        assertThat(differences(c.path("javaExpected"), actual, "")).as(c.path("name").asText()).isEmpty();
        Set<String> known = differences(c.path("expected"), actual, "");
        assertThat(known).contains("evidence.fx_halved").isSubsetOf(MIDS_WINDOW_FIELDS);
    }

    static Frozen frozen(LocalDate date, Instant at, JsonNode in, ObjectNode mids) {
        var unavailable = new DecisionFacts.Evidence("UNAVAILABLE", "CANONICAL_SOURCE_UNAVAILABLE");
        var facts = new DecisionFacts.Input(date, "09:05", at, List.of(), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, null, unavailable, unavailable, Map.of(),
                new DecisionFacts.Budget(0, 0, 0, false, BigDecimal.ZERO, Map.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO), unavailable, null);
        ObjectNode input = JsonNodeFactory.instance.objectNode();input.put("ownerUserId", 7).put("tradingDate", date.toString());
        ObjectNode sources = input.putObject("sources");String bundle = "a".repeat(64);
        sources.putObject("policy").put("bundleHash", bundle);sources.set("assets", in.path("assets").deepCopy());sources.set("fx", in.path("fx").deepCopy());
        sources.set("usd-twd-month-mids", mids);sources.set("market:台股:009826", in.path("market_009826").deepCopy());sources.set("market:美股:VT", in.path("market_vt").deepCopy());
        ObjectNode fills = in.path("transactions").deepCopy();fills.put("ownerUserId", 7).put("tradingDate", date.toString()).put("policyBundleSha256", bundle).put("status", "VERIFIED").put("verified", true).put("coverage", "COMPLETE");
        sources.set("satellite-build-fills", fills);
        ObjectNode vector = JsonNodeFactory.instance.objectNode();
        sources.fields().forEachRemaining(e -> vector.putObject(e.getKey()).put("sourceId", e.getKey()).put("sha256", SrppJcs.hash(SrppReportModuleCalculator.normalize(e.getValue()))).put("coverage", "COMPLETE").put("validation", "VERIFIED"));
        return new Frozen(facts, input, vector, 1, at.toString());
    }

    @Test void policyParsesExactCadenceAndRejectsMissingOrMistypedFields() throws Exception {
        JsonNode golden = golden();ObjectNode cadence = cadence(golden);
        var config = SrppSatelliteBuildPolicy.parse(cadence).orElseThrow();
        assertThat(config.policy009826()).isEqualTo(golden.path("policy").path("009826"));
        assertThat(config.policyVt()).isEqualTo(golden.path("policy").path("VT"));
        assertThat(config.policyVt().path("window").asText()).isEqualTo("2026-11-23 to 2027-06-30");
        assertThat(config.policyVt().path("min_shares_per_order").asInt()).isEqualTo(5);
        assertThat(config.policy009826().path("max_lots_per_week").asInt()).isEqualTo(6);
        ObjectNode model = JsonNodeFactory.instance.objectNode();model.putObject("execution_policy_overrides").set("global_equity_build_cadence_d141", cadence);
        assertThat(SrppSatelliteBuildPolicy.enabled(model)).isTrue();
        ObjectNode legacy = model.deepCopy();((ObjectNode) legacy.path("execution_policy_overrides").path("global_equity_build_cadence_d141")).remove("VT");
        assertThat(SrppSatelliteBuildPolicy.enabled(legacy)).isFalse();
        for (String field : List.of("window", "earliest_buy_date", "monthly_baseline_twd", "monthly_cap_twd", "max_lots_per_day", "max_lots_per_week", "stop_at_weight")) {
            ObjectNode missing = cadence.deepCopy();((ObjectNode) missing.path("009826")).remove(field);
            assertThat(SrppSatelliteBuildPolicy.parse(missing)).as("009826 missing " + field).isEmpty();
            ObjectNode typed = cadence.deepCopy();((ObjectNode) typed.path("009826")).put(field, field.contains("date") || field.equals("window") ? "1" : "3");
            if (!field.equals("window") && !field.contains("date")) assertThat(SrppSatelliteBuildPolicy.parse(typed)).as("009826 string " + field).isEmpty();
        }
        for (String field : List.of("window", "earliest_buy_date", "monthly_baseline_twd", "monthly_cap_twd", "max_shares_per_day", "max_shares_per_week",
                "min_shares_per_order", "whole_shares_only", "decision", "stop_at_weight")) {
            ObjectNode missing = cadence.deepCopy();((ObjectNode) missing.path("VT")).remove(field);
            assertThat(SrppSatelliteBuildPolicy.parse(missing)).as("VT missing " + field).isEmpty();
        }
        for (var mutation : List.<java.util.function.Consumer<ObjectNode>>of(
                c -> ((ObjectNode) c.path("VT")).put("whole_shares_only", false), c -> ((ObjectNode) c.path("VT")).put("decision", "D-143"),
                c -> ((ObjectNode) c.path("VT")).put("window", "2026-11-23..2027-06-30"), c -> ((ObjectNode) c.path("009826")).put("earliest_buy_date", "2026-11-2"),
                c -> ((ObjectNode) c.path("VT")).put("max_shares_per_day", true), c -> c.put("fx_halving_threshold", "33"), c -> c.remove("fx_halving_threshold"))) {
            ObjectNode bad = cadence.deepCopy();mutation.accept(bad);assertThat(SrppSatelliteBuildPolicy.parse(bad)).isEmpty();
        }
    }

    static ObjectNode cadence(JsonNode golden) {
        ObjectNode cadence = JsonNodeFactory.instance.objectNode();cadence.set("fx_halving_threshold", golden.path("policy").path("VT").path("fx_halving_threshold"));
        for (String code : List.of("009826", "VT")) { ObjectNode block = golden.path("policy").path(code).deepCopy();block.remove("fx_halving_threshold");cadence.set(code, block); }
        return cadence;
    }

    static JsonNode policy(JsonNode golden, JsonNode c, String code) {
        return c.path("input").has("policy") ? c.path("input").path("policy") : golden.path("policy").path(code);
    }

    static List<BigDecimal> rates(JsonNode values) {
        List<BigDecimal> out = new ArrayList<>();values.forEach(v -> out.add(SrppSatelliteBuildCalculator.num(v)));return out;
    }

    /** Strings, booleans, nulls, integers lots/shares and list order are exact; other numbers within 1e-9 relative to max(1,|python|). */
    static Set<String> differences(JsonNode expected, JsonNode actual, String path) {
        Set<String> out = new TreeSet<>();diff(expected, actual, path, out);return out;
    }

    private static void diff(JsonNode expected, JsonNode actual, String path, Set<String> out) {
        if (actual == null) { out.add(path); return; }
        if (expected.isObject()) {
            if (!actual.isObject()) { out.add(path); return; }
            Set<String> keys = new TreeSet<>();expected.fieldNames().forEachRemaining(keys::add);actual.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) diff(expected.has(key) ? expected.get(key) : MissingNode.getInstance(), actual.get(key), path.isEmpty() ? key : path + "." + key, out);
        } else if (expected.isArray()) {
            if (!actual.isArray() || actual.size() != expected.size()) { out.add(path); return; }
            for (int i = 0; i < expected.size(); i++) diff(expected.get(i), actual.get(i), path + "[" + i + "]", out);
        } else if (expected.isNumber() && !expected.isBoolean()) {
            if (!actual.isNumber()) { out.add(path); return; }
            BigDecimal e = expected.decimalValue(), a = actual.decimalValue();
            if (Set.of("lots", "shares").contains(path)) { if (!actual.isIntegralNumber() || a.compareTo(e) != 0) out.add(path); return; }
            if (a.subtract(e).abs().compareTo(new BigDecimal("1e-9").multiply(e.abs().max(BigDecimal.ONE))) > 0) out.add(path);
        } else if (!expected.equals(actual)) out.add(path);
    }
}
