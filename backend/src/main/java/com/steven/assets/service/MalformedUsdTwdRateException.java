package com.steven.assets.service;

/** Redis／DB 的 USD/TWD payload 不符合固定資料完整性契約。 */
public class MalformedUsdTwdRateException extends RuntimeException {

    public MalformedUsdTwdRateException(String message) {
        super(message);
    }

    public MalformedUsdTwdRateException(String message, Throwable cause) {
        super(message, cause);
    }
}
