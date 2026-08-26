package com.steven.assets.bff.publictransaction;

/** A successful downstream status carried a body that is not the documented public ledger contract. */
public class PublicTransactionHistoryPayloadException extends RuntimeException {
    public PublicTransactionHistoryPayloadException() {
        super("public transaction history payload is invalid");
    }
}
