package com.steven.assets.bff.gdptwse;

/** 公開股市大盤 API 的 query validation 錯誤。 */
final class PublicMarketIndexRequestException extends IllegalArgumentException {

    PublicMarketIndexRequestException(String message) {
        super(message);
    }
}
