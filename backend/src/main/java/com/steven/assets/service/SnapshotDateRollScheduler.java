package com.steven.assets.service;

import com.steven.assets.repository.AssetSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 每日把「每個 owner 各自最新一筆」{@code asset_snapshot} 的日期釘成當日並重算（Requirement 35 / Task 174）。
 *
 * <p><b>動機</b>：BFF {@code LiveAssetsOverlay.applyToLatest} 以 Redis 即時價覆蓋歷史「最新一筆」的股票現值，
 * 但有 per-market 基準日閘門——僅當「最新快照 {@code snapshotDate} == 該市場時區今日」的市場才覆蓋。
 * 使用者若一段時間未手動建檔，最新快照停在過去日期 → 三市場皆非今日 → 完全不覆蓋 → 顯示過去凍結收盤。
 * 每天把最新快照日期釘成當日即打開閘門，讓 Dashboard／歷年資產「最新一筆」反映今日即時價。
 *
 * <p><b>多租戶</b>：背景排程無 request context → {@code @Filter(ownerFilter)} 不啟用。以
 * {@link AssetSnapshotRepository#findDistinctOwnerUserIds()} 取全 owner，逐 owner 呼叫
 * {@link AssetService#rollLatestSnapshotToTodayForOwner}（帶 owner 條件、owner-scoped 安全）。
 * 迴圈置於本 scheduler bean、逐 owner 呼叫 {@code AssetService} 的 public {@code @Transactional} 方法
 * ——每 owner 獨立交易，單一 owner 失敗只記 log、不連坐其他 owner；亦避免 self-invocation 繞過 Spring proxy。
 * 比照 {@link ExportScheduleService}（背景全 owner + per-owner 隔離）與 {@link IndexDailyRefreshScheduler}
 * （cron + 開機 self-heal）。
 *
 * <p><b>冪等</b>：{@code rollLatestSnapshotToTodayForOwner} 同時清除明確處理日已到期的在途款。
 * 已是當日且沒有到期款、或未來日期的最新快照 no-op，
 * 故重跑安全。{@code lastRolledDate} 為純效率的當日 guard（避免 cron 與 self-heal 同日重複掃全 owner），
 * 記憶體變數、重啟歸零；正確性不依賴它。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SnapshotDateRollScheduler {

    private static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");

    private final AssetSnapshotRepository snapshotRepo;
    private final AssetService assetService;

    /** 純效率的當日 guard；roll 本身冪等，重啟歸零多跑無害。僅整輪無失敗才標記，讓部分失敗者可於重啟 self-heal 補跑。 */
    private volatile LocalDate lastRolledDate;

    /** 每日 00:05 Asia/Taipei 釘定當日、清除到期在途款並重算。 */
    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Taipei")
    public void scheduledRoll() {
        rollAll("scheduled");
    }

    /** 開機自癒：服務於 00:05 排程時點未運行（部署／crash／重啟跨過 00:05）時補跑當日。 */
    @EventListener(ApplicationReadyEvent.class)
    public void selfHealOnStartup() {
        new Thread(() -> {
            try {
                Thread.sleep(30_000);   // 等 DB／Liquibase 等依賴就緒（比照 IndexDailyRefreshScheduler）
                rollAll("self-heal");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("最新快照日期 roll self-heal 失敗: {}", e.getMessage(), e);
            }
        }, "snapshot-date-roll-self-heal").start();
    }

    /**
     * 逐 owner 把其最新快照日期釘成當日並重算。單一 owner 失敗只記 log、不中斷其他 owner。
     * {@code synchronized} 避免 cron 與 self-heal thread 併發重入。
     */
    private synchronized void rollAll(String tag) {
        LocalDate today = LocalDate.now(TW_ZONE);
        if (today.equals(lastRolledDate)) {
            log.debug("最新快照日期今日已 roll 過，略過 [{}]", tag);
            return;
        }
        int rolled = 0, skipped = 0, failed = 0;
        for (Long ownerId : snapshotRepo.findDistinctOwnerUserIds()) {
            try {
                if (assetService.rollLatestSnapshotToTodayForOwner(ownerId, today)) rolled++;
                else skipped++;
            } catch (Exception e) {
                failed++;
                log.warn("roll 最新快照失敗 owner={} [{}]: {}", ownerId, tag, e.getMessage(), e);
            }
        }
        if (failed == 0) lastRolledDate = today;   // 全成功才標記；有失敗者留待重啟 self-heal 或隔日補跑
        log.info("最新快照日期 roll 完成 [{}]：rolled={} skipped={} failed={} today={}",
                tag, rolled, skipped, failed, today);
    }
}
