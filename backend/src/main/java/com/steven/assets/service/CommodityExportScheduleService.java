package com.steven.assets.service;

import com.steven.assets.repository.ExportSettingCreationRetry;

import com.steven.assets.dto.CommodityExportDto;
import com.steven.assets.model.CommodityExportSchedule;
import com.steven.assets.model.CommodityExportScheduleTime;
import com.steven.assets.repository.CommodityExportScheduleRepository;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.security.UnauthenticatedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 油價金價每日排程自動匯出（Requirement 41 / Task 203；多時間點 Requirement 72 / Task 330）。
 *
 * <p>每個使用者可各自設定啟用開關、多個每日執行時分、輸出相對子路徑與匯出範圍。因 {@code @Scheduled} 的 cron
 * 於啟動期固定、無法吃 DB 可調時間，改採「每分鐘 poll ＋ 當日 guard ＋ 開機自癒補跑」
 * （比照 {@link ExportScheduleService}／{@link RealizedGainExportScheduleService}）。
 *
 * <p><b>租戶隔離</b>：GET/PUT/run-now 走 HTTP（BFF→business），由 {@code TenantFilterAspect} 自動 owner-scoped 到本人；
 * 背景 poll 無 request context → {@code ownerFilter} 不啟用，{@code findAll()} 讀全部 owner 列。
 * 但<b>產檔本身不需縮 owner</b>：{@code commodity_price_history} 為全域公開行情（無 owner 欄位、無 {@code @Filter}），
 * 人人看到的油金價相同，故直接呼叫 {@link ExcelExportService#exportCommodityPrices} 即可。
 * 這與 {@link RealizedGainExportScheduleService}（per-user 損益，不縮 owner 會外洩他人資料）相反，
 * 與 {@link TradingCalendarExportService}（全域交易日曆）相同。
 *
 * <p><b>多時間點（Requirement 72 / Task 330）</b>：排程時間拆成正規化 children（{@link CommodityExportScheduleTime}），
 * 每個時間各自 enabled／guard／status；parent 的 {@code runHour}/{@code runMinute}/{@code lastRunDate} 降為
 * rollback shadow，只在每次設定儲存與 representative child 執行時由 {@code syncRollbackRepresentative} 同步，
 * 不再是新程式的排程來源。{@code rangeMonths}／輸出資料夾／Drive 設定仍為 parent-only，不因執行時段而異；
 * 每個到點 child 各自呼叫一次 {@link ExcelExportService#commodityPricesDoc}，以「執行當下」重新計算滾動起訖日。
 *
 * <p><b>滾動區間</b>：{@code rangeMonths} 為 null 時匯出全部十年，否則以「執行當日往前推 N 個月」計算起訖，
 * 使每日留存的檔案跟著時間滾動，而非固定區間。
 *
 * <p>路徑安全：使用者只設定「相對子路徑」，實際寫入 = 容器基底 {@code EXPORT_OUTPUT_DIR} resolve 子路徑，
 * 並驗證 normalize 後仍在基底內（拒 {@code ..}／絕對路徑跳脫）。
 *
 * <p>資料夾瀏覽不在此服務：沿用 Requirement 34 既有的 {@code GET /api/export-schedule/browse}，
 * 避免同義能力在 business 端出現第四份實作。
 */
@Service
@Slf4j
public class CommodityExportScheduleService {

    private static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** {@code commodity_export_schedule.last_run_status} 的欄位上限（{@code varchar(500)}）。 */
    private static final int STATUS_MAX = 500;
    /** {@code commodity_export_schedule.gdrive_last_status} 的欄位上限（{@code varchar(512)}）。 */
    private static final int GDRIVE_STATUS_MAX = 512;

    /** 匯出範圍上限（月）：十年，與 commodity_price_history 的保留視窗一致。 */
    private static final int MAX_RANGE_MONTHS = 120;

    private final CommodityExportScheduleRepository settingRepo;
    @org.springframework.beans.factory.annotation.Autowired
    private ExportExecutionPort<CommodityExportSchedule> executionStore;
    private final ExcelExportService excelExportService;
    private final ObjectProvider<CurrentUserContext> currentUserProvider;
    private final GdriveOutputSupport gdrive;
    // 雙格式匯出（Requirement 55 / Task 270）：一次查詢取得 doc，再 render 成 xlsx 與 JSON 兩份
    private final com.steven.assets.service.export.ExcelDocRenderer excelDocRenderer;
    private final com.steven.assets.service.export.JsonDocRenderer jsonDocRenderer;
    private final com.steven.assets.service.export.DualFormatExportWriter dualWriter;

    /** 容器內基底輸出目錄，經 docker volume 對映到 host（見 docker-compose.yml）。 */
    private final String baseDir;

    /** 避免每分鐘 poll 在上一輪尚未跑完時重入；startup 自癒與 tick 共用同一把鎖（見 {@link #tryRunDueExports()}）。 */
    private final AtomicBoolean ticking = new AtomicBoolean(false);

    public CommodityExportScheduleService(CommodityExportScheduleRepository settingRepo,
                                          ExcelExportService excelExportService,
                                          ObjectProvider<CurrentUserContext> currentUserProvider,
                                          GdriveOutputSupport gdrive,
                                          com.steven.assets.service.export.ExcelDocRenderer excelDocRenderer,
                                          com.steven.assets.service.export.JsonDocRenderer jsonDocRenderer,
                                          com.steven.assets.service.export.DualFormatExportWriter dualWriter,
                                          @Value("${EXPORT_OUTPUT_DIR:/home/steven}") String baseDir) {
        this.settingRepo = settingRepo;
        this.excelExportService = excelExportService;
        this.currentUserProvider = currentUserProvider;
        this.gdrive = gdrive;
        this.excelDocRenderer = excelDocRenderer;
        this.jsonDocRenderer = jsonDocRenderer;
        this.dualWriter = dualWriter;
        this.baseDir = baseDir;
    }

    // ===== HTTP（owner-scoped）=====

    /** 取當前使用者排程設定；無則回預設值（不寫入 DB）。 */
    public CommodityExportDto.SettingResponse getForCurrentUser() {
        Long ownerId = requireOwnerId();
        CommodityExportSchedule s = settingRepo.findByOwnerUserId(ownerId).orElseGet(() ->
                CommodityExportSchedule.builder().ownerUserId(ownerId).build());
        if (s.getTimes().isEmpty()) s.addTime(legacyTime(s)); // rolling-upgrade fallback；GET 不寫 DB
        return toResponse(s);
    }

    /** upsert 當前使用者設定。 */
    public CommodityExportDto.SettingResponse updateForCurrentUser(CommodityExportDto.SettingRequest req) {
        Long ownerId = requireOwnerId();
        validateTimes(req.times(), Boolean.TRUE.equals(req.enabled()));
        Integer rangeMonths = req.rangeMonths();
        if (rangeMonths != null && (rangeMonths < 1 || rangeMonths > MAX_RANGE_MONTHS)) {
            throw new IllegalArgumentException("匯出範圍(月)必須介於 1～" + MAX_RANGE_MONTHS + "，或留空代表全部十年");
        }
        String subpath = normalizeSubpath(req.outputSubpath());
        resolveDir(subpath); // 驗證不跳脫基底（丟出即擋下）

        return ExportSettingCreationRetry.retry(() -> executionStore.update(ownerId, s -> {
            // Drive 兩欄一律走共用元件：null 解析（未送出＝不變更）、驗證、啟用時必填、只有主要管理者能啟用（403）。
            // 本機路徑與排程時間刻意維持所有使用者皆可設定——只有 Drive 這一項會把資料送出本機。
            GdriveOutputSupport.DriveSettings drive = gdrive.resolveUpdate(
                    ownerId, req.gdriveEnabled(), req.gdriveSubpath(), s.isGdriveEnabled(), s.getGdriveSubpath());

            s.setOwnerUserId(ownerId);
            s.setEnabled(Boolean.TRUE.equals(req.enabled()));
            s.setOutputSubpath(subpath);
            s.setRangeMonths(rangeMonths);
            s.setGdriveEnabled(drive.enabled());
            s.setGdriveSubpath(drive.subpath());
            // 刻意不碰 gdriveLastRunAt／gdriveLastStatus：那是執行結果，不是使用者設定。
            // 啟用當下的自檢結果同樣不寫那兩欄（Task 247.3.4），只走當次回應。
            s.setUpdatedAt(LocalDateTime.now(TW_ZONE));

            Map<String, CommodityExportScheduleTime> existing = new HashMap<>();
            for (CommodityExportScheduleTime time : s.getTimes()) existing.put(timeKey(time.getRunHour(), time.getRunMinute()), time);
            HashSet<String> requested = new HashSet<>();
            for (CommodityExportDto.TimeRequest item : req.times()) {
                String key = timeKey(item.runHour(), item.runMinute());
                requested.add(key);
                CommodityExportScheduleTime time = existing.get(key);
                if (time == null) {
                    time = CommodityExportScheduleTime.builder().runHour(item.runHour()).runMinute(item.runMinute())
                            .enabled(Boolean.TRUE.equals(item.enabled())).updatedAt(LocalDateTime.now(TW_ZONE)).build();
                    s.addTime(time);
                } else {
                    time.setEnabled(Boolean.TRUE.equals(item.enabled()));
                    time.setUpdatedAt(LocalDateTime.now(TW_ZONE));
                }
            }
            s.getTimes().removeIf(time -> !requested.contains(timeKey(time.getRunHour(), time.getRunMinute())));
            syncRollbackRepresentative(s);
            return toResponse(s, drive.selfCheckWarning());
        }));
    }

    /** 立即產檔寫入當前使用者設定的目錄（供驗證路徑正確）。不動任何時間點的當日 guard。 */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public CommodityExportDto.RunNowResponse runNowForCurrentUser() {
        Long ownerId = requireOwnerId();
        CapturedExport s = executionStore.manual(ownerId);
        try {
            var r = export(s, ownerId);
            executionStore.complete(s, LocalDateTime.now(TW_ZONE), r.localStatus(), r.gdriveStatus());
            return CommodityExportDto.RunNowResponse.builder()
                    // 既有三欄語意不變：一律指 xlsx 那一份
                    .path(r.xlsxFile() == null ? null : r.xlsxFile().toString())
                    .sizeBytes(r.xlsxFile() == null ? 0 : Files.size(r.xlsxFile()))
                    .gdrivePath(r.xlsxGdrivePath())
                    .gdriveStatus(r.gdriveStatus())
                    .jsonPath(r.jsonFile() == null ? null : r.jsonFile().toString())
                    .jsonSizeBytes(r.jsonFile() == null ? 0 : Files.size(r.jsonFile()))
                    .jsonGdrivePath(r.jsonGdrivePath())
                    .build();
        } catch (IOException | RuntimeException e) {
            executionStore.complete(s, LocalDateTime.now(TW_ZONE), "失敗：" + e.getMessage(),
                    failedDriveStatus(s));
            throw new RuntimeException("立即匯出失敗：" + e.getMessage(), e);
        }
    }

    // ===== 背景排程 =====

    /** 每分鐘檢查各使用者設定，命中執行時間且當日未跑者即產檔。 */
    @Scheduled(cron = "0 * * * * *", zone = "Asia/Taipei")
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public void tick() {
        try {
            tryRunDueExports();
        } catch (RuntimeException e) {
            log.warn("油價金價排程匯出 tick 發生例外：{}", e.getMessage(), e);
        }
    }

    /** 服務重啟自癒：補跑「今日排程時間已到但尚未執行」者（與 tick 同一判斷，冪等）。 */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    public void selfHealOnStartup() {
        try {
            tryRunDueExports();
        } catch (RuntimeException e) {
            log.warn("油價金價排程匯出開機自癒失敗：{}", e.getMessage(), e);
        }
    }

    /** {@code tick} 與 {@code selfHealOnStartup} 共用的守門入口，防止啟動瞬間與跨分鐘 tick 重入同一輪。 */
    private void tryRunDueExports() {
        if (!ticking.compareAndSet(false, true)) {
            log.debug("上一輪油價金價排程匯出尚未結束，跳過本次觸發");
            return;
        }
        try {
            runDueExports();
        } finally {
            ticking.set(false);
        }
    }

    /**
     * 掃所有啟用中的設定，對每個「今日尚未執行且排程時間已到」的時間點產檔。
     *
     * <p>用 {@code now >= 排程時間}（而非「分鐘精確相等」）＋ child 各自的 {@code lastRunDate} 當日 guard：
     * 任何被排程執行緒延遲／跳過的分鐘（Spring 預設排程池只有 1 條執行緒、與其他 @Scheduled 共用，可能被
     * 長工作卡住跨越分鐘），都會在後續 tick 自動補跑，直到當日成功並把該時間點的 lastRunDate 設為今日為止，
     * 避免整日靜默漏跑。判斷主體先 parent 總開關、再逐 child——第一個時間點執行完只會擋住該 child，
     * 不會用 parent guard 擋住同日後續 child。
     */
    void runDueExports() {
        LocalDate today = LocalDate.now(TW_ZONE);
        LocalTime now = LocalTime.now(TW_ZONE);
        for (CommodityExportSchedule candidate : settingRepo.findAll()) {
            try {
                for (CapturedExport capture : executionStore.due(candidate.getId(), candidate.getOwnerUserId(), today, now)) {
                    try { runScheduled(capture); }
                    catch (RuntimeException e) { log.warn("匯出結果寫回失敗 owner={} child={}", capture.ownerId(), capture.childId(), e); }
                }
            } catch (RuntimeException e) {
                log.warn("匯出設定捕捉失敗 owner={}", candidate.getOwnerUserId(), e);
            }
        }
    }

    /** 背景：對指定時間點產檔並更新該 child 的 guard／狀態，同步 parent 最近一次整體摘要。單一時間點失敗只記錄、不影響其他時間點或其他 owner。 */
    private void runScheduled(CapturedExport s) {
        String localStatus;
        String driveStatus;
        try {
            var result = export(s, s.getOwnerUserId());
            localStatus = result.localStatus(); driveStatus = result.gdriveStatus();
            log.info("排程匯出 owner={} → {}", s.getOwnerUserId(), localStatus);
        } catch (Exception e) {
            localStatus = "失敗：" + e.getMessage();
            driveStatus = failedDriveStatus(s);
            log.warn("排程匯出 owner={} 失敗", s.getOwnerUserId(), e);
        }
        executionStore.complete(s, LocalDateTime.now(TW_ZONE), localStatus, driveStatus);
    }

    // ===== 輔助 =====

    /**
     * 依設定的滾動區間產檔並寫入目錄，回傳實際落點。
     * 與手動匯出（Requirement 40）走同一支 {@code exportCommodityPrices}，確保兩種途徑內容一致。
     * 每次呼叫都以「呼叫當下」重新計算起訖日——多時間點時每個到點 child 各自呼叫一次本方法。
     */
    private com.steven.assets.service.export.DualFormatExportWriter.DualResult export(
            CapturedExport s, Long ownerId) throws IOException {
        LocalDate end = LocalDate.now(TW_ZONE);
        Integer months = s.getRangeMonths();
        LocalDate start = months == null ? end.minusYears(10) : end.minusMonths(months);
        // 一次查詢取得 doc（不得為兩種格式各查一次）
        var doc = excelExportService.commodityPricesDoc(start, end);
        String baseName = "油價金價_" + ownerId + "_" + end.format(FILE_DATE);
        return writeDual(s, ownerId, doc, baseName);
    }
    /**
     * 一次查詢的 doc → render 兩種格式 → 寫兩份檔（Requirement 55 / Task 270）。
     *
     * <p><b>主檔名不含副檔名</b>，由 {@code DualFormatExportWriter} 各自加上 {@code .xlsx}／{@code .json}
     * ——呼叫端沒有機會讓兩份分岔。<b>兩支 render 各自 try/catch、失敗的那一份傳 null</b>：
     * 一份 render 失敗不得中斷另一份的寫入。
     *
     * <p>{@code resolveDir} 不可省略——它擋的是使用者設定的子路徑跳脫基底，與共用元件擋的
     * 「主檔名不得含路徑分隔字元」是兩道不同的防線。
     */
    private com.steven.assets.service.export.DualFormatExportWriter.DualResult writeDual(
            CapturedExport s, Long ownerId,
            com.steven.assets.service.export.ExportDoc doc, String baseName) throws IOException {
        byte[] xlsx = null;
        byte[] json = null;
        try {
            xlsx = excelDocRenderer.render(doc);
        } catch (Exception e) {
            log.warn("油價金價 xlsx render 失敗 owner={}：{}", ownerId, e.getMessage(), e);
        }
        try {
            json = jsonDocRenderer.render(doc);
        } catch (Exception e) {
            log.warn("油價金價 json render 失敗 owner={}：{}", ownerId, e.getMessage(), e);
        }
        return dualWriter.write(ownerId, resolveDir(normalizeSubpath(s.getOutputSubpath())),
                baseName, json, xlsx, s.isGdriveEnabled(), s.getGdriveSubpath());
    }

/** Null local file is handled without any remote I/O, preserving the existing skipped status. */
    private String failedDriveStatus(CapturedExport s) {
        return s.isGdriveEnabled() ? gdrive.syncQuietly(s.getOwnerUserId(), s.getGdriveSubpath(), null).status() : null;
    }

    private Long requireOwnerId() {
        CurrentUserContext ctx = currentUserProvider.getObject();
        if (!ctx.hasUser()) {
            throw new UnauthenticatedException("未識別使用者，無法存取排程設定");
        }
        return ctx.getEffectiveUserId();
    }

    private static String normalizeSubpath(String subpath) {
        String sub = subpath == null ? "" : subpath.trim();
        if (sub.isEmpty()) sub = "input";
        return sub;
    }

    private static CommodityExportScheduleTime defaultTime() {
        return CommodityExportScheduleTime.builder().runHour(8).runMinute(0).enabled(Boolean.TRUE).build();
    }

    /** rolling-upgrade fallback：以 parent 既有的 legacy 三欄重建一個等價 child（不寫 DB，呼叫端決定是否 persist）。 */
    private static CommodityExportScheduleTime legacyTime(CommodityExportSchedule schedule) {
        return CommodityExportScheduleTime.builder()
                .runHour(schedule.getRunHour() == null ? 8 : schedule.getRunHour())
                .runMinute(schedule.getRunMinute() == null ? 0 : schedule.getRunMinute())
                .enabled(Boolean.TRUE)
                .lastRunDate(schedule.getLastRunDate())
                .lastRunAt(schedule.getLastRunAt())
                .lastRunStatus(schedule.getLastRunStatus())
                .build();
    }

    private static String timeKey(Integer hour, Integer minute) { return hour + ":" + minute; }

    private static void validateTimes(List<CommodityExportDto.TimeRequest> times, boolean parentEnabled) {
        if (times == null || times.isEmpty()) throw new IllegalArgumentException("至少需要一個執行時間");
        HashSet<String> seen = new HashSet<>();
        boolean anyEnabled = false;
        for (CommodityExportDto.TimeRequest time : times) {
            if (time == null || time.runHour() == null || time.runMinute() == null
                    || time.runHour() < 0 || time.runHour() > 23 || time.runMinute() < 0 || time.runMinute() > 59) {
                throw new IllegalArgumentException("執行時間必須介於 00:00～23:59");
            }
            if (!seen.add(timeKey(time.runHour(), time.runMinute()))) throw new IllegalArgumentException("執行時間不可重複");
            anyEnabled |= Boolean.TRUE.equals(time.enabled());
        }
        if (parentEnabled && !anyEnabled) throw new IllegalArgumentException("啟用排程時至少需啟用一個時間");
    }

    /**
     * rollback representative = 最早 enabled child；全停用時是最早 child。
     *
     * <p>只同步舊 image 真正用來判斷排程的時分與 daily guard。Parent 的
     * {@code lastRunAt/lastRunStatus} 是新舊 UI 共用的「最近一次整體執行摘要」，必須保留
     * 最後完成的 child／run-now 結果，不能被較早 representative 的舊狀態覆蓋。
     */
    private static void syncRollbackRepresentative(CommodityExportSchedule s) {
        CommodityExportScheduleTime representative = s.getTimes().stream()
                .filter(t -> Boolean.TRUE.equals(t.getEnabled())).min(CommodityExportScheduleService::compareTime)
                .orElseGet(() -> s.getTimes().stream().min(CommodityExportScheduleService::compareTime).orElse(null));
        if (representative == null) return;
        s.setRunHour(representative.getRunHour());
        s.setRunMinute(representative.getRunMinute());
        s.setLastRunDate(representative.getLastRunDate());
    }

    private static int compareTime(CommodityExportScheduleTime left, CommodityExportScheduleTime right) {
        int hour = Integer.compare(left.getRunHour(), right.getRunHour());
        if (hour != 0) return hour;
        int minute = Integer.compare(left.getRunMinute(), right.getRunMinute());
        if (minute != 0) return minute;
        return Comparator.nullsLast(Long::compareTo).compare(left.getId(), right.getId());
    }

    /** 基底 resolve 子路徑並驗證仍在基底內（拒 `..`／絕對路徑跳脫）。 */
    private Path resolveDir(String subpath) {
        Path base = Path.of(baseDir).toAbsolutePath().normalize();
        Path target = base.resolve(subpath).normalize();
        if (!target.startsWith(base)) {
            throw new IllegalArgumentException("輸出子路徑不可跳脫基底目錄：" + subpath);
        }
        return target;
    }


    private CommodityExportDto.SettingResponse toResponse(CommodityExportSchedule s) {
        return toResponse(s, null);   // 讀取路徑不做自檢：自檢只在「使用者這次把開關打開」時才有意義
    }

    /**
     * @param gdriveSelfCheckWarning 本次啟用 Drive 時的自檢警告（Task 247.3.5）；正常時為 {@code null}。
     *                               <b>不入庫</b>——尤其不得寫進 {@code gdriveLastStatus}，那一欄是「上次上傳」
     */
    private CommodityExportDto.SettingResponse toResponse(CommodityExportSchedule s,
                                                          String gdriveSelfCheckWarning) {
        return CommodityExportDto.SettingResponse.builder()
                .enabled(Boolean.TRUE.equals(s.getEnabled()))
                .outputSubpath(s.getOutputSubpath())
                .rangeMonths(s.getRangeMonths())
                .times(s.getTimes().stream()
                        .sorted(Comparator.comparing(CommodityExportScheduleTime::getRunHour)
                                .thenComparing(CommodityExportScheduleTime::getRunMinute)
                                .thenComparing(t -> t.getId() == null ? Long.MAX_VALUE : t.getId()))
                        .map(t -> new CommodityExportDto.TimeResponse(t.getId(), t.getRunHour(), t.getRunMinute(),
                                t.getEnabled(), t.getLastRunAt() == null ? null : t.getLastRunAt().format(TS_FMT),
                                t.getLastRunStatus()))
                        .toList())
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

/**
     * 狀態字串寫入前的截斷（對應 {@code varchar(500)} 與 {@code varchar(512)}）。
     *
     * <p>成功時狀態內含絕對路徑、失敗時內含 rclone 或 IO 的原始錯誤訊息，長度無上限。
     * <b>寫入失敗會整筆交易回滾</b>，連帶 {@code last_run_date} 這個當日 guard 也寫不進去，
     * 該排程便從到點起每分鐘重試到午夜——截斷是為了擋掉這條回滾路徑，不只是為了好看。
     *
     * <p>{@code null} 進 {@code null} 出：兩欄皆 nullable，既有邏輯靠 null 表達「尚未執行」。
     */
    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
