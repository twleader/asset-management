package com.steven.assets.bff.tradingradar;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 已驗證的交易雷達 JSON 合約。
 *
 * <p>不保留 upstream status、Content-Type 或 raw bytes。controller 只會把 detached JSON tree
 * 以自己的 {@code 200 application/json} 合約輸出。</p>
 */
public record PublicTradingRadarRelay(JsonNode body) {

    public PublicTradingRadarRelay {
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
