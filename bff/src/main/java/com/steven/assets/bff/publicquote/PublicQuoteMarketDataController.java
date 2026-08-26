package com.steven.assets.bff.publicquote;

import com.steven.assets.bff.publicquote.PublicQuoteMarketDataDto.DetailedLatestQuote;
import com.steven.assets.bff.publicquote.PublicQuoteMarketDataDto.ListedLatestQuote;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/** Docker 9090 同名兩條 exact GET 的 BFF public market aggregation controller。 */
@RestController
@RequestMapping("/api/quotes")
@RequiredArgsConstructor
public class PublicQuoteMarketDataController {

    private final PublicQuoteMarketDataService service;

    @GetMapping
    public Mono<List<ListedLatestQuote>> list(
            @RequestParam(required = false) String market,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {
        return service.list(market, start, end);
    }

    /** raw cache miss 必須維持 204；成功才有固定 marketData。 */
    @GetMapping("/one")
    public Mono<ResponseEntity<DetailedLatestQuote>> one(
            @RequestParam String code,
            @RequestParam String market,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) {
        return service.one(code, market, start, end)
                .map(ResponseEntity::ok)
                .defaultIfEmpty(ResponseEntity.noContent().build());
    }
}
