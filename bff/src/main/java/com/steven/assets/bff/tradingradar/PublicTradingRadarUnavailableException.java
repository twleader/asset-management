package com.steven.assets.bff.tradingradar;

/** Configured admin bootstrap 不存在、失敗或不可信時的固定 fail-closed 訊號。 */
public class PublicTradingRadarUnavailableException extends RuntimeException {
    public PublicTradingRadarUnavailableException() {
        super("configured admin unavailable");
    }
}
