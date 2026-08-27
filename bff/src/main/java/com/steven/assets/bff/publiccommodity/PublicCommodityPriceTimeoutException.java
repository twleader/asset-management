package com.steven.assets.bff.publiccommodity;

/** Fixed five-second timeout signal for the public commodity bridge. */
public class PublicCommodityPriceTimeoutException extends RuntimeException {
    public PublicCommodityPriceTimeoutException() {
        super("public commodity price timeout");
    }
}
