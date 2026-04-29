package com.steven.assets.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 價格更新 fan-out：
 * - 來源：RedisSubscriberConfig 訂閱 Redis channel `price-update`，每收到一筆呼叫 {@link #onPriceUpdate}
 * - 出口：MarketDataController 的 SSE endpoint 透過 {@link #stream()} 將同一份訊息廣播給所有 EventSource client
 */
@Slf4j
@Service
public class PriceStreamService {

    private final Sinks.Many<String> sink =
            Sinks.many().multicast().onBackpressureBuffer(256, false);

    public void onPriceUpdate(String json) {
        Sinks.EmitResult result = sink.tryEmitNext(json);
        if (result.isFailure()) {
            log.debug("Price update emit failed: {}", result);
        }
    }

    public Flux<String> stream() {
        return sink.asFlux();
    }
}
