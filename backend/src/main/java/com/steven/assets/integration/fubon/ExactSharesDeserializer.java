package com.steven.assets.integration.fubon;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;

public final class ExactSharesDeserializer extends JsonDeserializer<Long> {
    static final long MAX_SHARES = 9_999_999_999L;

    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            return (Long) context.handleUnexpectedToken(Long.class, parser);
        }
        long value;
        try {
            value = parser.getLongValue();
        } catch (Exception exception) {
            return (Long) context.handleWeirdNumberValue(Long.class, parser.getNumberValue(), "INTEGER_OVERFLOW");
        }
        if (value < 1 || value > MAX_SHARES) {
            return (Long) context.handleWeirdNumberValue(Long.class, value, "SHARES_RANGE_EXCEEDED");
        }
        return value;
    }
}
