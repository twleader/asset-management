package com.steven.assets.bff.srppmail;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** BFF 外部 token 已由 filter 驗證；此層只負責固定 internal relay 與上游契約檢查。 */
@Service
@RequiredArgsConstructor
public class SrppDailyReportMailService {
    private static final ObjectMapper STRICT = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    private final @Qualifier("businessServicesClient") WebClient business;
    @Value("${srpp.daily-report.internal-token:}") private String internalToken;
    @Value("${srpp.daily-report.owner-email:tw.leader@gmail.com}") private String owner;

    public Mono<ResponseEntity<byte[]>> submit(byte[] rawBody) { return call(HttpMethod.POST, "/internal/srpp/daily-report-mail", rawBody); }
    public Mono<ResponseEntity<byte[]>> find(String key) {
        if (!key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,199}")) return Mono.just(problem(HttpStatus.BAD_REQUEST, "INVALID_IDEMPOTENCY_KEY"));
        return call(HttpMethod.GET, "/internal/srpp/daily-report-mail/" + key, null);
    }

    private Mono<ResponseEntity<byte[]>> call(HttpMethod method, String path, byte[] body) {
        WebClient.RequestBodySpec request = business.method(method).uri(path)
                .header("X-SRPP-Daily-Report-Internal-Token", internalToken == null ? "" : internalToken)
                .header("X-SRPP-Daily-Report-Owner", owner);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
        return request.exchangeToMono(response -> response.bodyToMono(byte[].class).defaultIfEmpty(new byte[0]).map(bytes -> {
            HttpStatusCode status = response.statusCode();
            if (!isJson(response.headers().contentType().orElse(null)) || !valid(status, bytes)) return problem(HttpStatus.BAD_GATEWAY, "UPSTREAM_INVALID");
            return ResponseEntity.status(status).contentType(response.headers().contentType().orElseThrow()).header(HttpHeaders.CACHE_CONTROL, "no-store").body(bytes);
        })).onErrorReturn(problem(HttpStatus.BAD_GATEWAY, "UPSTREAM_INVALID"));
    }
    private static boolean valid(HttpStatusCode status, byte[] body) { try { JsonNode json = STRICT.readTree(body); if (json == null || !json.isObject()) return false; if (!status.is2xxSuccessful()) return json.hasNonNull("code") && json.hasNonNull("status"); return Set.of("status", "messageId", "sentAt", "from", "to", "subject", "htmlSha256", "textSha256", "idempotentReplay").stream().allMatch(json::hasNonNull) && "SENT".equals(json.path("status").asText()) && json.path("to").isArray() && json.path("to").size() == 1; } catch (Exception ignored) { return false; } }
    private static boolean isJson(MediaType type) { return type != null && "application".equalsIgnoreCase(type.getType()) && ("json".equalsIgnoreCase(type.getSubtype()) || type.getSubtype().endsWith("+json")); }
    private static ResponseEntity<byte[]> problem(HttpStatus status, String code) { return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).header(HttpHeaders.CACHE_CONTROL, "no-store").body(("{\"type\":\"about:blank\",\"title\":\"" + code + "\",\"status\":" + status.value() + ",\"code\":\"" + code + "\"}").getBytes(StandardCharsets.UTF_8)); }
}
