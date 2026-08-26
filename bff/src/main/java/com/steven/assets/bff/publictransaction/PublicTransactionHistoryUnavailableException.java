package com.steven.assets.bff.publictransaction;

/** Configured owner cannot be selected or the internal service cannot be reached. */
public class PublicTransactionHistoryUnavailableException extends RuntimeException {
    public PublicTransactionHistoryUnavailableException() {
        super("public transaction history unavailable");
    }
}
