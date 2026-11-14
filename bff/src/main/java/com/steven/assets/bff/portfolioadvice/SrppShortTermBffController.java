package com.steven.assets.bff.portfolioadvice;

import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.bff.portfolioadvice.SrppShortTermDto.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Function;

/** Authenticated page-owned proxy. It cannot refresh data, publish rules or place trades. */
@RestController
@RequestMapping(value="/api/bff/portfolio-advice/srpp/short-opportunities",produces=MediaType.APPLICATION_JSON_VALUE)
public class SrppShortTermBffController {
    private final WebClient business;
    public SrppShortTermBffController(WebClient businessServicesClient){this.business=businessServicesClient;}
    @GetMapping public Mono<ResponseEntity<Object>> list(@RequestParam(required=false) String draftId,@RequestParam(required=false) String revision) {
        return read(business.get().uri(b->b.path("/api/portfolio-advice/srpp/short-opportunities")
                .queryParamIfPresent("draftId",Optional.ofNullable(draftId)).queryParamIfPresent("revision",Optional.ofNullable(revision)).build()),ListEnvelope.class,SrppShortTermPresentation::list);
    }
    @GetMapping("/stock") public Mono<ResponseEntity<Object>> stock(@RequestParam String stockCode,@RequestParam String market) {
        return read(business.get().uri(b->b.path("/api/portfolio-advice/srpp/short-opportunities/stock")
                .queryParam("stockCode",stockCode).queryParam("market",market).build()),DetailEnvelope.class);
    }
    private <T> Mono<ResponseEntity<Object>> read(WebClient.RequestHeadersSpec<?> request,Class<T> type) {
        return read(request,type,value->value);
    }
    private <T> Mono<ResponseEntity<Object>> read(WebClient.RequestHeadersSpec<?> request,Class<T> type,Function<T,Object> present) {
        return request.exchangeToMono(response->response.bodyToMono(String.class).defaultIfEmpty("").map(raw->{
            try {
                if(response.statusCode().is2xxSuccessful())return ResponseEntity.status(response.statusCode()).body(present.apply(SrppSettingsPresentation.JSON.readValue(raw,type)));
                JsonNode error=SrppSettingsPresentation.JSON.readTree(raw);
                return ResponseEntity.status(response.statusCode()).body((Object)new Problem(error.path("code").asText("UPSTREAM_ERROR"),error.path("message").asText("短線資料暫時無法讀取")));
            }catch(Exception invalid){return ResponseEntity.status(502).body((Object)new Problem("UPSTREAM_INVALID","短線資料格式無效，請稍後再試"));}
        })).timeout(Duration.ofSeconds(20));
    }
}
