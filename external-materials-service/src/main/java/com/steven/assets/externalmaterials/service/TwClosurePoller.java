package com.steven.assets.externalmaterials.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZonedDateTime;

/**
 * 台股颱風假偵測排程（Requirement 7 / Task 160）。
 *
 * <p>台股開盤前每 15 分鐘（05:00–08:45 Asia/Taipei、平日）爬 DGPA 停班公告判臺北市停班；命中即寫入
 * {@code tw_market_closure}，於 09:00 開盤前令台股一體休市。開機 {@link ApplicationReadyEvent} 亦 self-heal
 * 補跑一次（部署 / 重啟後立即修正當日狀態，含收盤後把當日休市補進日曆供回溯）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TwClosurePoller {

    private final TwTyphoonClosureService closure;

    @Value("${typhoon-closure.enabled:true}")
    private boolean enabled;

    /** 平日 05:00–08:45（Asia/Taipei）每 15 分鐘偵測，於 09:00 開盤前生效。 */
    @Scheduled(cron = "0 0/15 5-8 * * MON-FRI", zone = "Asia/Taipei")
    public void scheduled() {
        if (!enabled) return;
        closure.detectAndPersistToday();
    }

    /** 開機 self-heal：平日補跑一次（避免部署 / crash 錯過開盤前排程）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void selfHealOnStartup() {
        if (!enabled) {
            log.info("颱風假偵測已停用（typhoon-closure.enabled=false），略過 self-heal");
            return;
        }
        new Thread(() -> {
            try {
                Thread.sleep(8000);
                ZonedDateTime now = ZonedDateTime.now(TwTyphoonClosureService.TW_ZONE);
                DayOfWeek dow = now.getDayOfWeek();
                if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) return;
                // DGPA「今天」段反映當日；深夜（≥23:00）頁面可能已翻為隔日，避免誤讀 → 略過
                if (now.toLocalTime().isAfter(LocalTime.of(23, 0))) return;
                closure.detectAndPersistToday();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("颱風假 self-heal 失敗：{}", e.getMessage());
            }
        }, "typhoon-closure-selfheal").start();
    }
}
