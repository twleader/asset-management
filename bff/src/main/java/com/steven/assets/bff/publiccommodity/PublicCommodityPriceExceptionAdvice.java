package com.steven.assets.bff.publiccommodity;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ServerWebExchange;
import static com.steven.assets.bff.apierrorlogs.PublicApiErrorCaptureWebFilter.capture;

import java.net.URI;

/** Scoped, highest-priority sanitizer that must win over the global raw business error relay. */
@RestControllerAdvice(assignableTypes = PublicCommodityPriceController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
public class PublicCommodityPriceExceptionAdvice {

    @ExceptionHandler(PublicCommodityPriceRequestException.class)
    public ResponseEntity<ProblemDetail> invalid(PublicCommodityPriceRequestException ignored, ServerWebExchange exchange) {
        capture(exchange, ignored);
        return problem(HttpStatus.BAD_REQUEST, "Invalid commodity price request", "不支援 query parameter 或 request body");
    }

    @ExceptionHandler(PublicCommodityPriceTimeoutException.class)
    public ResponseEntity<ProblemDetail> timeout(PublicCommodityPriceTimeoutException ignored, ServerWebExchange exchange) {
        capture(exchange, ignored);
        return problem(HttpStatus.GATEWAY_TIMEOUT, "Commodity prices timeout", "商品報價服務逾時");
    }

    @ExceptionHandler(PublicCommodityPricePayloadException.class)
    public ResponseEntity<ProblemDetail> invalidPayload(PublicCommodityPricePayloadException ignored, ServerWebExchange exchange) {
        capture(exchange, ignored);
        return problem(HttpStatus.BAD_GATEWAY, "Commodity prices downstream failure", "商品報價暫時無法取得");
    }

    @ExceptionHandler(PublicCommodityPriceTransportException.class)
    public ResponseEntity<ProblemDetail> transport(PublicCommodityPriceTransportException ignored, ServerWebExchange exchange) {
        capture(exchange, ignored);
        return problem(HttpStatus.SERVICE_UNAVAILABLE,
                "Commodity prices service unavailable", "商品報價服務暫時無法連線");
    }

    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<ProblemDetail> leakedResponse(WebClientResponseException ignored, ServerWebExchange exchange) {
        capture(exchange, ignored);
        return problem(HttpStatus.BAD_GATEWAY, "Commodity prices downstream failure", "商品報價暫時無法取得");
    }

    @ExceptionHandler(WebClientException.class)
    public ResponseEntity<ProblemDetail> leakedTransport(WebClientException ignored, ServerWebExchange exchange) {
        capture(exchange, ignored);
        return problem(HttpStatus.SERVICE_UNAVAILABLE,
                "Commodity prices service unavailable", "商品報價服務暫時無法連線");
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String title, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
        body.setType(URI.create("about:blank"));
        body.setTitle(title);
        body.setInstance(URI.create(PublicCommodityPriceController.PATH));
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }
}
