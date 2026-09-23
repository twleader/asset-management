package com.steven.assets.service;

/** Bounded manual-refresh capacity is temporarily exhausted. */
public class TradingRadarRefreshUnavailableException extends RuntimeException {
    public TradingRadarRefreshUnavailableException() {
        super("行情更新工作暫時額滿，請稍後再試");
    }
}
