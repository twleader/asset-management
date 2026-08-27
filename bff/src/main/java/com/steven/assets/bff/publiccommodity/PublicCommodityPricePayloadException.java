package com.steven.assets.bff.publiccommodity;

/** A successful business response did not satisfy the closed public commodity contract. */
public class PublicCommodityPricePayloadException extends RuntimeException {
    public PublicCommodityPricePayloadException() {
        super("public commodity price payload is invalid");
    }
}
