package com.steven.assets.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Task 357／357.3a／357.3b／357.3c：一次性歷史回補 runner。
 *
 * <p><b>不對外曝露、不新增排程、不新增端點、不新增第二條抓取路徑</b>（357.8a）。串起既有的
 * 兩段機制：</p>
 * <ol>
 *   <li>ext 端重抓 snapshot——經既有 {@code POST /internal/dividend/sync?code=&market=}
 *       觸發 {@code DividendPersister.syncOne(code, market)} 對 FinMind 重新抓取，
 *       357.2 系列已停止壓合，重抓後純配股事件的除權日會落到獨立欄位。</li>
 *   <li>backend 端投影到 current state——呼叫
 *       {@link DividendCurrentStateProjectionService#projectOne(String, String, Instant)}，
 *       把新 snapshot 回填進 {@code stock_dividend_history}（{@code DividendPersister}
 *       自身明文表示不維護這張表的 current-state）。</li>
 * </ol>
 *
 * <p><b>分批、可中斷續跑、逐檔失敗清單（357.3c）：</b>{@link #backfill(List)} 對每一檔
 * 各自 try/catch，一檔失敗不影響其餘檔位；回傳的 {@link BackfillResult} 同時帶回成功與
 * 失敗清單，呼叫端可以只把 {@link BackfillResult#failed()} 的代碼／市場重新組成下一批
 * 輸入、再次呼叫本方法達成「續跑」——本類別本身不持有任何跨呼叫的狀態，天然可重入。
 * FinMind 有額度限制，兩檔之間插入 {@link #DELAY_BETWEEN_TARGETS} 的固定延遲。</p>
 *
 * <p>本類別刻意不是 {@code @RestController}／{@code @Scheduled}：它只被本任務的回補流程
 * 手動呼叫一次（例如透過測試或一次性的 headless 呼叫），完成後即可棄用。</p>
 */
@Component
@Slf4j
public class DividendHistoricalBackfillRunner {

    private static final Duration DELAY_BETWEEN_TARGETS = Duration.ofMillis(800);

    private final WebClient externalClient;
    private final DividendCurrentStateProjectionService projectionService;

    public DividendHistoricalBackfillRunner(
            @Value("${external-materials.base-url:http://external-materials-service:8080}")
            String externalUrl,
            DividendCurrentStateProjectionService projectionService) {
        this.externalClient = WebClient.builder().baseUrl(externalUrl).build();
        this.projectionService = projectionService;
    }

    /** 回補目標：股票代碼＋市場。 */
    public record Target(String code, String market) {}

    /** 單檔回補結果，成功／失敗清單都逐檔記錄，不得靜默跳過（357.3c）。 */
    public record BackfillResult(List<String> succeeded, List<Failure> failed) {
        public BackfillResult {
            succeeded = succeeded == null ? List.of() : List.copyOf(succeeded);
            failed = failed == null ? List.of() : List.copyOf(failed);
        }
    }

    public record Failure(String code, String market, String reason) {}

    /**
     * 對每個 target 依序執行「ext 重抓 → backend 投影」兩段；單檔失敗記入
     * {@link BackfillResult#failed()} 並繼續下一檔，不中斷整批。
     */
    public BackfillResult backfill(List<Target> targets) {
        List<String> succeeded = new ArrayList<>();
        List<Failure> failed = new ArrayList<>();
        if (targets == null || targets.isEmpty()) return new BackfillResult(succeeded, failed);

        for (int i = 0; i < targets.size(); i++) {
            Target target = targets.get(i);
            if (target == null || target.code() == null || target.market() == null) {
                failed.add(new Failure(
                        target == null ? null : target.code(),
                        target == null ? null : target.market(),
                        "target 或 code／market 為 null"));
                continue;
            }
            try {
                backfillOne(target.code(), target.market());
                succeeded.add(target.code() + "/" + target.market());
                log.info("回補完成：{} {}", target.market(), target.code());
            } catch (RuntimeException e) {
                String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                failed.add(new Failure(target.code(), target.market(), reason));
                log.warn("回補失敗（不中斷整批，續跑下一檔）：{} {}: {}",
                        target.market(), target.code(), reason);
            }
            if (i < targets.size() - 1) sleep();
        }
        return new BackfillResult(succeeded, failed);
    }

    private void backfillOne(String code, String market) {
        // 第一段：ext 端重抓 snapshot（既有端點，非本任務新增）。
        externalClient.post()
                .uri(uri -> uri.path("/internal/dividend/sync")
                        .queryParam("code", code)
                        .queryParam("market", market).build())
                .retrieve()
                .toBodilessEntity()
                .block(Duration.ofSeconds(30));
        // 第二段：backend 端投影到 current state（DividendPersister 明文表示自己不做這件事）。
        boolean projected = projectionService.projectOne(code, market, Instant.now());
        if (!projected) {
            throw new IllegalStateException(
                    "projectOne 回傳 false：無可投影的完整或歷史 snapshot（重抓可能未回傳涵蓋未來 45 日的完整 scope）");
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(DELAY_BETWEEN_TARGETS.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
