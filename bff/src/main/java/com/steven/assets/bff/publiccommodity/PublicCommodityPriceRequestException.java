package com.steven.assets.bff.publiccommodity;

/** The exact public route accepts neither named query parameters nor a GET request body. */
public class PublicCommodityPriceRequestException extends RuntimeException {
    public PublicCommodityPriceRequestException() {
        super("invalid public commodity price request");
    }
}
