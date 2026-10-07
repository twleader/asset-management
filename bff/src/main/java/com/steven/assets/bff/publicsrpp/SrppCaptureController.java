package com.steven.assets.bff.publicsrpp;

import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Exact private-network 9090 POST relay. It forwards raw JSON bytes and never calls a vendor. */
@RestController @RequiredArgsConstructor
public class SrppCaptureController {
    public static final String EVENT = "/api/public/srpp/event-evidence/capture";
    public static final String DECISION = "/api/public/srpp/daily-decision/evaluate";
    private final SrppCaptureRelay relay;
    @PostMapping(value=EVENT, consumes=MediaType.APPLICATION_JSON_VALUE) public Mono<ResponseEntity<byte[]>> event(@RequestBody Mono<byte[]> body){return body.defaultIfEmpty(new byte[0]).flatMap(b->relay.call("/internal/srpp/event-evidence/capture",b));}
    @PostMapping(value=DECISION, consumes=MediaType.APPLICATION_JSON_VALUE) public Mono<ResponseEntity<byte[]>> decision(@RequestBody Mono<byte[]> body){return body.defaultIfEmpty(new byte[0]).flatMap(b->relay.call("/internal/srpp/daily-decision/evaluate",b));}
}

@Service
class SrppCaptureRelay {
    private final WebClient business;
    SrppCaptureRelay(@Qualifier("businessServicesClient") WebClient business) { this.business=business; }
    Mono<ResponseEntity<byte[]>> call(String path, byte[] body) {
        return business.post().uri(path).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON,MediaType.APPLICATION_PROBLEM_JSON).bodyValue(body).exchangeToMono(r->r.bodyToMono(byte[].class).defaultIfEmpty(new byte[0]).map(bytes->{MediaType type=r.headers().contentType().orElse(MediaType.APPLICATION_PROBLEM_JSON); if(!isJson(type)) return problem(HttpStatus.BAD_GATEWAY,"UPSTREAM_INVALID"); ResponseEntity.BodyBuilder out=ResponseEntity.status(r.statusCode()).contentType(type).header(HttpHeaders.CACHE_CONTROL,"no-store"); String retry=r.headers().header(HttpHeaders.RETRY_AFTER).stream().findFirst().orElse(null);if(retry!=null)out.header(HttpHeaders.RETRY_AFTER,retry);return out.body(bytes);})).onErrorReturn(problem(HttpStatus.BAD_GATEWAY,"UPSTREAM_INVALID"));
    }
    private static boolean isJson(MediaType t){return "application".equalsIgnoreCase(t.getType())&&(t.getSubtype().equalsIgnoreCase("json")||t.getSubtype().endsWith("+json"));}
    private static ResponseEntity<byte[]> problem(HttpStatus s,String c){return ResponseEntity.status(s).contentType(MediaType.APPLICATION_PROBLEM_JSON).header(HttpHeaders.CACHE_CONTROL,"no-store").body(("{\"type\":\"about:blank\",\"title\":\""+c+"\",\"status\":"+s.value()+",\"code\":\""+c+"\"}").getBytes(StandardCharsets.UTF_8));}
}
