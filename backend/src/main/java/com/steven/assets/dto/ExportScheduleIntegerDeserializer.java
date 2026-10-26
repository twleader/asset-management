package com.steven.assets.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;

/** Schedule times must be JSON integers, rather than silently truncated fractional values. */
public final class ExportScheduleIntegerDeserializer extends StdDeserializer<Integer> {
    public ExportScheduleIntegerDeserializer() { super(Integer.class); }
    @Override public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            return (Integer) context.handleUnexpectedToken(Integer.class, parser);
        }
        return parser.getIntValue();
    }
}
