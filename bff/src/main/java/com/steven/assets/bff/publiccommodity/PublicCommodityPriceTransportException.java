package com.steven.assets.bff.publiccommodity;

/** The public commodity bridge could not establish or keep its one business request. */
public class PublicCommodityPriceTransportException extends RuntimeException {
    public PublicCommodityPriceTransportException() {
        super("public commodity price transport unavailable");
    }
}
