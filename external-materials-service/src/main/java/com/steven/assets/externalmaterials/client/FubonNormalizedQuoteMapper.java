package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.externalmaterials.client.PriceFetchClient.PriceResult;
import com.steven.assets.externalmaterials.service.MarketClock;
import com.steven.assets.externalmaterials.service.ProviderTimedPriceObservation;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

/** Strict second boundary for the Python adapter's normalized Taiwan quote wire contract. */
final class FubonNormalizedQuoteMapper {

    private static final Pattern CANONICAL_DECIMAL =
            Pattern.compile("^(0|[1-9][0-9]*)(?:\\.[0-9]+)?$");
    private static final String MARKET = "台股";
    private static final String SOURCE = "FUBON_INTRADAY";
    private static final String STATUS = "LIVE";

    private final Clock clock;

    FubonNormalizedQuoteMapper(Clock clock) {
        this.clock = clock;
    }

    ProviderTimedPriceObservation map(String requestedCode, JsonNode quote) {
        if (quote == null || !quote.isObject()) throw rejected("QUOTE_NOT_OBJECT");
        String stockCode = requiredText(quote, "stockCode");
        String stockName = requiredText(quote, "stockName").trim();
        if (!requestedCode.equals(stockCode)) throw rejected("WRONG_SYMBOL");
        if (stockName.isBlank() || stockName.equalsIgnoreCase(requestedCode)) {
            throw rejected("INVALID_STOCK_NAME");
        }
        if (!MARKET.equals(requiredText(quote, "market"))) throw rejected("INVALID_MARKET");
        if (!SOURCE.equals(requiredText(quote, "source"))) throw rejected("INVALID_SOURCE");
        if (!STATUS.equals(requiredText(quote, "quoteStatus"))) throw rejected("INVALID_STATUS");
        JsonNode closed = quote.get("closed");
        if (closed == null || !closed.isBoolean() || closed.booleanValue()) {
            throw rejected("INVALID_CLOSED_FLAG");
        }

        BigDecimal price = positiveDecimal(quote, "actualPrice", false);
        BigDecimal previousClose = positiveDecimal(quote, "previousClose", false);
        BigDecimal openPrice = positiveDecimal(quote, "openPrice", false);
        BigDecimal highPrice = positiveDecimal(quote, "highPrice", false);
        BigDecimal lowPrice = positiveDecimal(quote, "lowPrice", false);
        BigDecimal buyPrice = positiveDecimal(quote, "buyPrice", true);
        BigDecimal sellPrice = positiveDecimal(quote, "sellPrice", true);

        if (lowPrice.compareTo(openPrice.min(price)) > 0
                || highPrice.compareTo(openPrice.max(price)) < 0
                || lowPrice.compareTo(highPrice) > 0) {
            throw rejected("INVALID_OHLC_RELATION");
        }

        JsonNode volumeNode = quote.get("volume");
        if (volumeNode == null || !volumeNode.isIntegralNumber() || !volumeNode.canConvertToLong()) {
            throw rejected("INVALID_VOLUME");
        }
        long volume = volumeNode.longValue();
        if (volume < 0) throw rejected("INVALID_VOLUME");

        LocalDate tradingDate;
        Instant providerUpdatedAt;
        try {
            tradingDate = LocalDate.parse(requiredText(quote, "tradingDate"));
            providerUpdatedAt = Instant.parse(requiredText(quote, "updatedAt"));
        } catch (DateTimeParseException ex) {
            throw rejected("INVALID_PROVIDER_TIME");
        }
        LocalDate providerDate = providerUpdatedAt.atZone(MarketClock.TW_ZONE).toLocalDate();
        LocalDate today = clock.instant().atZone(MarketClock.TW_ZONE).toLocalDate();
        if (!tradingDate.equals(providerDate) || !tradingDate.equals(today)) {
            throw rejected("WRONG_PROVIDER_DATE");
        }
        if (providerUpdatedAt.isAfter(clock.instant().plusSeconds(30))) {
            throw rejected("FUTURE_PROVIDER_TIME");
        }

        PriceResult result = new PriceResult(
                stockCode,
                MARKET,
                price,
                null,
                null,
                SOURCE,
                stockName,
                buyPrice,
                sellPrice,
                openPrice,
                previousClose,
                highPrice,
                lowPrice,
                volume);
        return new ProviderTimedPriceObservation(result, tradingDate, providerUpdatedAt);
    }

    private static BigDecimal positiveDecimal(JsonNode object, String field, boolean nullable) {
        JsonNode value = object.get(field);
        if (nullable && (value == null || value.isNull())) return null;
        if (value == null || !value.isTextual()) throw rejected("INVALID_DECIMAL_TYPE");
        String text = value.textValue();
        if (!CANONICAL_DECIMAL.matcher(text).matches()) throw rejected("NON_CANONICAL_DECIMAL");
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(text);
        } catch (NumberFormatException ex) {
            throw rejected("INVALID_DECIMAL");
        }
        if (decimal.signum() <= 0) throw rejected("NON_POSITIVE_DECIMAL");
        if (decimal.precision() > 20) throw rejected("DECIMAL_PRECISION_EXCEEDED");
        if (decimal.scale() < 0 || decimal.scale() > 10) throw rejected("DECIMAL_SCALE_EXCEEDED");
        return decimal;
    }

    private static String requiredText(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw rejected("MISSING_OR_INVALID_" + field.toUpperCase());
        }
        return value.textValue();
    }

    private static MappingException rejected(String reason) {
        return new MappingException(reason);
    }

    static final class MappingException extends IllegalArgumentException {
        private final String reason;

        MappingException(String reason) {
            super(reason);
            this.reason = reason;
        }

        String reason() {
            return reason;
        }
    }
}
