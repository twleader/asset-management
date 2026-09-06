package com.steven.assets.bff.apierrorlogs;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@RestController @RequestMapping("/api/bff/api-error-logs")
public class ApiErrorLogsBffController {
    private final WebClient business;
    public ApiErrorLogsBffController(@Qualifier("businessServicesClient") WebClient business){this.business=business;}
    @GetMapping public Mono<Object> list(@RequestParam(defaultValue="ALL") String source,@RequestParam(required=false) String operationKey,@RequestParam(defaultValue="NEWEST") String sort){return business.get().uri(uri->uri.path("/api/api-error-logs").queryParam("source",source).queryParam("sort",sort).queryParamIfPresent("operationKey",java.util.Optional.ofNullable(operationKey)).build()).retrieve().bodyToMono(Object.class);}
    @GetMapping("/operations") public Mono<Object> operations(@RequestParam(defaultValue="ALL") String source){return business.get().uri(uri->uri.path("/api/api-error-logs/operations").queryParam("source",source).build()).retrieve().bodyToMono(Object.class);}
    @GetMapping("/{id}") public Mono<Object> detail(@PathVariable Long id){return business.get().uri("/api/api-error-logs/{id}",id).retrieve().bodyToMono(Object.class);}
}
