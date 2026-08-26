package com.steven.assets.bff.tradingradar;

/** Business 的合法 current-result miss；只保留公開 404 語意。 */
public class PublicTradingRadarStockNotFoundException extends RuntimeException {
    public PublicTradingRadarStockNotFoundException() {
        super("public trading radar stock unavailable");
    }
}
