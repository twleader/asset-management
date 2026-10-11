package com.steven.assets.bff.apierrorlogs;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.codec.CodecException;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@RestController @RequestMapping("/api/bff/api-error-logs")
public class ApiErrorLogsBffController {
    private final WebClient business;
    public ApiErrorLogsBffController(@Qualifier("businessServicesClient") WebClient business){this.business=business;}
    @GetMapping
    public Mono<PageResult> list(@RequestParam(defaultValue="ALL") String source,
                                 @RequestParam(required=false) String operationKey,
                                 @RequestParam(defaultValue="NEWEST") String sort,
                                 @RequestParam(defaultValue="0") int page) {
        return business.get().uri(uri -> uri.path("/api/api-error-logs")
                        .queryParam("source", source).queryParam("sort", sort).queryParam("page", page)
                        .queryParamIfPresent("operationKey", Optional.ofNullable(operationKey)).build())
                .exchangeToMono(response -> {
                    if (!response.statusCode().is2xxSuccessful()) return response.createException().flatMap(Mono::error);
                    // Preserve the upstream status for advice when a successful body cannot be decoded.
                    return response.bodyToMono(PageResult.class).onErrorResume(
                            error -> error instanceof CodecException || error instanceof DataBufferLimitException,
                            error -> response.createException()
                            .flatMap(ex -> Mono.error(ex.initCause(error))));
                });
    }
    @GetMapping("/operations") public Mono<Object> operations(@RequestParam(defaultValue="ALL") String source){return business.get().uri(uri->uri.path("/api/api-error-logs/operations").queryParam("source",source).build()).retrieve().bodyToMono(Object.class);}
    @GetMapping("/{id}") public Mono<Object> detail(@PathVariable Long id){return business.get().uri("/api/api-error-logs/{id}",id).retrieve().bodyToMono(Object.class);}
    public record PageResult(List<ListItem> items, long totalElements, int page, int pageSize, int totalPages) {
        public PageResult { items = List.copyOf(items); }
    }
    public record ListItem(Long id, String source, String operationKey, String apiName, String messageHeader,
                           Integer httpStatus, @JsonFormat(shape = JsonFormat.Shape.STRING) Instant occurredAt) {}
}
