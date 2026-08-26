package com.steven.assets.bff.publictransaction;

/** Local strict filter validation failure; must occur before configured-admin bootstrap. */
public class PublicTransactionHistoryRequestException extends RuntimeException {
    public PublicTransactionHistoryRequestException() {
        super("invalid public transaction history filter");
    }
}
