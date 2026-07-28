package com.steven.assets.service;

import com.steven.assets.dto.TradingCalendarExportDto;
import com.steven.assets.model.TradingCalendarExportSchedule;
import com.steven.assets.repository.TradingCalendarExportScheduleRepository;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.security.UnauthenticatedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
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
    private final GdriveOutputSupport gdrive;

    /** 容器內基底輸出目錄（與 Requirement 34 共用同一 volume）。 */
    private final String baseDir;

    /** 避免每分鐘 poll 在上一輪尚未跑完時重入。 */
    private final AtomicBoolean ticking = new AtomicBoolean(false);

    public TradingCalendarExportScheduleService(TradingCalendarExportScheduleRepository settingRepo,
                                                TradingCalendarExportService exportService,
                                                ObjectProvider<CurrentUserContext> currentUserProvider,
                                                GdriveOutputSupport gdrive,
                                                @Value("${EXPORT_OUTPUT_DIR:/home/steven}") String baseDir) {
        this.settingRepo = settingRepo;
        this.exportService = exportService;
        this.currentUserProvider = currentUserProvider;
        this.gdrive = gdrive;
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
        // Drive 兩欄一律走共用元件：null 解析（未送出＝不變更）、驗證、啟用時必填、只有主要管理者能啟用（403）。
        GdriveOutputSupport.DriveSettings drive = gdrive.resolveUpdate(
                ownerId, req.gdriveEnabled(), req.gdriveSubpath(), s.isGdriveEnabled(), s.getGdriveSubpath());

        s.setOwnerUserId(ownerId);
        s.setEnabled(Boolean.TRUE.equals(req.enabled()));
        s.setRunHour(hour);
        s.setRunMinute(minute);
        s.setFormat(format);
        s.setOutputSubpath(subpath);
        s.setGdriveEnabled(drive.enabled());
        s.setGdriveSubpath(drive.subpath());
        // 刻意不碰 gdriveLastRunAt／gdriveLastStatus：那是執行結果，不是使用者設定。
        // 啟用當下的自檢結果同樣不寫那兩欄（Task 247.3.4），只走當次回應。
        s.setUpdatedAt(LocalDateTime.now(TW_ZONE));
        return toResponse(settingRepo.save(s), drive.selfCheckWarning());
    }

    /**
     * 手動匯出（{@code POST /api/trading-calendar-export/run}）：本機照寫，Drive 已啟用時另上傳一份。
     *
     * <p><b>本頁沒有 run-now</b>，手動匯出就是這一支，故它也必須上傳——否則使用者只能等排程才知道
     * Drive 設定對不對（這正是 Task 241 在爬蟲頁缺 run-now 造成的實際不便）。
     *
     * <p><b>兩個 subpath 的來源刻意不同</b>：本機目錄來自 query param（「這次匯出到哪」），Drive 目錄取自
     * <b>排程設定列</b>（「Drive 同步的固定目的地」）。若讓 Drive 也吃 query param，使用者每次手動匯出都
     * 可能把檔案倒進 Drive 的不同位置。
     *
     * <p>設定列不存在（只手動匯出、從未設過排程）或未啟用時不上傳，也不建列。
     */
    public TradingCalendarExportDto.RunResponse runManualForCurrentUser(
            Integer year, String format, String subpath) {
        int targetYear = year != null ? year : LocalDate.now(TW_ZONE).getYear();
        // 本機一律先寫；失敗會直接往上拋（既有行為），此時完全不上傳。
        TradingCalendarExportDto.RunResponse local = exportService.exportToDir(targetYear, format, subpath);

        GdriveOutputSupport.SyncResult drive = null;
        CurrentUserContext ctx = currentUserProvider.getObject();
        if (ctx.hasUser()) {
            TradingCalendarExportSchedule s =
                    settingRepo.findByOwnerUserId(ctx.getEffectiveUserId()).orElse(null);
            if (s != null) {
                drive = syncGdrive(s, Path.of(local.path()));
                if (drive != null) saveQuietly(s);
            }
        }
        return TradingCalendarExportDto.RunResponse.builder()
                .path(local.path())
                .sizeBytes(local.sizeBytes())
                .format(local.format())
                .year(local.year())
                .totalDays(local.totalDays())
                .gdrivePath(drive == null ? null : drive.path())
                .gdriveStatus(drive == null ? null : drive.status())
                .build();
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
            // 本機寫成功後才上傳；狀態欄由下方 finally 既有的 save 一併寫入。
            syncGdrive(s, Path.of(r.path()));
        } catch (Exception e) {
            s.setLastRunStatus("失敗：" + e.getMessage());
            log.warn("交易日曆排程匯出失敗 owner={}：{}", s.getOwnerUserId(), e.getMessage(), e);
            syncGdrive(s, null); // 本機失敗＝不上傳；已啟用時仍寫狀態欄，否則會停在上一次的成功
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
            throw new UnauthenticatedException("未識別使用者，無法存取排程設定");
        }
        return ctx.getEffectiveUserId();
    }

    /**
     * 本機檔寫成功後的 Drive 同步（best-effort，Requirement 51 / Task 244）。
     *
     * <p><b>順序不可顛倒</b>：本機那一份是既有的留存機制，必須先確定它寫成功才上傳；本機失敗時傳
     * {@code null} ——完全不上傳，絕不上傳前一次的舊檔，但已啟用時仍寫狀態欄說明原因。
     *
     * <p>不擲例外、不改既有 {@code lastRunStatus}——「本機成功、Drive 失敗」是正常且必須可分辨的狀態。
     * owner 權限在 {@code syncQuietly} 內<b>每一輪重驗</b>（背景排程沒有 request context）。
     *
     * <p><b>只改記憶體中的欄位、不自行 save</b>：排程路徑由 {@code runScheduled} 的 finally 一併寫入，
     * 手動路徑由 {@link #saveQuietly} 寫入。
     */
    private GdriveOutputSupport.SyncResult syncGdrive(TradingCalendarExportSchedule s, Path localFile) {
        if (!s.isGdriveEnabled()) return null;
        GdriveOutputSupport.SyncResult r =
                gdrive.syncQuietly(s.getOwnerUserId(), s.getGdriveSubpath(), localFile);
        s.setGdriveLastRunAt(LocalDateTime.now(TW_ZONE));
        s.setGdriveLastStatus(r.status());
        return r;
    }

    /** 「回報結果」這件事本身不能成為新的失敗來源（DB 短暫不可用時 save 會擲例外）。 */
    private void saveQuietly(TradingCalendarExportSchedule s) {
        try {
            settingRepo.save(s);
        } catch (RuntimeException e) {
            log.error("寫入 Drive 同步狀態失敗 owner={}：{}", s.getOwnerUserId(), e.getMessage(), e);
        }
    }

    private TradingCalendarExportDto.ScheduleSettingResponse toResponse(TradingCalendarExportSchedule s) {
        return toResponse(s, null);   // 讀取路徑不做自檢：自檢只在「使用者這次把開關打開」時才有意義
    }

    /**
     * @param gdriveSelfCheckWarning 本次啟用 Drive 時的自檢警告（Task 247.3.5）；正常時為 {@code null}。
     *                               <b>不入庫</b>——尤其不得寫進 {@code gdriveLastStatus}，那一欄是「上次上傳」
     */
    private TradingCalendarExportDto.ScheduleSettingResponse toResponse(TradingCalendarExportSchedule s,
                                                                        String gdriveSelfCheckWarning) {
        return TradingCalendarExportDto.ScheduleSettingResponse.builder()
                .enabled(Boolean.TRUE.equals(s.getEnabled()))
                .runHour(s.getRunHour())
                .runMinute(s.getRunMinute())
                .format(s.getFormat())
                .outputSubpath(s.getOutputSubpath())
                .lastRunAt(s.getLastRunAt() == null ? null : s.getLastRunAt().format(TS_FMT))
                .lastRunStatus(s.getLastRunStatus())
                .baseDir(baseDir)
                // 讀取一律不驗證 Drive 子路徑：DB 值可能被繞過 API 直改，若讀取也擲例外，設定頁會 500
                // 而使用者沒有任何入口能把它改回正常值——唯一的修正入口被自己鎖死。存檔時才驗。
                .gdriveEnabled(s.isGdriveEnabled())
                .gdriveSubpath(s.getGdriveSubpath())
                .gdriveRemote(gdrive.remoteName())
                .gdriveLastRunAt(s.getGdriveLastRunAt() == null ? null : s.getGdriveLastRunAt().format(TS_FMT))
                .gdriveLastStatus(s.getGdriveLastStatus())
                .gdriveSelfCheckWarning(gdriveSelfCheckWarning)
                .build();
    }
}
