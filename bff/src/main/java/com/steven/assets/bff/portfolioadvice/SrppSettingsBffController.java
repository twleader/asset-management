package com.steven.assets.bff.portfolioadvice;

import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.bff.portfolioadvice.SrppSettingsDto.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.time.Duration;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** Page entry, immutable DTOs and BFF-owned presentation. Raw request text retains strict parsing. */
@RestController @RequestMapping(value="/api/bff/portfolio-advice/srpp",produces=MediaType.APPLICATION_JSON_VALUE)
public class SrppSettingsBffController {
    private final WebClient business;
    public SrppSettingsBffController(WebClient businessServicesClient){business=businessServicesClient;}
    @GetMapping public Mono<ResponseEntity<Response>> center(){return call(HttpMethod.GET,"",null,CenterResponse.class,SrppSettingsPresentation::center);}
    @PostMapping("/drafts") public Mono<ResponseEntity<Response>> create(@RequestBody String raw){return call(HttpMethod.POST,"/drafts",raw,DraftResponse.class,UnaryOperator.identity());}
    @PutMapping("/drafts/{id}") public Mono<ResponseEntity<Response>> update(@PathVariable UUID id,@RequestBody String raw){return call(HttpMethod.PUT,"/drafts/"+id,raw,DraftResponse.class,UnaryOperator.identity());}
    @PostMapping("/drafts/{id}/simulate") public Mono<ResponseEntity<Response>> simulate(@PathVariable UUID id,@RequestBody String raw){return call(HttpMethod.POST,"/drafts/"+id+"/simulate",raw,RunResponse.class,SrppSettingsPresentation::run);}
    @GetMapping("/simulations/{id}") public Mono<ResponseEntity<Response>> simulation(@PathVariable UUID id){return call(HttpMethod.GET,"/simulations/"+id,null,RunResponse.class,SrppSettingsPresentation::run);}
    @PostMapping("/drafts/{id}/publish") public Mono<ResponseEntity<Response>> publish(@PathVariable UUID id,@RequestBody String raw){return call(HttpMethod.POST,"/drafts/"+id+"/publish",raw,ReleaseResponse.class,UnaryOperator.identity());}
    @GetMapping("/releases/{id}/payload") public Mono<ResponseEntity<Response>> payload(@PathVariable UUID id){return call(HttpMethod.GET,"/releases/"+id+"/payload",null,PayloadResponse.class,UnaryOperator.identity());}
    private <T extends Response> Mono<ResponseEntity<Response>> call(HttpMethod method,String suffix,String raw,Class<T> type,UnaryOperator<T> projection) {
        WebClient.RequestBodySpec request=business.method(method).uri("/api/portfolio-advice/srpp"+suffix).contentType(MediaType.APPLICATION_JSON);
        WebClient.RequestHeadersSpec<?> ready=raw==null?request:request.bodyValue(raw);
        return ready.exchangeToMono(response->response.bodyToMono(String.class).defaultIfEmpty("").map(body->{
            try {
                if(response.statusCode().is2xxSuccessful())return ResponseEntity.status(response.statusCode()).body((Response)projection.apply(SrppSettingsPresentation.JSON.readValue(body,type)));
                JsonNode error=SrppSettingsPresentation.JSON.readTree(body);
                return ResponseEntity.status(response.statusCode()).body((Response)new ProblemResponse(error.path("code").asText("UPSTREAM_ERROR"),error.path("message").asText("服務暫時無法完成請求")));
            }catch(Exception invalid){return ResponseEntity.status(502).body((Response)new ProblemResponse("UPSTREAM_INVALID","服務回應格式無效"));}
        })).timeout(Duration.ofSeconds(20));
    }
}
