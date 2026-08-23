package com.steven.assets.client;

import com.steven.assets.service.DividendResyncClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Map;

/**
 * {@link DividendResyncClient} 的唯一實作：呼叫 external-materials-service 既有的
 * {@code POST /internal/dividend/sync?code=&market=}（Task 357.3a 第一段）。
 *
 * <p><b>刻意沿用既有端點，不新增任何抓取路徑</b>（357.8a）。同一支端點已由
 * {@code DividendHistoryService.findFromDb} 的 cold-cache fallback 使用；本 client 只是把
 * 同一個呼叫從「讀不到才觸發」變成「回補時逐檔顯式觸發」。對 FinMind 的網路 IO 仍然
 * 只發生在 external-materials-service 內。</p>
 *
 * <p><b>逾時是必要的，不是保險。</b>{@code WebClient.block()} 不帶 timeout 會無限等待，
 * 一檔卡住就讓整批回補停在那裡而且沒有任何錯誤——那正好違反 357.3c 的「失敗必須逐檔記錄
 * 且不得靜默跳過」。逾時會拋 {@code IllegalStateException}，由
 * {@code DividendBackfillService} 記成該檔失敗並繼續下一檔。</p>
 */
@Component
public class ExternalDividendResyncClient implements DividendResyncClient {

    private static final ParameterizedTypeReference<Map<String, Object>> BODY_TYPE =
            new ParameterizedTypeReference<>() {};

    private final WebClient client;
    private final Duration timeout;

    @Autowired
    public ExternalDividendResyncClient(
            @Value("${external-materials.base-url:http://external-materials-service:8080}")
            String baseUrl,
            @Value("${app.dividend-backfill.request-timeout:5m}") Duration timeout) {
        this(WebClient.builder().baseUrl(baseUrl).build(), timeout);
    }

    ExternalDividendResyncClient(WebClient client, Duration timeout) {
        this.client = client;
        this.timeout = timeout;
    }

    @Override
    public int resyncOne(String code, String market) {
        Map<String, Object> body = client.post()
                .uri(uri -> uri.path("/internal/dividend/sync")
                        .queryParam("code", code)
                        .queryParam("market", market).build())
                .retrieve()
                .bodyToMono(BODY_TYPE)
                .block(timeout);
        Object written = body == null ? null : body.get("written");
        return written instanceof Number number ? number.intValue() : 0;
    }
}
