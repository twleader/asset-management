package com.steven.assets.bff.transaction;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.buffer.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/** Bounded inbound parsing and status-preserving relay. No source authentication is claimed here. */
@RestController
@RequestMapping(TradeReportImportBoundary.PREFIX)
public final class TradeReportImportBffController {
    static final int MAX_BODY_BYTES = 768 * 1024;
    private final WebClient business;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    public TradeReportImportBffController(@Qualifier("businessServicesClient") WebClient business) {
        this.business = business;
    }

    @PostMapping("/preview")
    public Mono<ResponseEntity<byte[]>> preview(ServerWebExchange exchange) { return relay(exchange, "preview"); }

    @PostMapping("/apply")
    public Mono<ResponseEntity<byte[]>> apply(ServerWebExchange exchange) { return relay(exchange, "apply"); }

    private Mono<ResponseEntity<byte[]>> relay(ServerWebExchange exchange, String operation) {
        return DataBufferUtils.join(exchange.getRequest().getBody(), MAX_BODY_BYTES)
                .map(buffer -> {
                    try { byte[] data = new byte[buffer.readableByteCount()]; buffer.read(data); return data; }
                    finally { DataBufferUtils.release(buffer); }
                })
                .defaultIfEmpty(new byte[0])
                .flatMap(bytes -> {
                    try {
                        var node = mapper.readTree(bytes);
                        if (node == null || !node.isObject()) return Mono.just(error(400, "INVALID_REPORT_REQUEST"));
                    } catch (Exception ex) { return Mono.just(error(400, "INVALID_REPORT_REQUEST")); }
                    return business.post().uri("/api/asset-transactions/report-import/" + operation)
                            .contentType(MediaType.APPLICATION_JSON).bodyValue(bytes)
                            .exchangeToMono(response -> response.bodyToMono(byte[].class).defaultIfEmpty(new byte[0])
                                    .map(body -> project(operation, response.statusCode().value(), body)));
                })
                .onErrorResume(DataBufferLimitException.class,
                        ex -> Mono.just(error(413, "REPORT_REQUEST_TOO_LARGE")));
    }

    ResponseEntity<byte[]> project(String operation, int status, byte[] body) {
        try {
            var parsed = mapper.readTree(body);
            if (!(parsed instanceof com.fasterxml.jackson.databind.node.ObjectNode node))
                return error(502, "INVALID_REPORT_RESPONSE");
            var conflicts = mapper.createArrayNode();
            var rows = node.path("rows");
            int newRowCount = 0;
            int feeUpdateRowCount = 0;
            boolean completeRows = rows.isArray() && !rows.isEmpty() && rows.size() <= 100;
            if (rows.isArray()) for (var row : rows) {
                boolean reason = !row.path("reason").isNull() && !row.path("reason").isMissingNode()
                        && !row.path("reason").asText().isBlank();
                if ("CONFLICT".equals(row.path("status").asText()) || reason) conflicts.add(row);
                String rowStatus = row.path("status").asText();
                boolean feeUpdate = "FEE_UPDATE_READY".equals(rowStatus);
                if ("NEW".equals(rowStatus)) newRowCount++;
                if (feeUpdate) feeUpdateRowCount++;
                completeRows &= row.isObject() && ("NEW".equals(rowStatus) || "ALREADY_IMPORTED".equals(rowStatus) || feeUpdate) && !reason;
            }
            String reportStatus = node.path("status").asText();
            boolean canApply = "preview".equals(operation) && status == 200 && completeRows && conflicts.isEmpty()
                    && (feeUpdateRowCount > 0 ? "FEE_UPDATE_READY".equals(reportStatus)
                            : "READY".equals(reportStatus) || "ALREADY_IMPORTED".equals(reportStatus))
                    && node.path("previewHash").isTextual() && !node.path("previewHash").asText().isBlank();
            node.set("conflictRows", conflicts);
            node.put("canApply", canApply);
            node.put("newRowCount", canApply ? newRowCount : 0);
            node.put("feeUpdateRowCount", canApply ? feeUpdateRowCount : 0);
            return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(mapper.writeValueAsBytes(node));
        } catch (Exception ignored) { return error(502, "INVALID_REPORT_RESPONSE"); }
    }

    private static ResponseEntity<byte[]> error(int status, String code) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(("{\"code\":\"" + code + "\",\"conflictRows\":[],\"canApply\":false,\"newRowCount\":0,\"feeUpdateRowCount\":0}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
