package com.steven.assets.bff.publiccalendar;

/** A successful downstream status carried a body that is not the documented public calendar contract. */
public class PublicTradingCalendarPayloadException extends RuntimeException {
    public PublicTradingCalendarPayloadException() {
        super("public trading calendar payload is invalid");
    }
}
