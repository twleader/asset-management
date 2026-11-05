package com.steven.assets.bff.annualdividends;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.util.Set;

/** AnnualDividendsView only; the standard WebClient carries effective tenant headers. */
@RestController
@RequestMapping("/api/bff/annual-dividends")
public class AnnualDividendBffController {
    private final WebClient businessServicesClient;
    public AnnualDividendBffController(WebClient businessServicesClient) { this.businessServicesClient = businessServicesClient; }

    @GetMapping("/summary")
    public Mono<ResponseEntity<byte[]>> summary(@RequestParam MultiValueMap<String, String> query) {
        if (query.entrySet().stream().anyMatch(e -> !Set.of("year", "market", "stock").contains(e.getKey())
                || e.getValue() == null || e.getValue().isEmpty()
                || (!"stock".equals(e.getKey()) && e.getValue().size() != 1)))
            return Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).contentType(MediaType.APPLICATION_JSON)
                    .body("{\"detail\":\"僅接受單一年度、市場與可多選股票參數，不接受指定使用者\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        return businessServicesClient.get().uri(uri -> {
            var builder = uri.path("/api/annual-dividends");
            query.forEach((key, values) -> builder.queryParam(key, values));
            return builder.build();
        }).exchangeToMono(response -> response.toEntity(byte[].class));
    }
}
