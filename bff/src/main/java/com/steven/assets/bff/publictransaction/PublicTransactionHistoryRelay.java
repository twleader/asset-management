package com.steven.assets.bff.publictransaction;

import com.fasterxml.jackson.databind.JsonNode;

/** Validated, detached JSON contract for a successful public ledger response. */
public record PublicTransactionHistoryRelay(JsonNode body) {
    public PublicTransactionHistoryRelay {
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
