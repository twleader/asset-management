package com.steven.assets.bff.tradingradar;

/** 外部 selector 在任何 bootstrap／downstream 前被拒絕的固定訊號。 */
public class PublicTradingRadarRequestException extends RuntimeException {
    public PublicTradingRadarRequestException() {
        super("invalid public trading radar selector");
    }
}
