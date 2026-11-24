package com.steven.assets.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * 價格更新 fan-out：
 * - 來源：RedisSubscriberConfig 訂閱 Redis channel `price-update`，每收到一筆呼叫 {@link #onPriceUpdate}
 * - 出口 1：MarketDataController 的 SSE endpoint 透過 {@link #stream()} 廣播給所有 EventSource client
 * - 出口 2：交給 StockAlertService 即時檢查該檔股票的警示條件
 * - 出口 3：合併後評估交易雷達逐檔狀態 Email 通知
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceStreamService {

    private static final String US_MARKET = "美股";

    private final ObjectMapper mapper = new ObjectMapper();
    @Autowired
    @Lazy
    private StockAlertService stockAlertService;
    @Autowired
    @Lazy
    private TradingRadarNotificationService tradingRadarNotificationService;

    private final Sinks.Many<String> sink =
            Sinks.many().multicast().onBackpressureBuffer(256, false);

    public void onPriceUpdate(String json) {
        Sinks.EmitResult result = sink.tryEmitNext(json);
        if (result.isFailure()) {
            log.debug("Price update emit failed: {}", result);
        }
        // 觸發單檔警示比對（避免阻塞訂閱者執行緒）
        try {
            JsonNode n = mapper.readTree(json);
            String code = n.path("stockCode").asText(null);
            String market = n.path("market").asText(null);
            if (code != null && market != null) {
                stockAlertService.checkAlertsFor(code, market);
                if (shouldQueueRadarNotification(market, n)) {
                    tradingRadarNotificationService.queueEvaluation(code, market);
                }
            }
        } catch (Exception e) {
            log.debug("alert dispatch on price-update 失敗: {}", e.getMessage());
        }
    }

    public Flux<String> stream() {
        return sink.asFlux();
    }

    /**
     * 美股收盤校正與失效快取也會共用 {@code price-update}；它們不可改寫通知 baseline。
     * 台股沿用既有事件語意，只有美股在 fan-out 邊界額外要求明確盤中報價。
     */
    static boolean shouldQueueRadarNotification(String market, JsonNode event) {
        return !US_MARKET.equals(market)
                || ("LIVE".equals(event.path("quoteStatus").asText())
                && !event.path("closed").asBoolean(false));
    }
}
