package com.steven.assets.integration.fubon;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.regex.Pattern;

/** Strict canonical decimal-string boundary shared by portfolio and quote DTOs. */
@JsonDeserialize(using = CanonicalFubonDecimal.Deserializer.class)
public final class CanonicalFubonDecimal {

    private static final Pattern CANONICAL = Pattern.compile("^(0|[1-9][0-9]*)(\\.[0-9]+)?$");
    private static final Pattern SIGNED = Pattern.compile("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?$");
    private final BigDecimal value;

    private CanonicalFubonDecimal(BigDecimal value) {
        this.value = value;
    }

    public static CanonicalFubonDecimal parsePositive(String raw) {
        if (raw == null || !CANONICAL.matcher(raw).matches()) {
            throw new IllegalArgumentException("NON_CANONICAL_DECIMAL");
        }
        BigDecimal parsed = new BigDecimal(raw);
        if (parsed.signum() <= 0) throw new IllegalArgumentException("NON_POSITIVE_DECIMAL");
        if (parsed.precision() > 20) throw new IllegalArgumentException("DECIMAL_PRECISION_EXCEEDED");
        if (parsed.scale() < 0 || parsed.scale() > 10) {
            throw new IllegalArgumentException("DECIMAL_SCALE_EXCEEDED");
        }
        return new CanonicalFubonDecimal(parsed);
    }

    /** Bank balances and realized profit/loss allow zero through a component-only override. */
    public static CanonicalFubonDecimal parseNonNegative(String raw) {
        if (raw == null || !CANONICAL.matcher(raw).matches()) {
            throw new IllegalArgumentException("NON_CANONICAL_DECIMAL");
        }
        BigDecimal parsed = new BigDecimal(raw);
        if (parsed.signum() < 0) throw new IllegalArgumentException("NEGATIVE_DECIMAL");
        if (parsed.precision() > 20) throw new IllegalArgumentException("DECIMAL_PRECISION_EXCEEDED");
        if (parsed.scale() < 0 || parsed.scale() > 10) {
            throw new IllegalArgumentException("DECIMAL_SCALE_EXCEEDED");
        }
        return new CanonicalFubonDecimal(parsed);
    }

    /** Settlement amounts alone may be negative; the class-level positive rule is unchanged. */
    public static CanonicalFubonDecimal parseSigned(String raw) {
        if (raw == null || !SIGNED.matcher(raw).matches()) {
            throw new IllegalArgumentException("NON_CANONICAL_DECIMAL");
        }
        BigDecimal parsed = new BigDecimal(raw);
        if (raw.startsWith("-") && parsed.signum() == 0) {
            throw new IllegalArgumentException("NON_CANONICAL_DECIMAL");
        }
        if (parsed.precision() > 20) throw new IllegalArgumentException("DECIMAL_PRECISION_EXCEEDED");
        if (parsed.scale() < 0 || parsed.scale() > 10) {
            throw new IllegalArgumentException("DECIMAL_SCALE_EXCEEDED");
        }
        return new CanonicalFubonDecimal(parsed);
    }

    public BigDecimal value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CanonicalFubonDecimal decimal && value.equals(decimal.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
    }

    @Override
    public String toString() {
        return value.toPlainString();
    }

    static final class Deserializer extends JsonDeserializer<CanonicalFubonDecimal> {
        @Override
        public CanonicalFubonDecimal deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                return (CanonicalFubonDecimal) context.handleUnexpectedToken(
                        CanonicalFubonDecimal.class, parser);
            }
            try {
                return parsePositive(parser.getText());
            } catch (IllegalArgumentException exception) {
                return (CanonicalFubonDecimal) context.handleWeirdStringValue(
                        CanonicalFubonDecimal.class, parser.getText(), exception.getMessage());
            }
        }
    }

    /** Per-record-component override deserializer using {@link #parseNonNegative(String)}
     * (Requirement 130 / Task 395) -- see that method's javadoc for why this exists. */
    static final class NonNegativeDeserializer extends JsonDeserializer<CanonicalFubonDecimal> {
        @Override
        public CanonicalFubonDecimal deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                return (CanonicalFubonDecimal) context.handleUnexpectedToken(
                        CanonicalFubonDecimal.class, parser);
            }
            try {
                return parseNonNegative(parser.getText());
            } catch (IllegalArgumentException exception) {
                return (CanonicalFubonDecimal) context.handleWeirdStringValue(
                        CanonicalFubonDecimal.class, parser.getText(), exception.getMessage());
            }
        }
    }

    static final class SignedDeserializer extends JsonDeserializer<CanonicalFubonDecimal> {
        @Override
        public CanonicalFubonDecimal deserialize(JsonParser parser, DeserializationContext context)
                throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                return (CanonicalFubonDecimal) context.handleUnexpectedToken(CanonicalFubonDecimal.class, parser);
            }
            try {
                return parseSigned(parser.getText());
            } catch (IllegalArgumentException exception) {
                return (CanonicalFubonDecimal) context.handleWeirdStringValue(
                        CanonicalFubonDecimal.class, parser.getText(), exception.getMessage());
            }
        }
    }
}
