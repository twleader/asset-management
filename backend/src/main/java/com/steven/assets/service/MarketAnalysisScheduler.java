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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

/**
 * 今日股市分析（Requirement 31）排程。
 *
 * <ul>
 *   <li><b>每分鐘 tick（Task 184）</b>：每個台股交易日於「可設定的多個寄送時間」（{@code market_analysis_send_time}
 *       之 {@code active=true}）各觸發一次全新分析並各寄一封；cron 以 MON-FRI 每分鐘觸發，命中啟用時點後再判台股假日。
 *       未命中任何時點即零成本略過（不查 enabled、不做颱風假偵測、不呼叫 LLM）。</li>
 *   <li>開機 self-heal：服務於「最早啟用時點」未運行（重啟／crash／部署）時，若今天為交易日且現在已過該最早時點
 *       且今日尚無成功分析，補跑一次（不逐時段補寄，避免重啟洗版；比照 {@link IndexDailyRefreshScheduler}）。</li>
 * </ul>
 *
 * <p>背景 cron 無 HTTP request → {@code CurrentUserContext}（request scope）取不到，不套 owner 過濾
 * （本功能為全域參考資料）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MarketAnalysisScheduler {

    private final MarketAnalysisService analysisService;
    private final MarketAnalysisSendTimeService sendTimeService;
    private final MarketDataService marketDataService;

    /**
     * 每分鐘 tick（MON-FRI，Asia/Taipei）：比對現在 HH:mm 是否命中任一啟用中寄送時間；命中才做交易日閘門並觸發分析。
     */
    @Scheduled(cron = "0 * * * * MON-FRI", zone = "Asia/Taipei")
    public void scheduledTick() {
        LocalTime nowHm = LocalTime.now(MarketZones.TW_ZONE).truncatedTo(ChronoUnit.MINUTES);
        List<LocalTime> activeTimes = sendTimeService.activeTimes();   // 已截到分、升序
        if (activeTimes.stream().noneMatch(nowHm::equals)) {
            return;   // 非寄送時點：零成本略過（絕大多數分鐘走這條）
        }
        if (!analysisService.isEnabled()) {
            log.info("今日股市分析排程：命中寄送時點 {} 但每日自動分析已停用（enabled=false），略過", nowHm);
            return;
        }
        LocalDate today = LocalDate.now(MarketZones.TW_ZONE);
        // 花錢（送 LLM 批次）前先做一次權威即時颱風假偵測（爬 DGPA 免費、分析昂貴）：直接採 detect 回傳的
        // closedToday 短路，不賭 poller 是否已於此時點前偵測+傳播完成 → 颱風假不白花錢送批次。
        boolean closedToday = marketDataService.refreshTwClosureToday();
        if (closedToday || !marketDataService.isTwTradingDay(today)) {
            log.info("今日股市分析排程：{} 非台股交易日（颱風假 / 假日），略過寄送時點 {}", today, nowHm);
            return;
        }
        log.info("今日股市分析排程：命中寄送時點 {}，觸發重跑並寄送（date={}）", nowHm, today);
        analysisService.generateForSend(today, "scheduled@" + nowHm);
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

    /** 開機自我修復：服務於「最早啟用寄送時點」未運行時補跑一次（不逐時段補寄）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void selfHealOnStartup() {
        new Thread(() -> {
            try {
                Thread.sleep(30_000);   // 等 DB / Liquibase 等依賴就緒
                if (!analysisService.isEnabled()) {
                    log.info("今日股市分析 self-heal：每日自動分析已停用（enabled=false），略過");
                    return;
                }
                // 最早的啟用寄送時點；無任何啟用時間 → 不補跑
                Optional<LocalTime> earliest = sendTimeService.activeTimes().stream().findFirst();
                if (earliest.isEmpty()) {
                    log.info("今日股市分析 self-heal：無啟用中的寄送時間，略過");
                    return;
                }
                ZonedDateTime now = ZonedDateTime.now(MarketZones.TW_ZONE);
                LocalDate today = now.toLocalDate();
                // 先做便宜的本地判斷：未過最早時點 或 今日已有成功分析 → 無需補跑，連 DGPA 前置偵測都省。
                boolean afterEarliest = !now.toLocalTime().isBefore(earliest.get());
                boolean hasOk = analysisService.hasOkFor(today);
                if (!afterEarliest || hasOk) {
                    log.info("今日股市分析 self-heal：無需補跑（afterEarliest={}, hasOk={}, earliest={}）",
                            afterEarliest, hasOk, earliest.get());
                    return;
                }
                // 可能要花錢補跑 → 才做前置權威即時颱風假偵測（爬蟲免費、分析昂貴）：直接採 closedToday 短路
                // （不動快取）；偵測失敗則退回 isTwTradingDay（既有快取，含 poller 先前偵測到的休市）。
                boolean closedToday = marketDataService.refreshTwClosureToday();
                boolean tradingDay = !closedToday && marketDataService.isTwTradingDay(today);
                if (tradingDay) {
                    log.info("今日股市分析 self-heal：偵測到 {} 交易日已過最早寄送時點 {} 但尚無成功分析，補跑",
                            today, earliest.get());
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
