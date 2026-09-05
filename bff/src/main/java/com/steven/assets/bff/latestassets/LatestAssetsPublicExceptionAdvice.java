package com.steven.assets.bff.latestassets;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/** bootstrap 沒有可信 owner 時回可判讀的 non-200；business downstream errors 保留原本語意。 */
@RestControllerAdvice(assignableTypes = LatestAssetsPublicController.class)
public class LatestAssetsPublicExceptionAdvice {
    @ExceptionHandler(LatestAssetsUnavailableException.class)
    public ResponseEntity<ProblemDetail> unavailable(LatestAssetsUnavailableException ex) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        p.setTitle("Latest assets unavailable");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(p);
    }

    /** email 參數格式不合法（Requirement 140）：在呼叫 business 前就已拒絕，回 400。 */
    @ExceptionHandler(LatestAssetsRequestException.class)
    public ResponseEntity<ProblemDetail> invalidRequest(LatestAssetsRequestException ex) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "email 格式不合法");
        p.setTitle("Invalid latest assets request");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(p);
    }

    @ExceptionHandler(LatestAssetsPayloadException.class)
    public ResponseEntity<ProblemDetail> malformedPayload(LatestAssetsPayloadException ex) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, ex.getMessage());
        p.setTitle("Invalid latest assets payload");
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(p);
    }

    /** business 的 404/ProblemDetail 不能被 BFF 包裝成 200 或一律 500。 */
    @ExceptionHandler(WebClientResponseException.class)
    public ResponseEntity<byte[]> downstream(WebClientResponseException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .header("Content-Type", ex.getHeaders().getFirst("Content-Type") == null
                        ? "application/problem+json" : ex.getHeaders().getFirst("Content-Type"))
                .body(ex.getResponseBodyAsByteArray());
    }
}
