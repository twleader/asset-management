package com.steven.assets.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 配息四日期歷史回補的<b>唯一觸發點</b>（Requirement 94 / Task 357.3a）。
 *
 * <h2>為什麼是 {@code ApplicationReadyEvent} ＋ property 開關，而不是端點或排程</h2>
 * <ul>
 *   <li><b>不新增對外端點</b>：本元件沒有任何 HTTP surface，連 {@code /internal} 都沒有。
 *       回補是一次性動作，多一支端點就是多一個永久的攻擊面與待維護契約。</li>
 *   <li><b>不新增排程</b>：沒有 {@code @Scheduled}。它只在啟動時跑一次，而且只有在
 *       {@code app.dividend-backfill.enabled=true} 時才會被建立成 bean
 *       （{@link ConditionalOnProperty}，預設 {@code false}）。</li>
 *   <li><b>不新增 9090 路由</b>：沒有端點就沒有東西可以掛上 gateway。</li>
 *   <li><b>不新增第二條抓取路徑</b>：真正的抓取仍走既有的
 *       {@code POST /internal/dividend/sync} → {@code DividendPersister.syncOne} → FinMind。</li>
 * </ul>
 *
 * <h2>觸發方式</h2>
 * <pre>{@code
 * DIVIDEND_BACKFILL_ENABLED=true \
 *   docker compose -p asset-management up -d --no-deps --force-recreate business-services
 * }</pre>
 * <p>回補完成後把旗標拿掉再 recreate 一次即可回到常態。進度存在
 * {@code EXPORT_OUTPUT_DIR/dividend-backfill-t357/}（host 家目錄 volume），
 * 中途 recreate 也能續跑。</p>
 *
 * <h2>為什麼丟到 daemon thread</h2>
 * <p>{@code ApplicationReadyEvent} 的 listener 跑在<b>主執行緒</b>上；回補要逐檔打 FinMind，
 * 全量數十檔會是分鐘級。在主執行緒上阻塞會延後 {@code SpringApplication.run()} 收尾與同事件
 * 其他 listener，readiness 也可能被一起拖住，healthcheck 會把容器判成 unhealthy。
 * 這與既有的 {@link GdriveSelfCheckStarter} 是同一個理由與同一種寫法。</p>
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.dividend-backfill.enabled", havingValue = "true")
@RequiredArgsConstructor
public class DividendBackfillStarter {

    private final DividendBackfillService backfillService;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        Thread thread = new Thread(this::runOnce, "dividend-backfill-t357");
        thread.setDaemon(true);
        thread.start();
    }

    private void runOnce() {
        try {
            DividendBackfillService.Report report = backfillService.run();
            log.info("配息四日期回補結束：目標 {} 檔、先前已完成 {} 檔、本次嘗試 {} 檔（{} 批）、"
                            + "成功 {} 檔、失敗 {} 檔、357.3b 修正 {} 列",
                    report.totalTargets(), report.alreadyDone(), report.attempted(),
                    report.batchCount(), report.succeeded().size(), report.failures().size(),
                    report.corrections().size());
            for (DividendBackfillService.Failure failure : report.failures()) {
                log.warn("配息四日期回補未完成（下次啟動會續跑）：{} {} — {}",
                        failure.market(), failure.code(), failure.reason());
            }
        } catch (Exception e) {
            // 回補是離線動作，任何例外都不得影響服務本身繼續提供 API。
            log.error("配息四日期回補中止：{}", e.getMessage(), e);
        }
    }
}
