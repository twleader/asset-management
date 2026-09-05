package com.steven.assets.bff.tradingradar;

/** email selector 格式不合法；不得與既有 stockCode／market selector 錯誤混用。 */
public class PublicTradingRadarEmailRequestException extends RuntimeException {
    public PublicTradingRadarEmailRequestException() {
        super("invalid public trading radar email");
    }
}
