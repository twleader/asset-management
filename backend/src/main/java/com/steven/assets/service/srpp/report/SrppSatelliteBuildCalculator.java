package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.service.srpp.decision.DecisionInputCapturePort.Frozen;
import com.steven.assets.srpp.SrppDecimal;
import java.math.*;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

/**
 * D-143 009826 lot and D-234 VT share build ceilings, ported field by field from SRPP
 * {@code execution_policy.global_build_009826}/{@code global_build_vt} and their API-input adapters.
 * Inputs and outputs keep the original snake_case shape. No Spring, clock, repository, HTTP or files.
 * A ceiling is never an order: the remaining gates stay with their own modules and the consumer.
 */
public final class SrppSatelliteBuildCalculator {
    private static final JsonNodeFactory F = JsonNodeFactory.instance;
    private static final MathContext MC = MathContext.DECIMAL128;
    static final BigDecimal EPS = new BigDecimal("0.000000001");
    private static final BigDecimal LOT = new BigDecimal("1000"), HALF = new BigDecimal("0.5"), TWO = new BigDecimal("2");
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private SrppSatelliteBuildCalculator() {}

    public static ObjectNode build009826(JsonNode quote, String tradingDate, JsonNode heldValueTwd, JsonNode totalAssetsTwd,
                                         JsonNode transactions, List<BigDecimal> usdTwdRates, JsonNode policy) {
        String code = "009826";
        List<String> blocks = new ArrayList<>();
        LocalDate today = LocalDate.parse(tradingDate);
        LocalDate monthStart = today.withDayOfMonth(1), weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        period(policy, tradingDate, blocks);

        JsonNode book = quote != null && quote.isObject() ? quote.get("askLevels") : null;
        JsonNode ask = book != null && book.isArray() && !book.isEmpty() && book.get(0).isObject() ? book.get(0).get("price") : null;
        boolean identity = quote != null && quote.isObject() && is(quote.get("stockCode"), code)
                && is(quote.get("market"), "台股") && is(quote.get("tradingDate"), tradingDate);
        String basis;JsonNode referenceNode;
        if (finitePositive(ask)) { basis = "ASK1"; referenceNode = ask; }
        else { basis = "LAST_PRICE"; referenceNode = identity ? quote.get("price") : null; }
        BigDecimal reference = null;
        if (!identity || !finitePositive(referenceNode)) { blocks.add("QUOTE_UNVERIFIED"); referenceNode = null; }
        else reference = num(referenceNode);
        if (!finitePositive(totalAssetsTwd)) blocks.add("TOTAL_ASSETS_UNVERIFIED");
        if (!heldValid(heldValueTwd)) blocks.add("HOLDING_UNVERIFIED");

        Pace pace = pace(transactions, today, monthStart, weekStart, code, "台股", false);
        if (!pace.ok) blocks.add("PACE_STATE_UNVERIFIED");

        Fx fx = fx(usdTwdRates, policy);
        Map<String, BigDecimal> room = new LinkedHashMap<>();
        if (pace.ok) {
            room.put("day", num(policy.get("max_lots_per_day")).subtract(pace.day));
            room.put("week", num(policy.get("max_lots_per_week")).subtract(pace.week));
            if (room.get("day").compareTo(BigDecimal.ONE.subtract(EPS)) < 0) blocks.add("DAILY_LOT_LIMIT");
            if (room.get("week").compareTo(BigDecimal.ONE.subtract(EPS)) < 0) blocks.add("WEEKLY_LOT_LIMIT");
        }
        BigDecimal postBuyWeight = null, total = num(totalAssetsTwd), held = num(heldValueTwd);
        if (reference != null) {
            BigDecimal lotValue = LOT.multiply(reference);
            room.put("month", fx.cap.subtract(pace.month).divide(lotValue, MC));
            if (pace.ok && room.get("month").compareTo(BigDecimal.ONE.subtract(EPS)) < 0) blocks.add("MONTHLY_CAP");
            if (finitePositive(totalAssetsTwd) && !blocks.contains("HOLDING_UNVERIFIED")) {
                room.put("weight", num(policy.get("stop_at_weight")).multiply(total).subtract(held).divide(lotValue, MC));
                postBuyWeight = held.add(lotValue).divide(total, MC);
                if (room.get("weight").compareTo(BigDecimal.ONE.subtract(EPS)) < 0) blocks.add("TARGET_WEIGHT_REACHED");
            }
        }
        long lots = blocks.isEmpty() ? Math.max(minFloor(room), 0) : 0;
        if (lots > 0 && finitePositive(totalAssetsTwd))
            postBuyWeight = held.add(BigDecimal.valueOf(lots).multiply(LOT).multiply(reference)).divide(total, MC);

        ObjectNode out = F.objectNode();
        out.put("decision", "D-222");out.put("stock_code", code);out.put("trading_date", tradingDate);out.put("places_orders", false);
        out.put("status", lots > 0 ? "BUILD_LOT_CEILING" : "BUILD_ZERO");out.put("lots", lots);out.put("shares", lots * 1000);
        reasons(out, blocks);
        ObjectNode e = out.putObject("evidence");
        e.set("reference_price", referenceNode == null ? NullNode.instance : referenceNode.deepCopy());e.put("reference_basis", basis);
        e.put("day_lots", pace.day);e.put("week_lots", pace.week);e.put("month_amount_twd", pace.month);
        e.put("week_start", weekStart.toString());e.put("month_start", monthStart.toString());
        fxEvidence(e, fx);
        e.set("held_value_twd", echo(heldValueTwd));e.set("total_assets_twd", echo(totalAssetsTwd));
        decimal(e, "post_buy_weight", postBuyWeight);e.set("stop_at_weight", echo(policy.get("stop_at_weight")));
        e.set("max_lots_per_day", echo(policy.get("max_lots_per_day")));e.set("max_lots_per_week", echo(policy.get("max_lots_per_week")));
        return out;
    }

    public static ObjectNode buildVt(JsonNode quote, String tradingDate, String usSessionDate, JsonNode heldValueTwd, JsonNode totalAssetsTwd,
                                     JsonNode transactions, List<BigDecimal> usdTwdRates, JsonNode spotSellingRate, JsonNode policy) {
        String code = "VT";
        List<String> blocks = new ArrayList<>();
        LocalDate today = LocalDate.parse(tradingDate);
        LocalDate monthStart = today.withDayOfMonth(1), weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        period(policy, tradingDate, blocks);

        boolean identity = quote != null && quote.isObject() && is(quote.get("stockCode"), code) && is(quote.get("market"), "美股")
                && usSessionDate != null && isoDate(usSessionDate) && is(quote.get("tradingDate"), usSessionDate);
        JsonNode ask = quote != null && quote.isObject() ? quote.get("sellPrice") : null;
        String basis;JsonNode referenceNode;
        if (identity && finitePositive(ask)) { basis = "ASK1"; referenceNode = ask; }
        else { basis = "LAST_PRICE"; referenceNode = identity ? quote.get("price") : null; }
        BigDecimal reference = null;
        if (!identity || !finitePositive(referenceNode)) { blocks.add("QUOTE_UNVERIFIED"); referenceNode = null; }
        else reference = num(referenceNode);
        if (!finitePositive(spotSellingRate)) blocks.add("FX_CONVERSION_UNVERIFIED");
        if (!finitePositive(totalAssetsTwd)) blocks.add("TOTAL_ASSETS_UNVERIFIED");
        if (!heldValid(heldValueTwd)) blocks.add("HOLDING_UNVERIFIED");

        Pace pace = pace(transactions, today, monthStart, weekStart, code, "美股", true);
        if (!pace.ok) blocks.add("PACE_STATE_UNVERIFIED");

        Fx fx = fx(usdTwdRates, policy);
        BigDecimal minimum = num(policy.get("min_shares_per_order"));
        Map<String, BigDecimal> room = new LinkedHashMap<>();
        if (pace.ok) {
            room.put("day", num(policy.get("max_shares_per_day")).subtract(pace.day));
            room.put("week", num(policy.get("max_shares_per_week")).subtract(pace.week));
        }
        BigDecimal shareTwd = null, postBuyWeight = null, total = num(totalAssetsTwd), held = num(heldValueTwd);
        if (reference != null && finitePositive(spotSellingRate)) {
            shareTwd = reference.multiply(num(spotSellingRate));
            room.put("month", fx.cap.subtract(pace.month).divide(shareTwd, MC));
            if (finitePositive(totalAssetsTwd) && !blocks.contains("HOLDING_UNVERIFIED")) {
                room.put("weight", num(policy.get("stop_at_weight")).multiply(total).subtract(held).divide(shareTwd, MC));
                postBuyWeight = held.divide(total, MC);
            }
        }
        long ceiling = !room.isEmpty() && blocks.isEmpty() ? Math.max(minFloor(room), 0) : 0;
        if (blocks.isEmpty()) {
            Map<String, String> names = Map.of("day", "DAILY_SHARE_LIMIT", "week", "WEEKLY_SHARE_LIMIT", "month", "MONTHLY_CAP", "weight", "TARGET_WEIGHT_REACHED");
            for (var entry : room.entrySet()) if (entry.getValue().compareTo(minimum.subtract(EPS)) < 0) blocks.add(names.get(entry.getKey()));
            if (blocks.isEmpty() && BigDecimal.valueOf(ceiling).compareTo(minimum) < 0) blocks.add("BELOW_MIN_ORDER_SHARES");
        }
        long shares = blocks.isEmpty() ? ceiling : 0;
        if (shares > 0 && finitePositive(totalAssetsTwd))
            postBuyWeight = held.add(BigDecimal.valueOf(shares).multiply(shareTwd)).divide(total, MC);

        ObjectNode out = F.objectNode();
        out.put("decision", "D-234");out.put("stock_code", code);out.put("trading_date", tradingDate);out.put("places_orders", false);
        out.put("status", shares > 0 ? "BUILD_SHARE_CEILING" : "BUILD_ZERO");out.put("shares", shares);
        reasons(out, blocks);
        ObjectNode e = out.putObject("evidence");
        e.set("reference_price_usd", referenceNode == null ? NullNode.instance : referenceNode.deepCopy());e.put("reference_basis", basis);
        if (usSessionDate == null) e.putNull("us_session_date"); else e.put("us_session_date", usSessionDate);
        e.set("spot_selling_rate", echo(spotSellingRate));decimal(e, "share_value_twd", shareTwd);
        e.put("day_shares", pace.day);e.put("week_shares", pace.week);e.put("month_amount_twd", pace.month);
        e.put("week_start", weekStart.toString());e.put("month_start", monthStart.toString());
        fxEvidence(e, fx);
        e.set("held_value_twd", echo(heldValueTwd));e.set("total_assets_twd", echo(totalAssetsTwd));
        decimal(e, "post_buy_weight", postBuyWeight);e.set("stop_at_weight", echo(policy.get("stop_at_weight")));
        e.set("max_shares_per_day", echo(policy.get("max_shares_per_day")));e.set("max_shares_per_week", echo(policy.get("max_shares_per_week")));
        e.set("min_shares_per_order", echo(policy.get("min_shares_per_order")));
        return out;
    }

    /** Same as {@code global_build_api_inputs}: unknown or malformed values become null / []. */
    public static ObjectNode apiInputs009826(JsonNode assets, JsonNode fx, String tradingDate) {
        JsonNode live = assets != null && assets.isObject() ? assets.get("liveAssets") : null;
        JsonNode total = live != null && live.isObject() ? live.get("liveTotalAssets") : null;
        ObjectNode out = F.objectNode();
        decimal(out, "held_value_twd", held(assets, "009826", "台股"));
        out.set("total_assets_twd", finitePositive(total) ? total.deepCopy() : NullNode.instance);
        ArrayNode rates = out.putArray("usd_twd_rates");
        if (fx != null && fx.isObject() && tradingDate != null && isoDate(tradingDate)) {
            String month = tradingDate.substring(0, 7);
            JsonNode history = fx.get("history");
            if (history != null && history.isArray()) for (JsonNode r : history) {
                if (!r.isObject() || r.get("date") == null || !r.get("date").isTextual()) continue;
                String date = r.get("date").textValue();
                if ((date.length() >= 7 ? date.substring(0, 7) : date).equals(month) && date.compareTo(tradingDate) <= 0 && finitePositive(r.get("midRate")))
                    rates.add(r.get("midRate").deepCopy());
            }
            JsonNode spot = fx.get("spot");
            if (spot != null && spot.isObject() && is(spot.get("date"), tradingDate) && is(spot.get("quoteStatus"), "LIVE") && finitePositive(spot.get("midRate")))
                rates.add(spot.get("midRate").deepCopy());
        }
        return out;
    }

    /** Same as {@code global_build_vt_api_inputs}: VT holding (all accounts) and the same-day LIVE spot selling rate. */
    public static ObjectNode apiInputsVt(JsonNode assets, JsonNode fx, String tradingDate) {
        ObjectNode out = apiInputs009826(assets, fx, tradingDate);
        decimal(out, "held_value_twd", held(assets, "VT", "美股"));
        JsonNode spot = fx != null && fx.isObject() ? fx.get("spot") : null;
        boolean live = spot != null && spot.isObject() && is(spot.get("date"), tradingDate) && is(spot.get("quoteStatus"), "LIVE") && finitePositive(spot.get("sellRate"));
        out.set("spot_selling_rate", live ? spot.get("sellRate").deepCopy() : NullNode.instance);
        return out;
    }

    /** Frozen 9090 sources adapted to the original Python request shape. */
    public record Inputs(String tradingDate, JsonNode quote009826, JsonNode quoteVt, String usSessionDate, JsonNode assets,
                         JsonNode fx, JsonNode transactions, boolean fxVerified, boolean midsVerified) {
        public List<BigDecimal> rates(ObjectNode api) {
            List<BigDecimal> out = new ArrayList<>();
            if (fxVerified && midsVerified) api.path("usd_twd_rates").forEach(v -> out.add(num(v)));
            return out;
        }
        public JsonNode spotSellingRate(ObjectNode api) { return fxVerified ? api.path("spot_selling_rate") : NullNode.instance; }
    }

    /** The frozen-source 009826 result: Python {@code global_build_api_inputs} then {@code global_build_009826}. */
    public static ObjectNode calculate009826(Inputs in, JsonNode policy) {
        ObjectNode api = apiInputs009826(in.assets(), in.fx(), in.tradingDate());
        return build009826(in.quote009826(), in.tradingDate(), api.path("held_value_twd"), api.path("total_assets_twd"), in.transactions(), in.rates(api), policy);
    }

    /** The frozen-source VT result: Python {@code global_build_vt_api_inputs} then {@code global_build_vt}. */
    public static ObjectNode calculateVt(Inputs in, JsonNode policy) {
        ObjectNode api = apiInputsVt(in.assets(), in.fx(), in.tradingDate());
        return buildVt(in.quoteVt(), in.tradingDate(), in.usSessionDate(), api.path("held_value_twd"), api.path("total_assets_twd"),
                in.transactions(), in.rates(api), in.spotSellingRate(api), policy);
    }

    public static Inputs toPythonShape(Frozen f) {
        JsonNode sources = f.input().path("sources"), vector = f.sourceVector();
        String date = f.facts().date().toString();
        JsonNode tw = sources.get("market:台股:009826"), us = sources.get("market:美股:VT");
        JsonNode quote009826 = NullNode.instance, quoteVt = NullNode.instance;
        if (tw != null && tw.path("quote").isObject()) {
            JsonNode q = tw.path("quote");ObjectNode shape = F.objectNode();
            for (String key : List.of("stockCode", "market", "tradingDate", "price")) shape.set(key, q.has(key) ? q.get(key).deepCopy() : NullNode.instance);
            ArrayNode levels = shape.putArray("askLevels");
            if (approvedCompleteBook(tw.path("book"), f.facts().date(), f.facts().capturedAt()))
                levels.addObject().set("price", tw.path("book").path("levels").get(0).get("askPrice").deepCopy());
            quote009826 = shape;
        }
        if (us != null && us.path("quote").isObject()) {
            JsonNode q = us.path("quote");ObjectNode shape = F.objectNode();
            for (String key : List.of("stockCode", "market", "tradingDate", "price", "sellPrice")) shape.set(key, q.has(key) ? q.get(key).deepCopy() : NullNode.instance);
            quoteVt = shape;
        }
        String session = null;
        if (us != null && us.path("calendarVerified").isBoolean() && us.path("calendarVerified").booleanValue()
                && us.path("completedSessions").isArray() && !us.path("completedSessions").isEmpty()) {
            JsonNode last = us.path("completedSessions").get(us.path("completedSessions").size() - 1);
            if (last.isTextual()) session = last.textValue();
        }
        boolean fxVerified = "VERIFIED".equals(vector.path("fx").path("validation").asText());
        JsonNode mids = sources.path("usd-twd-month-mids");
        boolean midsVerified = "VERIFIED".equals(vector.path("usd-twd-month-mids").path("validation").asText()) && "VERIFIED".equals(mids.path("status").asText());
        ObjectNode fx = F.objectNode();
        JsonNode rate = sources.path("fx");BigDecimal buy = num(rate.get("buyRate")), sell = num(rate.get("sellRate"));
        if (fxVerified && buy != null && sell != null) {
            ObjectNode spot = fx.putObject("spot");spot.set("date", rate.has("date") ? rate.get("date").deepCopy() : NullNode.instance);
            spot.set("quoteStatus", rate.has("quoteStatus") ? rate.get("quoteStatus").deepCopy() : NullNode.instance);
            spot.set("sellRate", rate.get("sellRate").deepCopy());
            spot.put("midRate", SrppReportModuleCalculator.dec(buy.add(sell).divide(TWO, 4, RoundingMode.HALF_UP)));
        } else fx.putNull("spot");
        ArrayNode history = fx.putArray("history");
        if (midsVerified && mids.path("rows").isArray()) mids.path("rows").forEach(row -> history.addObject().put("date", row.path("date").asText()).set("midRate", row.path("midRate").deepCopy()));
        JsonNode fills = sources.path("satellite-build-fills");
        boolean fillsVerified = fills.isObject() && "VERIFIED".equals(fills.path("status").asText()) && fills.path("verified").asBoolean()
                && "VERIFIED".equals(vector.path("satellite-build-fills").path("validation").asText())
                && SrppReportModuleCalculator.ownerSource(fills, f, sources.path("policy").path("bundleHash").asText());
        return new Inputs(date, quote009826, quoteVt, session, sources.path("assets"), fx, fillsVerified ? fills : F.objectNode(), fxVerified, midsVerified);
    }

    /** Same complete-book conditions as the BFF public quote projection, evaluated at the frozen capture clock. */
    static boolean approvedCompleteBook(JsonNode book, LocalDate tradingDate, Instant capturedAt) {
        try {
            if (book == null || !book.isObject() || !book.path("supported").asBoolean(false) || !book.path("available").asBoolean(false)
                    || !Set.of("FUBON_BOOKS", "YAHOO_TW").contains(book.path("source").asText())
                    || !"OPEN".equals(book.path("marketStatus").asText())
                    || !finitePositive(book.get("price")) || !finitePositive(book.get("previousClose"))) return false;
            Instant at = Instant.parse(book.path("sourceTime").asText());
            if (at.isAfter(capturedAt) || !at.atZone(TAIPEI).toLocalDate().equals(tradingDate)) return false;
            JsonNode levels = book.path("levels");
            if (!levels.isArray() || levels.size() != 5) return false;
            BigDecimal previousBid = null, previousAsk = null;
            for (int index = 0; index < 5; index++) {
                JsonNode level = levels.get(index);
                if (!level.isObject() || !level.path("level").isIntegralNumber() || level.path("level").asInt() != index + 1) return false;
                BigDecimal bid = num(level.get("bidPrice")), ask = num(level.get("askPrice")), bidSize = num(level.get("bidVolumeLots")), askSize = num(level.get("askVolumeLots"));
                if (bid == null || ask == null || bidSize == null || askSize == null || bid.signum() <= 0 || ask.signum() <= 0 || bidSize.signum() <= 0 || askSize.signum() <= 0) return false;
                if (previousBid != null && previousBid.compareTo(bid) <= 0 || previousAsk != null && previousAsk.compareTo(ask) >= 0) return false;
                previousBid = bid;previousAsk = ask;
            }
            return true;
        } catch (RuntimeException invalid) { return false; }
    }

    private record Pace(boolean ok, BigDecimal day, BigDecimal week, BigDecimal month) {}

    private static Pace pace(JsonNode transactions, LocalDate today, LocalDate monthStart, LocalDate weekStart, String code, String market, boolean vt) {
        JsonNode selection = transactions != null && transactions.isObject() ? transactions.get("selection") : null;
        JsonNode records = transactions != null && transactions.isObject() ? transactions.get("records") : null;
        JsonNode start = selection != null && selection.isObject() ? selection.get("start") : null;
        JsonNode end = selection != null && selection.isObject() ? selection.get("end") : null;
        BigDecimal day = BigDecimal.ZERO, week = BigDecimal.ZERO, month = BigDecimal.ZERO;
        String tradingDate = today.toString(), monthIso = monthStart.toString(), weekIso = weekStart.toString();
        String earliest = (monthStart.isBefore(weekStart) ? monthStart : weekStart).toString();
        boolean ok = isoDate(start) && isoDate(end) && start.textValue().compareTo(earliest) <= 0
                && end.textValue().compareTo(tradingDate) >= 0 && records != null && records.isArray();
        if (ok) for (JsonNode r : records) {
            if (!r.isObject()) { ok = false; continue; }
            if (!is(r.get("assetCode"), code) || !is(r.get("market"), market) || !is(r.get("transactionType"), "買")) continue;
            JsonNode shares = r.get("shares"), amount = r.get(vt ? "amountTwd" : "amount"), date = r.get("tradeDate");
            // amountTwd falls back to the USD amount when a USD fill has no rate; that would understate pace.
            boolean usdRateOk = !vt || !is(r.get("currency"), "USD") || finitePositive(r.get("exchangeRate"));
            if (!(finitePositive(shares) && finitePositive(amount) && isoDate(date) && usdRateOk)) { ok = false; continue; }
            String d = date.textValue();
            if (d.compareTo(tradingDate) > 0) continue;
            BigDecimal quantity = vt ? num(shares) : num(shares).divide(LOT, MC);
            if (d.compareTo(monthIso) >= 0) month = month.add(num(amount));
            if (d.compareTo(weekIso) >= 0) week = week.add(quantity);
            if (d.equals(tradingDate)) day = day.add(quantity);
        }
        return new Pace(ok, day, week, month);
    }

    private record Fx(boolean halved, boolean verified, BigDecimal max, BigDecimal cap, BigDecimal baseline) {}

    private static Fx fx(List<BigDecimal> usdTwdRates, JsonNode policy) {
        BigDecimal max = null;
        if (usdTwdRates != null) for (BigDecimal rate : usdTwdRates) if (rate != null && rate.signum() > 0 && (max == null || rate.compareTo(max) > 0)) max = rate;
        boolean halved = max == null || max.compareTo(num(policy.get("fx_halving_threshold"))) > 0;
        BigDecimal factor = halved ? HALF : BigDecimal.ONE;
        return new Fx(halved, max != null, max, num(policy.get("monthly_cap_twd")).multiply(factor), num(policy.get("monthly_baseline_twd")).multiply(factor));
    }

    private static void fxEvidence(ObjectNode e, Fx fx) {
        e.put("fx_halved", fx.halved);e.put("fx_status", fx.verified ? "VERIFIED" : "UNVERIFIED_ASSUMED_HALVED");
        decimal(e, "fx_month_max_mid", fx.max);
        e.put("monthly_cap_twd_effective", fx.cap);e.put("monthly_baseline_twd_effective", fx.baseline);
    }

    private static void period(JsonNode policy, String tradingDate, List<String> blocks) {
        String windowEnd = policy.get("window").textValue().split(" to ")[1];
        if (tradingDate.compareTo(policy.get("earliest_buy_date").textValue()) < 0) blocks.add("BEFORE_EARLIEST_BUY_DATE");
        if (tradingDate.compareTo(windowEnd) > 0) blocks.add("BUILD_WINDOW_CLOSED");
    }

    private static long minFloor(Map<String, BigDecimal> room) {
        long min = Long.MAX_VALUE;
        for (BigDecimal value : room.values()) min = Math.min(min, value.add(EPS).setScale(0, RoundingMode.FLOOR).longValueExact());
        return min;
    }

    private static void reasons(ObjectNode out, List<String> blocks) {
        ArrayNode reasons = out.putArray("reasons");
        if (blocks.isEmpty()) reasons.add("WITHIN_PACE_AND_TARGET"); else blocks.forEach(reasons::add);
    }

    private static BigDecimal held(JsonNode assets, String code, String market) {
        JsonNode live = assets != null && assets.isObject() ? assets.get("liveAssets") : null;
        JsonNode stocks = live != null && live.isObject() ? live.get("stocks") : null;
        if (stocks == null || !stocks.isArray()) return null;
        BigDecimal sum = BigDecimal.ZERO;
        for (JsonNode row : stocks) {
            if (!row.isObject() || !is(row.get("stockCode"), code) || !is(row.get("market"), market)) continue;
            BigDecimal value = num(row.get("liveValue"));
            if (value == null || value.signum() < 0) return null;
            sum = sum.add(value);
        }
        return sum;
    }

    /** Non-boolean JSON number or canonical decimal string; anything else is unknown. */
    static BigDecimal num(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode() || n.isBoolean()) return null;
        try {
            if (n.isNumber()) return n.decimalValue();
            if (n.isTextual() && SrppDecimal.isCanonical(n.textValue())) return new BigDecimal(n.textValue());
        } catch (NumberFormatException invalid) { return null; }
        return null;
    }

    static boolean finitePositive(JsonNode n) { BigDecimal value = num(n); return value != null && value.signum() > 0; }
    private static boolean heldValid(JsonNode n) { BigDecimal value = num(n); return value != null && value.signum() >= 0; }
    private static boolean is(JsonNode n, String expected) { return n != null && n.isTextual() && expected.equals(n.textValue()); }
    static boolean isoDate(JsonNode n) { return n != null && n.isTextual() && isoDate(n.textValue()); }
    static boolean isoDate(String value) { try { return LocalDate.parse(value).toString().equals(value); } catch (RuntimeException invalid) { return false; } }
    private static JsonNode echo(JsonNode n) { return n == null ? NullNode.instance : n.deepCopy(); }
    private static void decimal(ObjectNode n, String key, BigDecimal value) { if (value == null) n.putNull(key); else n.put(key, value); }
}
