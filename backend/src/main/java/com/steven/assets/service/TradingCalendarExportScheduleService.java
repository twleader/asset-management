package com.steven.assets.service;

import com.steven.assets.dto.TradingCalendarExportDto;
import com.steven.assets.model.TradingCalendarExportSchedule;
import com.steven.assets.repository.TradingCalendarExportScheduleRepository;
import com.steven.assets.security.CurrentUserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 交易日曆每日排程自動匯出（Requirement 37 / Task 185）。
 *
 * <p>每個使用者可各自設定啟用開關、每日執行時分、格式（json/excel）、輸出相對子路徑。比照
 * {@link ExportScheduleService} 的「每分鐘 poll ＋ 當日 guard ＋ 開機自癒補跑」機制。
 *
 * <p>租戶隔離：GET/PUT 走 HTTP（BFF→business），由 {@code TenantFilterAspect} 自動 owner-scoped 到本人；
 * 背景 poll 無 request context → {@code ownerFilter} 不啟用，{@code findAll()} 讀全部 owner 列。
 * 與 {@link ExportScheduleService} 不同：交易日曆為**全域資料**，產檔時直接呼叫
 * {@link TradingCalendarExportService#exportToDir}（**無需** {@code enableFilter} 縮資產）。
 */
@Service
@Slf4j
public class TradingCalendarExportScheduleService {

    private static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TradingCalendarExportScheduleRepository settingRepo;
    private final TradingCalendarExportService exportService;
    private final ObjectProvider<CurrentUserContext> currentUserProvider;

    /** 容器內基底輸出目錄（與 Requirement 34 共用同一 volume）。 */
    private final String baseDir;

    /** 避免每分鐘 poll 在上一輪尚未跑完時重入。 */
    private final AtomicBoolean ticking = new AtomicBoolean(false);

    public TradingCalendarExportScheduleService(TradingCalendarExportScheduleRepository settingRepo,
                                                TradingCalendarExportService exportService,
                                                ObjectProvider<CurrentUserContext> currentUserProvider,
                                                @Value("${EXPORT_OUTPUT_DIR:/home/steven}") String baseDir) {
        this.settingRepo = settingRepo;
        this.exportService = exportService;
        this.currentUserProvider = currentUserProvider;
        this.baseDir = baseDir;
    }

    // ===== HTTP（owner-scoped）=====

    /** 取當前使用者排程設定；無則回預設值（不寫入 DB）。 */
    public TradingCalendarExportDto.ScheduleSettingResponse getForCurrentUser() {
        Long ownerId = requireOwnerId();
        TradingCalendarExportSchedule s = settingRepo.findByOwnerUserId(ownerId).orElseGet(() ->
                TradingCalendarExportSchedule.builder().ownerUserId(ownerId).build());
        return toResponse(s);
    }

    /** upsert 當前使用者設定。 */
    public TradingCalendarExportDto.ScheduleSettingResponse updateForCurrentUser(
            TradingCalendarExportDto.ScheduleSettingRequest req) {
        Long ownerId = requireOwnerId();
        int hour = req.runHour() == null ? 8 : req.runHour();
        int minute = req.runMinute() == null ? 0 : req.runMinute();
        if (hour < 0 || hour > 23) throw new IllegalArgumentException("執行時(hour)必須介於 0～23");
        if (minute < 0 || minute > 59) throw new IllegalArgumentException("執行分(minute)必須介於 0～59");
        String format = exportService.requireValidFormat(req.format());       // 驗 json/excel（丟即擋）
        String subpath = exportService.requireValidSubpath(req.outputSubpath()); // 驗不跳脫（丟即擋）

        TradingCalendarExportSchedule s = settingRepo.findByOwnerUserId(ownerId).orElseGet(() ->
                TradingCalendarExportSchedule.builder().ownerUserId(ownerId).build());
        s.setOwnerUserId(ownerId);
        s.setEnabled(Boolean.TRUE.equals(req.enabled()));
        s.setRunHour(hour);
        s.setRunMinute(minute);
        s.setFormat(format);
        s.setOutputSubpath(subpath);
        s.setUpdatedAt(LocalDateTime.now(TW_ZONE));
        return toResponse(settingRepo.save(s));
    }

    // ===== 背景排程 =====

    /** 每分鐘檢查各使用者設定，命中執行時間且當日未跑者即產檔。 */
    @Scheduled(cron = "0 * * * * *", zone = "Asia/Taipei")
    public void tick() {
        if (!ticking.compareAndSet(false, true)) {
            log.debug("上一輪交易日曆排程匯出尚未結束，跳過本次 tick");
            return;
        }
        try {
            runDueExports();
        } catch (RuntimeException e) {
            log.warn("交易日曆排程匯出 tick 發生例外：{}", e.getMessage(), e);
        } finally {
            ticking.set(false);
        }
    }

    /** 服務重啟自癒：補跑「今日排程時間已到但尚未執行」者（與 tick 同一判斷，冪等）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void selfHealOnStartup() {
        try {
            runDueExports();
        } catch (RuntimeException e) {
            log.warn("交易日曆排程匯出開機自癒失敗：{}", e.getMessage(), e);
        }
    }

    /**
     * 掃所有啟用中的設定，對「今日尚未執行且排程時間已到」者產檔（{@code now >= 排程時間} ＋ 當日 guard，
     * 比照 {@link ExportScheduleService}，避免被其他長工作卡住跨越分鐘而整日漏跑）。
     */
    private void runDueExports() {
        LocalDate today = LocalDate.now(TW_ZONE);
        LocalTime now = LocalTime.now(TW_ZONE);
        for (TradingCalendarExportSchedule s : settingRepo.findAll()) {
            if (!Boolean.TRUE.equals(s.getEnabled())) continue;
            if (today.equals(s.getLastRunDate())) continue;
            if (!now.isBefore(LocalTime.of(s.getRunHour(), s.getRunMinute()))) {
                runScheduled(s, today);
            }
        }
    }

    /** 背景：以該列 format/subpath 匯出「當前年度」交易日曆並更新 guard／狀態。單一使用者失敗只記錄、不影響其他人。 */
    private void runScheduled(TradingCalendarExportSchedule s, LocalDate today) {
        try {
            // 交易日曆為全域資料，無需 owner 過濾；匯出當前西元年（隨年度/颱風假更新保持最新）。
            TradingCalendarExportDto.RunResponse r =
                    exportService.exportToDir(today.getYear(), s.getFormat(), s.getOutputSubpath());
            s.setLastRunStatus("成功：" + r.path());
            log.info("交易日曆排程匯出成功 owner={} format={} → {}（{} bytes）",
                    s.getOwnerUserId(), s.getFormat(), r.path(), r.sizeBytes());
        } catch (Exception e) {
            s.setLastRunStatus("失敗：" + e.getMessage());
            log.warn("交易日曆排程匯出失敗 owner={}：{}", s.getOwnerUserId(), e.getMessage(), e);
        } finally {
            s.setLastRunDate(today);
            s.setLastRunAt(LocalDateTime.now(TW_ZONE));
            settingRepo.save(s);
        }
    }

    // ===== 輔助 =====

    private Long requireOwnerId() {
        CurrentUserContext ctx = currentUserProvider.getObject();
        if (!ctx.hasUser()) {
            throw new IllegalStateException("未識別使用者，無法存取排程設定");
        }
        return ctx.getEffectiveUserId();
    }

    private TradingCalendarExportDto.ScheduleSettingResponse toResponse(TradingCalendarExportSchedule s) {
        return TradingCalendarExportDto.ScheduleSettingResponse.builder()
                .enabled(Boolean.TRUE.equals(s.getEnabled()))
                .runHour(s.getRunHour())
                .runMinute(s.getRunMinute())
                .format(s.getFormat())
                .outputSubpath(s.getOutputSubpath())
                .lastRunAt(s.getLastRunAt() == null ? null : s.getLastRunAt().format(TS_FMT))
                .lastRunStatus(s.getLastRunStatus())
                .baseDir(baseDir)
                .build();
    }
}
