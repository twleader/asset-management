package com.steven.assets.bff.tradingradar;

/** 公開 current-read timeout，不洩漏 upstream target 或 exception。 */
public class PublicTradingRadarTimeoutException extends RuntimeException {
    public PublicTradingRadarTimeoutException() {
        super("public trading radar timeout");
    }
}
