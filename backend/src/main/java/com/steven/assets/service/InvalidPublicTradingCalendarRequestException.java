package com.steven.assets.service;

/** Invalid internal year selector; the global calendar authority must not be called. */
public class InvalidPublicTradingCalendarRequestException extends RuntimeException {
    public InvalidPublicTradingCalendarRequestException() {
        super("invalid public trading calendar year");
    }
}
