package com.steven.assets.bff.publiccalendar;

import com.fasterxml.jackson.databind.JsonNode;

/** Validated, detached JSON contract for a successful no-tenant calendar response. */
public record PublicTradingCalendarRelay(JsonNode body) {
    public PublicTradingCalendarRelay {
        if (body == null || !body.isObject()) {
            throw new IllegalArgumentException("relay body must be a JSON object");
        }
        body = body.deepCopy();
    }

    @Override
    public JsonNode body() {
        return body.deepCopy();
    }
}
