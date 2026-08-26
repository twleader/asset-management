package com.steven.assets.bff.publiccalendar;

/** Requested calendar year is absent, repeated, malformed, or outside the Taipei three-year window. */
public class PublicTradingCalendarRequestException extends RuntimeException {
    public PublicTradingCalendarRequestException() {
        super("invalid public trading calendar year");
    }
}
