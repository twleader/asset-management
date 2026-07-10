package com.steven.assets.service;

import com.steven.assets.util.MarketZones;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;

/**
 * 今日股市分析（Requirement 31）排程。
 *
 * <ul>
 *   <li>每個台股交易日 07:30（Asia/Taipei）觸發一次分析；cron 以 MON-FRI 觸發後再判台股假日。</li>
 *   <li>開機 self-heal：服務於排程時點未運行（重啟／crash／部署）時，若今天為交易日且現在已過 07:30
 *       且今日尚無成功分析，補跑一次（比照 {@link IndexDailyRefreshScheduler}）。</li>
 * </ul>
 *
 * <p>背景 cron 無 HTTP request → {@code CurrentUserContext}（request scope）取不到，不套 owner 過濾
 * （本功能為全域參考資料）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketAnalysisScheduler {

    /** 每日排程時刻（Asia/Taipei）。self-heal 以此判斷「是否已過排程時點」。 */
    private static final LocalTime RUN_AT = LocalTime.of(7, 30);

    private final MarketAnalysisService analysisService;
    private final MarketDataService marketDataService;

    /** 每交易日 07:30 Asia/Taipei（MON-FRI，另判台股假日）。 */
    @Scheduled(cron = "0 30 7 * * MON-FRI", zone = "Asia/Taipei")
    public void scheduledAnalysis() {
        if (!analysisService.isEnabled()) {
            log.info("今日股市分析排程：每日自動分析已停用（enabled=false），略過（可由管理者手動觸發）");
            return;
        }
        LocalDate today = LocalDate.now(MarketZones.TW_ZONE);
        // 花錢（送 LLM 批次）前先做一次權威即時颱風假偵測（爬 DGPA 免費、分析昂貴）：直接採 detect 回傳的
        // closedToday 短路，不賭 05:00–08:45 poller 是否已在 07:30 前偵測+傳播完成 → 颱風假不白花錢送批次。
        boolean closedToday = marketDataService.refreshTwClosureToday();
        if (closedToday || !marketDataService.isTwTradingDay(today)) {
            log.info("今日股市分析排程：{} 非台股交易日（颱風假 / 假日），略過", today);
            return;
        }
        analysisService.generateIfAbsent(today, "scheduled");
    }

    /**
     * Batch API 收尾 poller：每 90 秒撈在製批次（status=PROCESSING），批次 ENDED 後取結果落庫。
     * 不受 {@code enabled} 影響（只收尾已送出的批次，不新送）。啟動後延遲 60 秒待依賴就緒。
     */
    @Scheduled(fixedDelayString = "90000", initialDelayString = "60000")
    public void pollBatches() {
        try {
            analysisService.pollPendingBatches();
        } catch (Exception e) {
            log.warn("今日股市分析：批次 poller 例外: {}", e.getMessage());
        }
    }

    /** 開機自我修復：服務於 07:30 排程時點未運行時補跑。 */
    @EventListener(ApplicationReadyEvent.class)
    public void selfHealOnStartup() {
        new Thread(() -> {
            try {
                Thread.sleep(30_000);   // 等 DB / Liquibase 等依賴就緒
                if (!analysisService.isEnabled()) {
                    log.info("今日股市分析 self-heal：每日自動分析已停用（enabled=false），略過");
                    return;
                }
                ZonedDateTime now = ZonedDateTime.now(MarketZones.TW_ZONE);
                LocalDate today = now.toLocalDate();
                // 先做便宜的本地判斷：未過 07:30 或今日已有成功分析 → 無需補跑，連 DGPA 前置偵測都省。
                boolean afterRunTime = !now.toLocalTime().isBefore(RUN_AT);
                boolean hasOk = analysisService.hasOkFor(today);
                if (!afterRunTime || hasOk) {
                    log.info("今日股市分析 self-heal：無需補跑（afterRunTime={}, hasOk={}）", afterRunTime, hasOk);
                    return;
                }
                // 可能要花錢補跑 → 才做前置權威即時颱風假偵測（爬蟲免費、分析昂貴）：直接採 closedToday 短路
                // （不動快取）；偵測失敗則退回 isTwTradingDay（既有快取，含 poller 先前偵測到的休市）。
                boolean closedToday = marketDataService.refreshTwClosureToday();
                boolean tradingDay = !closedToday && marketDataService.isTwTradingDay(today);
                if (tradingDay) {
                    log.info("今日股市分析 self-heal：偵測到 {} 交易日已過 07:30 但尚無成功分析，補跑", today);
                    analysisService.generateIfAbsent(today, "self-heal");
                } else {
                    log.info("今日股市分析 self-heal：{} 非台股交易日（颱風假 / 假日），不補跑", today);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("今日股市分析 self-heal 失敗: {}", e.getMessage());
            }
        }, "market-analysis-self-heal").start();
    }
}
