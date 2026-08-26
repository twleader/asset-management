package com.steven.assets.bff.tradingradar;

/** A successful downstream status carried a body that is not the documented public radar contract. */
public class PublicTradingRadarPayloadException extends RuntimeException {
    public PublicTradingRadarPayloadException() {
        super("public trading radar payload is invalid");
    }
}
