package com.steven.assets.integration.fubon;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;

public final class ExactVolumeDeserializer extends JsonDeserializer<Long> {
    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            return (Long) context.handleUnexpectedToken(Long.class, parser);
        }
        try {
            long value = parser.getLongValue();
            if (value < 0) {
                return (Long) context.handleWeirdNumberValue(Long.class, value, "NEGATIVE_VOLUME");
            }
            return value;
        } catch (Exception exception) {
            return (Long) context.handleWeirdNumberValue(Long.class, parser.getNumberValue(), "VOLUME_OVERFLOW");
        }
    }
}
