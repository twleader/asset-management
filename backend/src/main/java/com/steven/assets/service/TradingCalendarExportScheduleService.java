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
import java.util.ArrayList;
import java.util.List;
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
        // format 欄位自 Requirement 55 起停用（一律雙格式）；DB 欄位保留但程式不再讀寫
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
    public TradingCalendarExportDto.RangeRunResponse runManualForCurrentUser(String subpath) {
        TradingCalendarExportDto.RangeRunResponse local =
                exportService.exportYearPairToDir(LocalDate.now(TW_ZONE).getYear(), subpath);
        CurrentUserContext ctx = currentUserProvider.getObject();
        TradingCalendarExportSchedule s = null;
        if (ctx.hasUser()) {
            s = settingRepo.findByOwnerUserId(ctx.getEffectiveUserId()).orElse(null);
        }
        TradingCalendarExportDto.RangeRunResponse completed = attachDrive(local, s);
        if (s != null && s.isGdriveEnabled()) saveQuietly(s);
        return completed;
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

    /** 背景：以該列 subpath 匯出今年與明年交易日曆並更新 guard／狀態。 */
    private void runScheduled(TradingCalendarExportSchedule s, LocalDate today) {
        try {
            TradingCalendarExportDto.RangeRunResponse raw =
                    exportService.exportYearPairToDir(today.getYear(), s.getOutputSubpath());
            TradingCalendarExportDto.RangeRunResponse r = attachDrive(raw, s);
            // 一律沿用共用元件算好、已截斷的字串：自組會在兩份都失敗時記成假的「成功：null／null」，
            // 且無截斷會讓 varchar(500) 溢位而把一次本機其實已成功的匯出記成失敗。
            // 上游那次截斷是跨類別的約定；寫入點自己再截一次，上游算式改動也不會演變成溢位。
            s.setLastRunStatus(truncate(r.localStatus(), STATUS_MAX));
            if (r.gdriveStatus() != null) s.setGdriveLastStatus(truncate(r.gdriveStatus(), GDRIVE_STATUS_MAX));
            log.info("交易日曆排程匯出完成 owner={}：{}", s.getOwnerUserId(), r.localStatus());
        } catch (Exception e) {
            // 例外訊息無界（含絕對路徑／IO 原始訊息）：不截會 varchar(500) 溢位→整筆回滾→
            // last_run_date 這個當日 guard 也寫不進去，該筆排程從到點起每分鐘重試到午夜。
            s.setLastRunStatus(truncate("失敗：" + e.getMessage(), STATUS_MAX));
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
    /** {@code last_run_status} 的欄位上限（實測自運行中 DB，{@code varchar(500)}）。 */
    private static final int STATUS_MAX = 500;
    /** {@code gdrive_last_status} 的欄位上限（實測自運行中 DB）。 */
    private static final int GDRIVE_STATUS_MAX = 512;
    /** 合併前每半的上限：扣掉 {@code "xlsx "} 與 {@code "／json "} 共 12 字元的固定開銷後對半分。 */
    private static final int GDRIVE_HALF_MAX = (GDRIVE_STATUS_MAX - 12) / 2;

    /**
     * 上傳<b>兩份</b>到同一個 Drive 子路徑（Requirement 55 / Task 271.3.2.1）。
     *
     * <p>{@code exportToDir} 的簽章不含 owner 與 Drive 設定，結構上走不到
     * {@code DualFormatExportWriter} 的 Drive 段，故本匯出點與警示觸發同型：狀態字串的合併與截斷
     * 必須自己做。字串契約沿用共用元件的 {@code "xlsx …／json …"}，兩半<b>各自先截斷再合併</b>
     * ——合併後才截尾會把 {@code ／json …} 整段切掉，違反「必須能分辨是哪一份」。
     */
    /** 兩份的 Drive 落點與合併後的狀態字串。 */
    private record BothUploaded(String status, String xlsxPath, String jsonPath) {}

    private TradingCalendarExportDto.RangeRunResponse attachDrive(
            TradingCalendarExportDto.RangeRunResponse local, TradingCalendarExportSchedule setting) {
        if (setting == null || !setting.isGdriveEnabled()) return local;
        List<TradingCalendarExportDto.RunResponse> results = new ArrayList<>(2);
        for (TradingCalendarExportDto.RunResponse r : local.results()) {
            BothUploaded uploaded;
            if (r.path() != null && r.jsonPath() != null) {
                uploaded = syncGdriveBoth(setting, Path.of(r.path()), Path.of(r.jsonPath()));
            } else {
                String skipped = "xlsx 略過：" + r.year() + " 年本機雙格式未完整／json 略過："
                        + r.year() + " 年本機雙格式未完整";
                uploaded = new BothUploaded(skipped, null, null);
            }
            results.add(TradingCalendarExportDto.RunResponse.builder()
                    .path(r.path()).sizeBytes(r.sizeBytes()).jsonPath(r.jsonPath()).jsonSizeBytes(r.jsonSizeBytes())
                    .year(r.year()).totalDays(r.totalDays()).localStatus(r.localStatus())
                    .gdrivePath(uploaded.xlsxPath()).jsonGdrivePath(uploaded.jsonPath()).gdriveStatus(uploaded.status())
                    .build());
        }
        String gdriveStatus = TradingCalendarExportService.aggregateStatus(
                results, TradingCalendarExportDto.RunResponse::gdriveStatus, GDRIVE_STATUS_MAX);
        setting.setGdriveLastRunAt(LocalDateTime.now(TW_ZONE));
        setting.setGdriveLastStatus(gdriveStatus);
        return new TradingCalendarExportDto.RangeRunResponse(local.years(), results, local.localStatus(), gdriveStatus);
    }

    private BothUploaded syncGdriveBoth(TradingCalendarExportSchedule s,
                                        Path xlsxFile, Path jsonFile) {
        if (!s.isGdriveEnabled()) return null;
        GdriveOutputSupport.SyncResult x =
                gdrive.syncQuietly(s.getOwnerUserId(), s.getGdriveSubpath(), xlsxFile);
        GdriveOutputSupport.SyncResult j =
                gdrive.syncQuietly(s.getOwnerUserId(), s.getGdriveSubpath(), jsonFile);
        String merged = "xlsx " + truncate(x.status(), GDRIVE_HALF_MAX)
                + "／json " + truncate(j.status(), GDRIVE_HALF_MAX);
        s.setGdriveLastRunAt(LocalDateTime.now(TW_ZONE));
        s.setGdriveLastStatus(merged);
        // 兩個落點都要回：既有 gdrivePath 指 xlsx、新增的 jsonGdrivePath 指 json。
        // 只回 xlsx 會讓 jsonGdrivePath 恆為 null，前端無從分辨「沒上傳」與「有上傳但沒回報」。
        return new BothUploaded(merged, x.path(), j.path());
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

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
