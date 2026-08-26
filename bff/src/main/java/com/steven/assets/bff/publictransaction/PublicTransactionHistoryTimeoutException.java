package com.steven.assets.bff.publictransaction;

/** Fixed timeout signal that never exposes upstream details. */
public class PublicTransactionHistoryTimeoutException extends RuntimeException {
    public PublicTransactionHistoryTimeoutException() {
        super("public transaction history timeout");
    }
}
