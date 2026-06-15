package com.steven.assets.service;

import com.steven.assets.repository.UsIndexDailyHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 海外指數日線（{@code us_index_daily_history}）自動回補排程（Task 105）。
 *
 * <p>背景：Task 102 的「當日走勢昨收 / 漲跌%」與 Task 95/96 的日線圖 + MA20/60/240 都讀
 * {@code us_index_daily_history}，但該表原本只靠前端「回補日線（10 年）」按鈕手動觸發、**無排程**。
 * 久未點擊的指數日線會停在舊日期；當日走勢的點位是即時抓 Yahoo（最新交易日），昨收卻退回數日前的
 * 舊收盤，導致漲跌% 爆量失真（實機：SOX 走勢 ~14,041 卻拿 5 日前 12,330 當昨收 → +13.88%）。
 * 台股大盤日線由 external-materials 的 {@code TwseIndexPoller} 顧著、故一直最新；海外 8 指數缺對應排程
 * ——本類補上，讓昨收恆為「真正的前一交易日」。
 *
 * <p>設計：重用 {@link MacroHistoryService#refreshUsIndexDaily(String)}（即手動按鈕同一條路徑，Yahoo
 * {@code range=10y} 一次抓整段、idempotent upsert），對 {@link MacroHistoryService#OVERSEAS_INDEX_CODES}
 * 逐一回補。
 * <ul>
 *   <li>每日排程：美股收盤後（隔日 07:00 Asia/Taipei，TUE-SAT）。美股 16:00 ET ≈ 隔日 04~05:00 台北，
 *       07:00 留足 Yahoo daily bar 發佈緩衝；此時 8 市場（亞 / 歐 / 美）最新交易日皆已收。</li>
 *   <li>開機 self-heal：任一指數最新日期過時（&gt; 4 日）即補一次，處理「服務於排程時點未運行
 *       （restart / crash）」場景，比照 {@code ClosePersister.selfHealMissedClose}。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexDailyRefreshScheduler {

    /** 最新日線超過此天數即視為過時（容忍週末 + 1 個假日）。 */
    private static final int STALE_DAYS = 4;

    private final MacroHistoryService macroHistoryService;
    private final UsIndexDailyHistoryRepository usDailyRepo;

    /** 海外指數收盤後回補：每日 07:00 Asia/Taipei（TUE-SAT，涵蓋週一~週五各市場交易日）。 */
    @Scheduled(cron = "0 0 7 * * TUE-SAT", zone = "Asia/Taipei")
    public void scheduledRefreshAll() {
        log.info("排程：回補海外指數日線（{} 檔）", MacroHistoryService.OVERSEAS_INDEX_CODES.size());
        refreshAll("scheduled");
    }

    /** 開機自我修復：任一指數日線過時就補一次（服務於排程時點未運行 / 久未手動回補）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void selfHealStaleOnStartup() {
        new Thread(() -> {
            try {
                Thread.sleep(30_000);   // 等 external-materials-service 等依賴就緒
                LocalDate staleBefore = LocalDate.now().minusDays(STALE_DAYS);
                boolean anyStale = MacroHistoryService.OVERSEAS_INDEX_CODES.stream()
                        .anyMatch(code -> latestTradingDate(code)
                                .map(d -> d.isBefore(staleBefore)).orElse(true));
                if (anyStale) {
                    log.info("self-heal：偵測到海外指數日線過時（> {} 日），啟動回補", STALE_DAYS);
                    refreshAll("self-heal");
                } else {
                    log.info("self-heal：海外指數日線皆為最新，略過");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("海外指數日線 self-heal 失敗: {}", e.getMessage());
            }
        }, "index-daily-self-heal").start();
    }

    private void refreshAll(String tag) {
        int ok = 0;
        int total = MacroHistoryService.OVERSEAS_INDEX_CODES.size();
        for (String code : MacroHistoryService.OVERSEAS_INDEX_CODES) {
            try {
                macroHistoryService.refreshUsIndexDaily(code);
                ok++;
                Thread.sleep(500);   // Yahoo 禮貌間隔
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("回補海外指數日線 {} 失敗 [{}]: {}", code, tag, e.getMessage());
            }
        }
        log.info("海外指數日線回補完成 [{}]：成功 {}/{} 檔", tag, ok, total);
    }

    private Optional<LocalDate> latestTradingDate(String code) {
        return usDailyRepo.findTopByIndexCodeOrderByTradingDateDesc(code)
                .map(com.steven.assets.model.UsIndexDailyHistory::getTradingDate);
    }
}
