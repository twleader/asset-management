package com.steven.assets.bff.publicsrpp;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.server.UnsupportedMediaTypeStatusException;
import reactor.core.publisher.Mono;

import static com.steven.assets.bff.apierrorlogs.PublicApiErrorCaptureWebFilter.capture;

/**
 * Exact private-network 9090 POST relay. It forwards raw JSON bytes and never calls a vendor.
 *
 * <p>Task 484.1／484.2：不以 {@code consumes} 宣告媒體型別（不符時例外在 handler mapping 階段拋出，帶 selector 的
 * advice 抓不到，會回 Spring 預設 415 本體）。入口自行檢查 Content-Type，參數解析階段的例外與未預期例外由
 * 控制器本地 {@code @ExceptionHandler} 統一輸出七欄 RFC 9457 problem，{@code instance} 為該請求的公開路徑。
 *
 * <p>Task 481.1：{@code event()} 在 415／空 body 之後交給 {@link SrppEventEvidenceCapture}（嚴格解析與 ownerEmail 400、
 * owner 解析、帶 owner headers 轉送原始 bytes、回應驗證）；{@code decision()} 維持原樣轉送，由 t482 另定。
 */
@RestController @RequiredArgsConstructor
public class SrppCaptureController {
    public static final String EVENT = "/api/public/srpp/event-evidence/capture";
    public static final String DECISION = "/api/public/srpp/daily-decision/evaluate";
    private static final Logger log = LoggerFactory.getLogger(SrppCaptureController.class);
    private final SrppCaptureRelay relay;
    private final SrppEventEvidenceCapture eventEvidence;
    @PostMapping(EVENT) public Mono<ResponseEntity<byte[]>> event(@RequestHeader(value=HttpHeaders.CONTENT_TYPE, required=false) String contentType, @RequestBody(required=false) Mono<byte[]> body){return eventChecked(contentType,body);}
    @PostMapping(DECISION) public Mono<ResponseEntity<byte[]>> decision(@RequestHeader(value=HttpHeaders.CONTENT_TYPE, required=false) String contentType, @RequestBody(required=false) Mono<byte[]> body){return relayChecked(contentType,body,"/internal/srpp/daily-decision/evaluate",DECISION);}

    /** Content-Type 先於 body 檢查：缺 Content-Type 且 body 為空仍是 415；通過後空白 body 才是 400。 */
    private Mono<ResponseEntity<byte[]>> relayChecked(String contentType, Mono<byte[]> body, String path, String instance) {
        if (!isApplicationJson(contentType)) return Mono.just(SrppCaptureRelay.problem(SrppCaptureProblemCatalog.UNSUPPORTED_MEDIA_TYPE, instance));
        return (body == null ? Mono.<byte[]>empty() : body).defaultIfEmpty(new byte[0])
                .flatMap(b -> blank(b) ? Mono.just(SrppCaptureRelay.problem(SrppCaptureProblemCatalog.INVALID_REQUEST, instance)) : relay.call(path, instance, b));
    }
    /** Task 481.1 第 1 步：同樣先 415、再空白 body 400；其餘步驟交給 {@link SrppEventEvidenceCapture}。 */
    private Mono<ResponseEntity<byte[]>> eventChecked(String contentType, Mono<byte[]> body) {
        if (!isApplicationJson(contentType)) return Mono.just(SrppCaptureRelay.problem(SrppCaptureProblemCatalog.UNSUPPORTED_MEDIA_TYPE, EVENT));
        return (body == null ? Mono.<byte[]>empty() : body).defaultIfEmpty(new byte[0])
                .flatMap(b -> blank(b) ? Mono.just(SrppCaptureRelay.problem(SrppCaptureProblemCatalog.INVALID_REQUEST, EVENT)) : eventEvidence.capture(b));
    }
    /** 只接受 type=application、subtype=json（不分大小寫、忽略參數）；{@code +json}、萬用字元、缺失與語法錯誤一律不接受。 */
    static boolean isApplicationJson(String contentType) {
        if (contentType == null || contentType.isBlank()) return false;
        try { MediaType type = MediaType.parseMediaType(contentType); return "application".equalsIgnoreCase(type.getType()) && "json".equalsIgnoreCase(type.getSubtype()); }
        catch (InvalidMediaTypeException e) { return false; }
    }
    private static boolean blank(byte[] b) { for (byte x : b) if (x != ' ' && x != '\t' && x != '\r' && x != '\n') return false; return true; }

    @ExceptionHandler(SrppCaptureProblemException.class)
    ResponseEntity<byte[]> problem(SrppCaptureProblemException ex, ServerWebExchange exchange) {
        if (ex.problem().status().is5xxServerError()) capture(exchange, ex);
        return SrppCaptureRelay.problem(ex.problem(), instance(exchange));
    }
    /** 語法不合法的 Content-Type 在 {@code @RequestBody} 解析階段即拋出，早於方法本體。 */
    @ExceptionHandler({UnsupportedMediaTypeStatusException.class, InvalidMediaTypeException.class})
    ResponseEntity<byte[]> unsupportedMediaType(Exception ex, ServerWebExchange exchange) {
        return SrppCaptureRelay.problem(SrppCaptureProblemCatalog.UNSUPPORTED_MEDIA_TYPE, instance(exchange));
    }
    /** 萬用字元 Content-Type（如 {@code application/*}）在解析階段是以一般 415 {@link ResponseStatusException} 拋出；其他狀態視為未預期。 */
    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<byte[]> responseStatus(ResponseStatusException ex, ServerWebExchange exchange) {
        if (ex.getStatusCode().value() == HttpStatus.UNSUPPORTED_MEDIA_TYPE.value()) return unsupportedMediaType(ex, exchange);
        return unexpected(ex, exchange);
    }
    @ExceptionHandler(ServerWebInputException.class)
    ResponseEntity<byte[]> invalidInput(ServerWebInputException ex, ServerWebExchange exchange) {
        return SrppCaptureRelay.problem(SrppCaptureProblemCatalog.INVALID_REQUEST, instance(exchange));
    }
    /** 未預期例外：本體只用固定文案；伺服器端 log 記錄例外類別與 stack trace（不含 request 內容與帳號），並進 API 錯誤日誌。 */
    @ExceptionHandler(Exception.class)
    ResponseEntity<byte[]> unexpected(Exception ex, ServerWebExchange exchange) {
        log.error("SRPP capture 未預期錯誤 instance={} exception={}", instance(exchange), ex.getClass().getName(), ex);
        capture(exchange, ex);
        return SrppCaptureRelay.problem(SrppCaptureProblemCatalog.INTERNAL_ERROR, instance(exchange));
    }
    private static String instance(ServerWebExchange exchange) {
        return DECISION.equals(exchange.getRequest().getPath().value()) ? DECISION : EVENT;
    }
}

@Service
class SrppCaptureRelay {
    private final WebClient business;
    SrppCaptureRelay(@Qualifier("businessServicesClient") WebClient business) { this.business=business; }
    /** business 回應（含 problem 本體）原樣轉送；只有 BFF 自己判定的上游不合法才改寫為本地 problem。 */
    Mono<ResponseEntity<byte[]>> call(String path, String instance, byte[] body) {
        return business.post().uri(path).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON,MediaType.APPLICATION_PROBLEM_JSON).bodyValue(body).exchangeToMono(r->r.bodyToMono(byte[].class).defaultIfEmpty(new byte[0]).map(bytes->{MediaType type=r.headers().contentType().orElse(MediaType.APPLICATION_PROBLEM_JSON); if(!isJson(type)) return problem(SrppCaptureProblemCatalog.UPSTREAM_INVALID,instance); ResponseEntity.BodyBuilder out=ResponseEntity.status(r.statusCode()).contentType(type).header(HttpHeaders.CACHE_CONTROL,"no-store"); String retry=r.headers().header(HttpHeaders.RETRY_AFTER).stream().findFirst().orElse(null);if(retry!=null)out.header(HttpHeaders.RETRY_AFTER,retry);return out.body(bytes);})).onErrorReturn(problem(SrppCaptureProblemCatalog.UPSTREAM_INVALID,instance));
    }
    private static boolean isJson(MediaType t){return "application".equalsIgnoreCase(t.getType())&&(t.getSubtype().equalsIgnoreCase("json")||t.getSubtype().endsWith("+json"));}
    static ResponseEntity<byte[]> problem(SrppCaptureProblemCatalog p, String instance){return ResponseEntity.status(p.status()).contentType(MediaType.APPLICATION_PROBLEM_JSON).header(HttpHeaders.CACHE_CONTROL,"no-store").body(p.body(instance));}
}
