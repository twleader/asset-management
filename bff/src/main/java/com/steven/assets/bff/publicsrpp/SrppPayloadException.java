package com.steven.assets.bff.publicsrpp;

/**
 * business 2xx 回應未通過 {@link SrppDailyContextResponseValidator}。訊息只描述違反的 JSON 路徑與規則，
 * 不含帳號或金額；對外一律轉成 502 {@code UPSTREAM_INVALID}。
 */
public class SrppPayloadException extends RuntimeException {
    public SrppPayloadException(String message) {
        super(message);
    }

    public SrppPayloadException(String message, Throwable cause) {
        super(message, cause);
    }
}
