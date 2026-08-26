package com.steven.assets.bff.publiccalendar;

/** Fixed timeout signal for the public no-tenant calendar read. */
public class PublicTradingCalendarTimeoutException extends RuntimeException {
    public PublicTradingCalendarTimeoutException() {
        super("public trading calendar timeout");
    }
}
