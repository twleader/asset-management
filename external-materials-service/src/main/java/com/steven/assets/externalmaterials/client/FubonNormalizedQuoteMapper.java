package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.externalmaterials.client.PriceFetchClient.PriceResult;
import com.steven.assets.externalmaterials.service.MarketClock;
import com.steven.assets.externalmaterials.service.ProviderTimedPriceObservation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
        return mapWithOrderBook(requestedCode, quote).observation();
    }

    /**
     * Maps the established actual-price observation first.  The same raw response can carry an
     * optional five-level book, but a malformed book must never reject that price observation.
     */
    MappedQuote mapWithOrderBook(String requestedCode, JsonNode quote) {
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
        ProviderTimedPriceObservation observation = new ProviderTimedPriceObservation(result, tradingDate, providerUpdatedAt);
        return new MappedQuote(observation, mapOrderBook(quote, observation));
    }

    private TwQuoteDetailFetchClient.QuoteDetailResult mapOrderBook(
            JsonNode quote, ProviderTimedPriceObservation observation) {
        try {
            JsonNode book = quote.get("orderBook");
            if (book == null || !book.isObject()) return null;
            Instant bookUpdatedAt = Instant.parse(requiredText(book, "bookUpdatedAt"));
            Instant now = clock.instant();
            LocalDate bookDate = bookUpdatedAt.atZone(MarketClock.TW_ZONE).toLocalDate();
            if (bookUpdatedAt.getNano() % 1_000 != 0 || bookUpdatedAt.isAfter(now)
                    || !bookDate.equals(now.atZone(MarketClock.TW_ZONE).toLocalDate())
                    || !bookDate.equals(observation.tradingDate())) {
                return null;
            }
            JsonNode levelsNode = book.get("levels");
            if (levelsNode == null || !levelsNode.isArray() || levelsNode.size() != 5) return null;
            List<TwQuoteDetailFetchClient.OrderBookLevel> levels = new ArrayList<>(5);
            Set<BigDecimal> bidPrices = new HashSet<>();
            Set<BigDecimal> askPrices = new HashSet<>();
            BigDecimal previousBid = null;
            BigDecimal previousAsk = null;
            for (int index = 0; index < 5; index++) {
                JsonNode level = levelsNode.get(index);
                if (level == null || !level.isObject() || !level.has("level")
                        || !level.get("level").isIntegralNumber()
                        || level.get("level").intValue() != index + 1) {
                    return null;
                }
                BookSide bid = bookSide(level, "bidPrice", "bidVolumeLots");
                BookSide ask = bookSide(level, "askPrice", "askVolumeLots");
                if (bid == null || ask == null
                        || !bidPrices.add(bid.price().stripTrailingZeros())
                        || !askPrices.add(ask.price().stripTrailingZeros())
                        || (previousBid != null && previousBid.compareTo(bid.price()) <= 0)
                        || (previousAsk != null && previousAsk.compareTo(ask.price()) >= 0)) {
                    return null;
                }
                levels.add(new TwQuoteDetailFetchClient.OrderBookLevel(
                        index + 1, bid.price(), bid.volumeLots(), ask.price(), ask.volumeLots()));
                previousBid = bid.price();
                previousAsk = ask.price();
            }

            PriceResult price = observation.result();
            BigDecimal change = price.price().subtract(price.previousClose());
            BigDecimal changePercent = percentage(change, price.previousClose());
            BigDecimal amplitude = price.highPrice() == null || price.lowPrice() == null
                    ? null : percentage(price.highPrice().subtract(price.lowPrice()), price.previousClose());
            Long inner = optionalNonNegativeLong(book, "innerVolumeLots");
            Long outer = optionalNonNegativeLong(book, "outerVolumeLots");
            return new TwQuoteDetailFetchClient.QuoteDetailResult(
                    price.stockCode(), price.stockName(), MARKET, true, true, "FUBON_BOOKS", null,
                    bookUpdatedAt, now, "OPEN", price.price(), price.previousClose(), price.openPrice(),
                    price.highPrice(), price.lowPrice(), optionalPositiveDecimal(book, "averagePrice"),
                    change, changePercent, optionalNonNegativeDecimal(book, "turnoverYi"), price.volume(),
                    null, amplitude, inner, outer, sharePercentage(inner, outer, true),
                    sharePercentage(inner, outer, false), total(levels, true), total(levels, false), List.copyOf(levels));
        } catch (RuntimeException invalidBook) {
            return null;
        }
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

    private static BigDecimal nonNegativeDecimal(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw rejected("INVALID_DECIMAL_TYPE");
        String text = value.textValue();
        if (!CANONICAL_DECIMAL.matcher(text).matches()) throw rejected("NON_CANONICAL_DECIMAL");
        BigDecimal decimal;
        try {
            decimal = new BigDecimal(text);
        } catch (NumberFormatException invalid) {
            throw rejected("INVALID_DECIMAL");
        }
        if (decimal.signum() < 0 || decimal.precision() > 20 || decimal.scale() < 0 || decimal.scale() > 10) {
            throw rejected("INVALID_NON_NEGATIVE_DECIMAL");
        }
        return decimal;
    }

    private static BigDecimal optionalPositiveDecimal(JsonNode object, String field) {
        try {
            return positiveDecimal(object, field, true);
        } catch (MappingException ignored) {
            return null;
        }
    }

    private static BigDecimal optionalNonNegativeDecimal(JsonNode object, String field) {
        try {
            return nonNegativeDecimal(object, field);
        } catch (MappingException ignored) {
            return null;
        }
    }

    private static Long optionalNonNegativeLong(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) return null;
        return value.longValue();
    }

    /** A best-five snapshot is useful only when both sides of every fixed level are positive. */
    private static BookSide bookSide(JsonNode level, String priceField, String lotsField) {
        JsonNode rawPrice = level.get(priceField);
        JsonNode rawLots = level.get(lotsField);
        boolean missingPrice = rawPrice == null || rawPrice.isNull();
        boolean missingLots = rawLots == null || rawLots.isNull();
        if (missingPrice || missingLots
                || !rawLots.isIntegralNumber() || !rawLots.canConvertToLong() || rawLots.longValue() <= 0) return null;
        try {
            return new BookSide(positiveDecimal(level, priceField, false), rawLots.longValue());
        } catch (MappingException invalid) {
            return null;
        }
    }

    private static BigDecimal percentage(BigDecimal numerator, BigDecimal denominator) {
        return numerator == null || denominator == null || denominator.signum() <= 0
                ? null : numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal sharePercentage(Long inner, Long outer, boolean useInner) {
        if (inner == null || outer == null) return null;
        try {
            long total = Math.addExact(inner, outer);
            if (total == 0) return null;
            BigDecimal innerPercent = BigDecimal.valueOf(inner).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
            return useInner ? innerPercent : BigDecimal.valueOf(100).setScale(2).subtract(innerPercent);
        } catch (ArithmeticException overflow) {
            return null;
        }
    }

    private static Long total(List<TwQuoteDetailFetchClient.OrderBookLevel> levels, boolean bid) {
        try {
            long total = 0;
            boolean present = false;
            for (TwQuoteDetailFetchClient.OrderBookLevel level : levels) {
                Long lots = bid ? level.bidVolumeLots() : level.askVolumeLots();
                if (lots != null) {
                    total = Math.addExact(total, lots);
                    present = true;
                }
            }
            return present ? total : null;
        } catch (ArithmeticException overflow) {
            return null;
        }
    }

    private record BookSide(BigDecimal price, Long volumeLots) {}

    record MappedQuote(
            ProviderTimedPriceObservation observation,
            TwQuoteDetailFetchClient.QuoteDetailResult orderBook) {}

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
