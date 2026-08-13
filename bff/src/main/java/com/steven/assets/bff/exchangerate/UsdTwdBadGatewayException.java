package com.steven.assets.bff.exchangerate;

/** business transport／decode／payload validation 的對外 fail-closed 錯誤。 */
final class UsdTwdBadGatewayException extends RuntimeException {

    UsdTwdBadGatewayException(String message) {
        super(message);
    }

    UsdTwdBadGatewayException(String message, Throwable cause) {
        super(message, cause);
    }
}
