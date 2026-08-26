package com.steven.assets.service;

/** Internal current-read query is invalid; no ledger lookup may have happened yet. */
public class InvalidPublicTransactionHistoryRequestException extends RuntimeException {
    public InvalidPublicTransactionHistoryRequestException() {
        super("invalid public transaction history filter");
    }
}
