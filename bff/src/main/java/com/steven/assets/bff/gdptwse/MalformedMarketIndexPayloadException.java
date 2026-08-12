package com.steven.assets.bff.gdptwse;

/** 下游大盤日線 payload 的成交金額不是可接受十進位值。 */
final class MalformedMarketIndexPayloadException extends RuntimeException {

    MalformedMarketIndexPayloadException(Object value, Throwable cause) {
        super("下游 tradeValue 非法：'" + String.valueOf(value) + "'", cause);
    }
}
