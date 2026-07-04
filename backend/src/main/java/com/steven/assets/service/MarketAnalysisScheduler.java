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
        LocalDate today = LocalDate.now(MarketZones.TW_ZONE);
        if (!marketDataService.isTwTradingDay(today)) {
            log.info("今日股市分析排程：{} 非台股交易日，略過", today);
            return;
        }
        analysisService.generateIfAbsent(today, "scheduled");
    }

    /** 開機自我修復：服務於 07:30 排程時點未運行時補跑。 */
    @EventListener(ApplicationReadyEvent.class)
    public void selfHealOnStartup() {
        new Thread(() -> {
            try {
                Thread.sleep(30_000);   // 等 DB / Liquibase 等依賴就緒
                ZonedDateTime now = ZonedDateTime.now(MarketZones.TW_ZONE);
                LocalDate today = now.toLocalDate();
                boolean tradingDay = marketDataService.isTwTradingDay(today);
                boolean afterRunTime = !now.toLocalTime().isBefore(RUN_AT);
                if (tradingDay && afterRunTime && !analysisService.hasOkFor(today)) {
                    log.info("今日股市分析 self-heal：偵測到 {} 交易日已過 07:30 但尚無成功分析，補跑", today);
                    analysisService.generateIfAbsent(today, "self-heal");
                } else {
                    log.info("今日股市分析 self-heal：無需補跑（tradingDay={}, afterRunTime={}）",
                            tradingDay, afterRunTime);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("今日股市分析 self-heal 失敗: {}", e.getMessage());
            }
        }, "market-analysis-self-heal").start();
    }
}
