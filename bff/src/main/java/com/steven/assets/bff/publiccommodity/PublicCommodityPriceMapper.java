package com.steven.assets.bff.publiccommodity;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

/** Closed decoder from the internal business response to immutable public commodity DTOs. */
final class PublicCommodityPriceMapper {

    private static final Set<String> ROOT_FIELDS = Set.of("marketOpen", "quotes");
    private static final Set<String> SLOT_CODES = Set.of("WTI", "BRENT", "GOLD");
    private static final Set<String> QUOTE_FIELDS = Set.of(
            "commodityCode", "price", "change", "changePercent", "sessionDate", "quoteTime", "polledAt",
            "status", "dayHigh", "dayLow", "provider");
    private static final Set<String> STATUSES = Set.of("LIVE", "STALE", "SETTLED");
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));

    private PublicCommodityPriceMapper() {}

    static CommodityPriceBatchResponse decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            throw new PublicCommodityPricePayloadException();
        }
        try {
            JsonNode root = JSON.readTree(bytes);
            exactObject(root, ROOT_FIELDS);
            JsonNode marketOpen = field(root, "marketOpen");
            if (!marketOpen.isBoolean()) {
                invalid();
            }
            JsonNode quotes = field(root, "quotes");
            exactObject(quotes, SLOT_CODES);
            return new CommodityPriceBatchResponse(marketOpen.booleanValue(), new CommodityPriceSlots(
                    quote("WTI", field(quotes, "WTI")),
                    quote("BRENT", field(quotes, "BRENT")),
                    quote("GOLD", field(quotes, "GOLD"))));
        } catch (PublicCommodityPricePayloadException invalid) {
            throw invalid;
        } catch (RuntimeException | IOException invalid) {
            throw new PublicCommodityPricePayloadException();
        }
    }

    private static CommodityPriceQuote quote(String slot, JsonNode value) {
        if (value.isNull()) {
            return null;
        }
        exactObject(value, QUOTE_FIELDS);
        if (!slot.equals(requiredText(field(value, "commodityCode")))) {
            invalid();
        }

        BigDecimal price = positiveDecimal(field(value, "price"));
        BigDecimal change = nullableDecimal(field(value, "change"));
        BigDecimal changePercent = nullableDecimal(field(value, "changePercent"));
        if ((change == null) != (changePercent == null)) {
            invalid();
        }

        String status = requiredText(field(value, "status"));
        if (!STATUSES.contains(status)) {
            invalid();
        }
        String provider = requiredText(field(value, "provider"));
        if (provider.isBlank()) {
            invalid();
        }

        return new CommodityPriceQuote(
                slot,
                unit(slot),
                price,
                change,
                changePercent,
                requiredDate(field(value, "sessionDate")),
                requiredInstant(field(value, "quoteTime")),
                requiredInstant(field(value, "polledAt")),
                status,
                nullablePositiveDecimal(field(value, "dayHigh")),
                nullablePositiveDecimal(field(value, "dayLow")),
                provider);
    }

    private static String unit(String slot) {
        return switch (slot) {
            case "WTI", "BRENT" -> "USD_PER_BARREL";
            case "GOLD" -> "USD_PER_TROY_OUNCE";
            default -> throw new PublicCommodityPricePayloadException();
        };
    }

    private static void exactObject(JsonNode value, Set<String> fields) {
        if (value == null || !value.isObject() || value.size() != fields.size()) {
            invalid();
        }
        if (!value.properties().stream().allMatch(entry -> fields.contains(entry.getKey()))) {
            invalid();
        }
        for (String field : fields) {
            if (!value.has(field)) {
                invalid();
            }
        }
    }

    private static JsonNode field(JsonNode object, String name) {
        JsonNode value = object.get(name);
        if (value == null) {
            invalid();
        }
        return value;
    }

    private static String requiredText(JsonNode value) {
        if (!value.isTextual() || value.textValue() == null) {
            invalid();
        }
        return value.textValue();
    }

    private static BigDecimal positiveDecimal(JsonNode value) {
        BigDecimal decimal = decimal(value);
        if (decimal.signum() <= 0) {
            invalid();
        }
        return decimal;
    }

    private static BigDecimal nullablePositiveDecimal(JsonNode value) {
        if (value.isNull()) {
            return null;
        }
        return positiveDecimal(value);
    }

    private static BigDecimal nullableDecimal(JsonNode value) {
        return value.isNull() ? null : decimal(value);
    }

    private static BigDecimal decimal(JsonNode value) {
        if (!value.isNumber()) {
            invalid();
        }
        try {
            return new BigDecimal(value.asText());
        } catch (RuntimeException invalid) {
            throw new PublicCommodityPricePayloadException();
        }
    }

    private static LocalDate requiredDate(JsonNode value) {
        try {
            return LocalDate.parse(requiredText(value));
        } catch (RuntimeException invalid) {
            throw new PublicCommodityPricePayloadException();
        }
    }

    private static Instant requiredInstant(JsonNode value) {
        try {
            return Instant.parse(requiredText(value));
        } catch (RuntimeException invalid) {
            throw new PublicCommodityPricePayloadException();
        }
    }

    private static void invalid() {
        throw new PublicCommodityPricePayloadException();
    }
}
