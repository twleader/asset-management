package com.steven.assets.integration.fubon;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.type.LogicalType;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

/** Strict decoding belongs only to the new accounting observations, not existing broker DTOs. */
final class FubonAccountingJson {
    private FubonAccountingJson() {}

    static ObjectMapper mapper() {
        ObjectMapper mapper = JsonMapper.builder().findAndAddModules()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .build();
        mapper.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        return mapper;
    }

    static final class LocalDateDeserializer extends JsonDeserializer<LocalDate> {
        @Override
        public LocalDate deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                return (LocalDate) context.handleUnexpectedToken(LocalDate.class, parser);
            }
            String raw = parser.getText();
            try {
                if (!raw.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException();
                return LocalDate.parse(raw);
            } catch (IllegalArgumentException | DateTimeParseException exception) {
                return (LocalDate) context.handleWeirdStringValue(LocalDate.class, raw, "INVALID_QUERY_DATE");
            }
        }
    }

    static final class InstantDeserializer extends JsonDeserializer<Instant> {
        @Override
        public Instant deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_STRING) {
                return (Instant) context.handleUnexpectedToken(Instant.class, parser);
            }
            String raw = parser.getText();
            try {
                if (!raw.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-5][0-9](\\.[0-9]{1,9})?Z")) {
                    throw new IllegalArgumentException();
                }
                return Instant.parse(raw);
            } catch (IllegalArgumentException | DateTimeParseException exception) {
                return (Instant) context.handleWeirdStringValue(Instant.class, raw, "INVALID_OBSERVED_AT");
            }
        }
    }
}
