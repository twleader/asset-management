package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * D-152 pooled all-bank settled-deposit post-buy headroom, ported from SRPP
 * {@code trade_blocker_policy.pooled_funding}. Not reduced by reservations or
 * pending orders: the consumer adds every same-report leg and fee into
 * {@code cashRequired}, and {@code cashRequired <= headroom} is exactly the
 * reference PASS. Pure: no Spring, clock, I/O or broker access.
 */
final class SrppPooledFunding {
    static final String BASIS = "ALL_BANK_SETTLED_DEPOSITS_D152";
    static final String NOT_SAME_ROUND = "POOLED_ASSETS_NOT_SAME_ROUND";
    static final String AMOUNT_UNAVAILABLE = "POOLED_DEPOSIT_AMOUNT_UNAVAILABLE:";
    static final String TERM_BELOW_FLOOR = "POOLED_TERM_BELOW_FLOOR";
    static final String TOTAL_BELOW_FLOOR = "POOLED_TOTAL_BELOW_FLOOR";
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final Set<String> TERM_TYPES = Set.of("定存", "美元定存");
    /** Same accepted shape as SRPP execution_policy.taipei_timestamp. */
    private static final Pattern TIMESTAMP = Pattern.compile(
            "(\\d{4}-\\d{2}-\\d{2})[T ](\\d{2}:\\d{2}:\\d{2})(?:\\.(\\d{1,9}))?"
                    + "(Z|[+-](?:[01]\\d|2[0-3]):[0-5]\\d)?"
                    + "(?:\\[(UTC|GMT|[A-Za-z_]+(?:/[A-Za-z0-9_+.-]+)+)\\])?");

    private SrppPooledFunding() {}

    record Result(String currency, String status, String reason,
                  BigDecimal settled, BigDecimal knownPayables, BigDecimal term,
                  BigDecimal termFloor, BigDecimal totalFloor, BigDecimal headroom) {}

    static Result evaluate(JsonNode assets, LocalDate tradingDate, String currency,
                           BigDecimal termFloorBase, BigDecimal totalFloorBase, BigDecimal inflationFactor) {
        if (!"TWD".equals(currency) && !"USD".equals(currency)) {
            throw new IllegalArgumentException("FUNDING_CURRENCY_UNSUPPORTED");
        }
        LocalDate round = taipeiDate(assets == null ? null : assets.get("generatedAt"));
        if (round == null || !round.equals(tradingDate)) {
            return unavailable(currency, NOT_SAME_ROUND);
        }
        String transit = "TRANSIT_" + currency;
        BigDecimal settled = BigDecimal.ZERO, payables = BigDecimal.ZERO, term = BigDecimal.ZERO;
        for (JsonNode row : assets.path("snapshot").path("deposits")) {
            JsonNode rawCurrency = row.get("currency");
            String rowCurrency = rawCurrency != null && rawCurrency.isTextual() ? rawCurrency.textValue() : null;
            if (!currency.equals(rowCurrency) && !transit.equals(rowCurrency)) continue;
            BigDecimal amount = number(row.get("TWD".equals(currency) ? "amount" : "originalAmount"));
            if (amount == null) return unavailable(currency, AMOUNT_UNAVAILABLE + row.path("id").asText());
            // Transit is decided by currency only; depositType is a display category.
            if (rowCurrency.startsWith("TRANSIT_")) {
                payables = payables.add(amount.min(BigDecimal.ZERO));
                continue;
            }
            settled = settled.add(amount);
            JsonNode type = row.get("depositType");
            if (type != null && type.isTextual() && TERM_TYPES.contains(type.textValue())) term = term.add(amount);
        }
        BigDecimal termFloor = "TWD".equals(currency) ? termFloorBase.multiply(inflationFactor) : BigDecimal.ZERO;
        BigDecimal totalFloor = "TWD".equals(currency) ? totalFloorBase.multiply(inflationFactor) : BigDecimal.ZERO;
        if (term.compareTo(termFloor) < 0) {
            return new Result(currency, "BLOCK", TERM_BELOW_FLOOR, settled, payables, term, termFloor, totalFloor, null);
        }
        BigDecimal headroom = settled.add(payables).subtract(totalFloor.max(termFloor));
        boolean pass = headroom.signum() >= 0;
        return new Result(currency, pass ? "PASS" : "BLOCK", pass ? BASIS : TOTAL_BELOW_FLOOR,
                settled, payables, term, termFloor, totalFloor, headroom);
    }

    private static Result unavailable(String currency, String reason) {
        return new Result(currency, "UNAVAILABLE", reason, null, null, null, null, null, null);
    }

    /** Explicit offset wins; no offset means Asia/Taipei (a bracketed zone is only a validated suffix). */
    static LocalDate taipeiDate(JsonNode value) {
        if (value == null || !value.isTextual()) return null;
        Matcher m = TIMESTAMP.matcher(value.textValue().strip());
        if (!m.matches()) return null;
        try {
            if (m.group(5) != null) ZoneId.of(m.group(5));
            String fraction = m.group(3) == null ? "" : "." + m.group(3);
            LocalDateTime local = LocalDateTime.parse(m.group(1) + "T" + m.group(2) + fraction);
            String offset = m.group(4);
            if (offset == null) return local.atZone(TAIPEI).toLocalDate();
            ZoneOffset zone = "Z".equals(offset) ? ZoneOffset.UTC : ZoneOffset.of(offset);
            return OffsetDateTime.of(local, zone).atZoneSameInstant(TAIPEI).toLocalDate();
        } catch (DateTimeException invalid) {
            return null;
        }
    }

    private static BigDecimal number(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode() || (!n.isNumber() && !n.isTextual())) return null;
        try {
            return new BigDecimal(n.asText());
        } catch (NumberFormatException invalid) {
            return null;
        }
    }
}
