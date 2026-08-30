package com.steven.assets.integration.fubon;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.service.MarketDataService.EtfHolding;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates the adapter's normalized v1 market-data envelope, never a proprietary SDK object. */
public final class FubonEtfHoldingsParser {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");
    private static final Pattern ETF_CODE = Pattern.compile("00[0-9]{2,3}(?:[0-9A-Z])?");
    private static final Pattern DECIMAL = Pattern.compile("(0|[1-9][0-9]*)(\\.[0-9]+)?");
    private static final Pattern ISO_DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
    private static final Set<String> ENVELOPE_FIELDS = Set.of("schemaVersion", "stockCode", "sourceDate", "holdings");
    private static final Set<String> HOLDING_FIELDS = Set.of("stockCode", "stockName", "weight", "shares");

    private FubonEtfHoldingsParser() {}

    public static boolean validEtfCode(String code) {
        return code != null && !"0000".equals(code) && ETF_CODE.matcher(code).matches();
    }

    public static Optional<Parsed> parse(String expectedCode, String normalizedJson) {
        return parse(expectedCode, normalizedJson, LocalDate.now(TW_ZONE));
    }

    static Optional<Parsed> parse(String expectedCode, String normalizedJson, LocalDate today) {
        if (!validEtfCode(expectedCode) || normalizedJson == null || normalizedJson.isBlank() || today == null) {
            return Optional.empty();
        }
        try {
            JsonNode root = MAPPER.readTree(normalizedJson);
            if (!exactFields(root, ENVELOPE_FIELDS)
                    || !root.path("schemaVersion").isIntegralNumber()
                    || !root.path("schemaVersion").canConvertToInt()
                    || root.path("schemaVersion").intValue() != 1
                    || !expectedCode.equals(text(root.get("stockCode")))
                    || !root.path("holdings").isArray()) {
                return Optional.empty();
            }
            JsonNode rows = root.get("holdings");
            JsonNode dateNode = root.get("sourceDate");
            LocalDate sourceDate = null;
            if (!dateNode.isNull()) {
                String date = text(dateNode);
                if (date == null || !ISO_DATE.matcher(date).matches()) return Optional.empty();
                sourceDate = LocalDate.parse(date);
                if (sourceDate.getYear() < 1 || !sourceDate.toString().equals(date) || sourceDate.isAfter(today)) return Optional.empty();
            } else if (!rows.isEmpty()) {
                return Optional.empty();
            }
            List<EtfHolding> holdings = new ArrayList<>();
            for (JsonNode row : rows) {
                if (!exactFields(row, HOLDING_FIELDS)) return Optional.empty();
                String code = text(row.get("stockCode"));
                String name = text(row.get("stockName"));
                BigDecimal weight = decimal(row.get("weight"));
                JsonNode sharesNode = row.get("shares");
                BigDecimal shares = sharesNode.isNull() ? null : decimal(sharesNode);
                if (code == null || name == null || weight == null
                        || weight.compareTo(BigDecimal.valueOf(100)) > 0
                        || (!sharesNode.isNull() && shares == null)) {
                    return Optional.empty();
                }
                holdings.add(new EtfHolding(code, name, weight, shares));
            }
            return Optional.of(new Parsed(sourceDate, holdings));
        } catch (JsonProcessingException | RuntimeException exception) {
            // Error text/payload may contain sensitive input. Callers expose only a fixed reason.
            return Optional.empty();
        }
    }

    private static boolean exactFields(JsonNode node, Set<String> allowed) {
        if (node == null || !node.isObject()) return false;
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        return actual.equals(allowed);
    }

    private static String text(JsonNode node) {
        if (node == null || !node.isTextual()) return null;
        String value = node.textValue();
        return value.isBlank() || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl) ? null : value;
    }

    private static BigDecimal decimal(JsonNode node) {
        String value = text(node);
        if (value == null || value.length() > 32 || !DECIMAL.matcher(value).matches()) return null;
        BigDecimal parsed = new BigDecimal(value);
        return parsed.precision() <= 20 && parsed.scale() <= 10 ? parsed : null;
    }

    public record Parsed(LocalDate sourceDate, List<EtfHolding> holdings) {
        public Parsed {
            holdings = List.copyOf(holdings);
        }
    }
}
