package com.steven.assets.service;

import com.steven.assets.dto.ExportScheduleDto;
import com.steven.assets.model.ExportScheduleSetting;
import com.steven.assets.repository.ExportScheduleSettingRepository;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.security.UnauthenticatedException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 當前即時資產每日排程自動匯出（Requirement 34 / Task 171）。
 *
 * <p>每個使用者可各自設定啟用開關、每日執行時分、輸出相對子路徑。因 {@code @Scheduled} 的 cron 於啟動期固定、
 * 無法吃 DB 可調時間，改採「每分鐘 poll ＋ 當日 guard ＋ 開機自癒補跑」（比照 {@code MarketAnalysisScheduler}）。
 *
 * <p>租戶隔離：GET/PUT/run-now 走 HTTP（BFF→business），由 {@code TenantFilterAspect} 自動 owner-scoped 到本人；
 * 背景 poll 無 request context → {@code ownerFilter} 不啟用，{@code findAll()} 讀全部 owner 列，
 * 產檔時才以 {@link ExcelExportService#exportLiveAssetsForOwner(Long)} 對該列 owner 手動 {@code enableFilter}。
 *
 * <p>路徑安全：使用者只設定「相對子路徑」，實際寫入 = 容器基底 {@code EXPORT_OUTPUT_DIR} resolve 子路徑，
 * 並驗證 normalize 後仍在基底內（拒 {@code ..}／絕對路徑跳脫），不允許 UI 指定任意檔案系統路徑。
 */
@Service
@Slf4j
public class ExportScheduleService {

    private static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    /** {@code export_schedule_setting.last_run_status} 的欄位上限（{@code varchar(500)}）。 */
    private static final int STATUS_MAX = 500;
    /** {@code export_schedule_setting.gdrive_last_status} 的欄位上限（{@code varchar(512)}）。 */
    private static final int GDRIVE_STATUS_MAX = 512;

    private final ExportScheduleSettingRepository settingRepo;
    private final ExcelExportService excelExportService;
    private final ObjectProvider<CurrentUserContext> currentUserProvider;

    private final GdriveOutputSupport gdrive;
    // 雙格式匯出（Requirement 55 / Task 271）：一次查詢取得 doc，再 render 成 xlsx 與 JSON 兩份
    private final com.steven.assets.service.export.ExcelDocRenderer excelDocRenderer;
    private final com.steven.assets.service.export.JsonDocRenderer jsonDocRenderer;
    private final com.steven.assets.service.export.DualFormatExportWriter dualWriter;

    /** 容器內基底輸出目錄，經 docker volume 對映到 host（見 docker-compose.yml）。 */
    private final String baseDir;

    /** 避免每分鐘 poll 在上一輪尚未跑完時重入。 */
    private final AtomicBoolean ticking = new AtomicBoolean(false);

    public ExportScheduleService(ExportScheduleSettingRepository settingRepo,
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
    public ExportScheduleDto.SettingResponse getForCurrentUser() {
        Long ownerId = requireOwnerId();
        ExportScheduleSetting s = settingRepo.findByOwnerUserId(ownerId).orElseGet(() ->
                ExportScheduleSetting.builder().ownerUserId(ownerId).build());
        return toResponse(s);
    }

    /** upsert 當前使用者設定。 */
    public ExportScheduleDto.SettingResponse updateForCurrentUser(ExportScheduleDto.SettingRequest req) {
        Long ownerId = requireOwnerId();
        int hour = req.runHour() == null ? 8 : req.runHour();
        int minute = req.runMinute() == null ? 0 : req.runMinute();
        if (hour < 0 || hour > 23) throw new IllegalArgumentException("執行時(hour)必須介於 0～23");
        if (minute < 0 || minute > 59) throw new IllegalArgumentException("執行分(minute)必須介於 0～59");
        String subpath = normalizeSubpath(req.outputSubpath());
        resolveDir(subpath); // 驗證不跳脫基底（丟出即擋下）

        ExportScheduleSetting s = settingRepo.findByOwnerUserId(ownerId).orElseGet(() ->
                ExportScheduleSetting.builder().ownerUserId(ownerId).build());
        // Drive 兩欄一律走共用元件：null 解析（未送出＝不變更）、驗證、啟用時必填、只有主要管理者能啟用（403）。
        // 本機路徑與排程時間刻意維持所有使用者皆可設定——只有 Drive 這一項會把資料送出本機。
        GdriveOutputSupport.DriveSettings drive = gdrive.resolveUpdate(
                ownerId, req.gdriveEnabled(), req.gdriveSubpath(), s.isGdriveEnabled(), s.getGdriveSubpath());

        s.setOwnerUserId(ownerId);
        s.setEnabled(Boolean.TRUE.equals(req.enabled()));
        s.setRunHour(hour);
        s.setRunMinute(minute);
        s.setOutputSubpath(subpath);
        s.setGdriveEnabled(drive.enabled());
        s.setGdriveSubpath(drive.subpath());
        // 刻意不碰 gdriveLastRunAt／gdriveLastStatus：那是執行結果，不是使用者設定。
        // 啟用當下的自檢結果同樣不寫那兩欄（Task 247.3.4），只走當次回應。
        s.setUpdatedAt(LocalDateTime.now(TW_ZONE));
        return toResponse(settingRepo.save(s), drive.selfCheckWarning());
    }

    /** 立即以當前使用者身分產檔寫入其設定目錄（供驗證）。不動當日 guard。 */
    public ExportScheduleDto.RunNowResponse runNowForCurrentUser() {
        Long ownerId = requireOwnerId();
        ExportScheduleSetting s = settingRepo.findByOwnerUserId(ownerId).orElseGet(() ->
                ExportScheduleSetting.builder().ownerUserId(ownerId).build());
        String subpath = normalizeSubpath(s.getOutputSubpath());
        try {
            // HTTP 情境：liveAssetsDoc() 由 TenantFilterAspect 自動 owner-scoped 到當前使用者。
            // 一次查詢取得 doc，再 render 兩種格式——本匯出點吃 Redis 即時價，查兩次會對不起來。
            var r = writeDual(s, ownerId, excelExportService.liveAssetsDoc(), subpath);
            s.setOwnerUserId(ownerId);
            s.setLastRunAt(LocalDateTime.now(TW_ZONE));
            s.setLastRunStatus(truncate(r.localStatus(), STATUS_MAX));
            applyGdriveStatus(s, r);
            settingRepo.save(s);
            return ExportScheduleDto.RunNowResponse.builder()
                    // 既有三欄語意不變：一律指 xlsx 那一份
                    .path(r.xlsxFile() == null ? null : r.xlsxFile().toString())
                    .sizeBytes(r.xlsxFile() == null ? 0 : (int) Files.size(r.xlsxFile()))
                    .gdrivePath(r.xlsxGdrivePath())
                    .gdriveStatus(r.gdriveStatus())
                    .jsonPath(r.jsonFile() == null ? null : r.jsonFile().toString())
                    .jsonSizeBytes(r.jsonFile() == null ? 0 : (int) Files.size(r.jsonFile()))
                    .jsonGdrivePath(r.jsonGdrivePath())
                    .build();
        } catch (IOException | RuntimeException e) {
            s.setOwnerUserId(ownerId);
            s.setLastRunAt(LocalDateTime.now(TW_ZONE));
            s.setLastRunStatus(truncate("失敗：" + e.getMessage(), STATUS_MAX));
            syncGdrive(s, null); // 本機失敗＝完全不上傳，但已啟用時仍須寫狀態欄說明原因
            settingRepo.save(s);
            throw new RuntimeException("立即匯出失敗：" + e.getMessage(), e);
        }
    }

    /**
     * 唯讀列出基底（家目錄）下指定相對子路徑的「子目錄」清單（Requirement 34 增修）。
     * 供前端檔案總管式樹狀選擇器逐層懶載入；僅列目錄名稱、隱藏 dotfiles、依名稱排序，
     * 不讀檔案內容、不變更檔案系統。以 normalize {@code startsWith(base)} 驗證防跳脫。
     */
    public ExportScheduleDto.BrowseResponse browse(String subpath) {
        requireOwnerId(); // 需登入
        String sub = subpath == null ? "" : subpath.trim();
        while (sub.startsWith("/")) sub = sub.substring(1);
        while (sub.endsWith("/")) sub = sub.substring(0, sub.length() - 1);

        Path base = Path.of(baseDir).toAbsolutePath().normalize();
        Path target = base.resolve(sub).normalize();
        if (!target.startsWith(base)) {
            throw new IllegalArgumentException("瀏覽路徑不可跳脫基底目錄：" + subpath);
        }

        final String parent = sub;
        List<ExportScheduleDto.DirEntry> dirs = new ArrayList<>();
        if (Files.isDirectory(target)) {
            try (var stream = Files.list(target)) {
                stream.filter(Files::isDirectory)
                        .filter(p -> !p.getFileName().toString().startsWith(".")) // 隱藏 dotfiles
                        .sorted(Comparator.comparing((Path p) -> p.getFileName().toString().toLowerCase()))
                        .forEach(p -> {
                            String name = p.getFileName().toString();
                            String childPath = parent.isEmpty() ? name : parent + "/" + name;
                            dirs.add(ExportScheduleDto.DirEntry.builder().name(name).path(childPath).build());
                        });
            } catch (IOException e) {
                throw new RuntimeException("讀取目錄失敗：" + e.getMessage(), e);
            }
        }
        return ExportScheduleDto.BrowseResponse.builder()
                .baseDir(baseDir)
                .subpath(sub)
                .absolutePath(target.toString())
                .directories(dirs)
                .build();
    }

    /**
     * 唯讀列出 Google Drive remote 下指定相對子路徑的「子目錄」清單（Requirement 50 / Task 241）。
     *
     * <p>與上方 {@link #browse} <b>並列為兩支方法而非加參數</b>：語意不同（一個列本機基底下子目錄、
     * 一個列 Drive remote 下子目錄）。但<b>「Drive 目錄列舉」全庫只准有這一支</b>——本機列舉目前已經是
     * 分裂的（九個有資料夾選擇器的頁面中，八頁走本方法上面那支，交易日曆自成一份
     * {@code /api/trading-calendar-export/browse}），本任務不修那個既有分裂，但不得把它複製到 Drive 這一側：
     * 日後其餘頁面接 Drive 一律沿用本方法，含交易日曆頁。
     *
     * <p>回傳形狀與本機 {@code browse} 完全相同，前端才能沿用同一個 el-tree 元件與同一組欄位
     * （{@code baseDir} 放 {@code <remote>:}、{@code absolutePath} 放 {@code <remote>:<subpath>}）。
     */
    public ExportScheduleDto.BrowseResponse browseGdrive(String subpath) {
        requireOwnerId(); // 需登入
        String sub = gdrive.normalizeBrowseSubpath(subpath);

        // 唯讀：只 lsjson --dirs-only，不建立／不刪除／不修改 Drive 內容。
        // remote 未設定／授權失效時 RcloneClient 會擲 RcloneUnavailableException，
        // 由 controller 轉成可讀訊息——刻意不吞成空清單，空樹會被誤讀為「Drive 裡沒有資料夾」。
        List<String> names = gdrive.listDirsSorted(sub);

        final String parent = sub;
        // 過濾 dotfiles 與排序已由 GdriveOutputSupport.listDirsSorted 處理
        List<ExportScheduleDto.DirEntry> dirs = names.stream()
                .map(name -> ExportScheduleDto.DirEntry.builder()
                        .name(name)
                        .path(parent.isEmpty() ? name : parent + "/" + name)
                        .build())
                .toList();

        return ExportScheduleDto.BrowseResponse.builder()
                .baseDir(gdrive.remoteName() + ":")
                .subpath(sub)
                .absolutePath(gdrive.remoteName() + ":" + sub)
                .directories(dirs)
                .build();
    }

    // ===== 背景排程 =====

    /** 每分鐘檢查各使用者設定，命中執行時間且當日未跑者即產檔。 */
    @Scheduled(cron = "0 * * * * *", zone = "Asia/Taipei")
    public void tick() {
        if (!ticking.compareAndSet(false, true)) {
            log.debug("上一輪排程匯出尚未結束，跳過本次 tick");
            return;
        }
        try {
            runDueExports();
        } catch (RuntimeException e) {
            log.warn("排程匯出 tick 發生例外：{}", e.getMessage(), e);
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
            log.warn("排程匯出開機自癒失敗：{}", e.getMessage(), e);
        }
    }

    /**
     * 掃所有啟用中的設定，對「今日尚未執行且排程時間已到」者產檔。
     *
     * <p>用 {@code now >= 排程時間}（而非「分鐘精確相等」）＋ {@code lastRunDate} 當日 guard：任何被排程執行緒
     * 延遲／跳過的分鐘（Spring 預設排程池只有 1 條執行緒、與其他 @Scheduled 共用，可能被長工作卡住跨越分鐘），
     * 都會在後續 tick 自動補跑，直到當日成功並把 lastRunDate 設為今日為止，避免整日靜默漏跑。
     */
    private void runDueExports() {
        LocalDate today = LocalDate.now(TW_ZONE);
        LocalTime now = LocalTime.now(TW_ZONE);
        for (ExportScheduleSetting s : settingRepo.findAll()) {
            if (!Boolean.TRUE.equals(s.getEnabled())) continue;
            if (today.equals(s.getLastRunDate())) continue;
            if (!now.isBefore(LocalTime.of(s.getRunHour(), s.getRunMinute()))) {
                runScheduled(s, today);
            }
        }
    }

    /** 背景：對指定 owner 產檔並更新 guard／狀態。單一使用者失敗只記錄、不影響其他人。 */
    private void runScheduled(ExportScheduleSetting s, LocalDate today) {
        try {
            var r = writeDual(s, s.getOwnerUserId(),
                    excelExportService.liveAssetsDocForOwner(s.getOwnerUserId()),
                    normalizeSubpath(s.getOutputSubpath()));
            s.setLastRunStatus(truncate(r.localStatus(), STATUS_MAX));
            applyGdriveStatus(s, r);
            log.info("排程匯出 owner={} → {}", s.getOwnerUserId(), r.localStatus());
        } catch (Exception e) {
            s.setLastRunStatus(truncate("失敗：" + e.getMessage(), STATUS_MAX));
            log.warn("排程匯出失敗 owner={}：{}", s.getOwnerUserId(), e.getMessage(), e);
            syncGdrive(s, null); // 本機失敗＝不上傳；已啟用時仍寫狀態欄，否則會停在上一次的成功
        } finally {
            // 成功或失敗都設 guard，避免命中分鐘後每 poll 重試整天。
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

    private static String normalizeSubpath(String subpath) {
        String sub = subpath == null ? "" : subpath.trim();
        if (sub.isEmpty()) sub = "input";
        return sub;
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

    /**
     * 一次查詢的 doc → render 兩種格式 → 寫兩份檔（Requirement 55 / Task 271）。
     *
     * <p>主檔名含 ownerId，避免多使用者同 subpath 時同名互相覆蓋；<b>不含副檔名</b>，
     * 由 {@code DualFormatExportWriter} 各自加上 {@code .xlsx}／{@code .json}。
     * <b>兩支 render 各自 try/catch、失敗的那一份傳 null</b>：一份失敗不得中斷另一份。
     * {@code resolveDir} 不可省略——它擋的是子路徑跳脫基底，是另一道防線。
     */
    private com.steven.assets.service.export.DualFormatExportWriter.DualResult writeDual(
            ExportScheduleSetting s, Long ownerId,
            com.steven.assets.service.export.ExportDoc doc, String subpath) throws IOException {
        byte[] xlsx = null;
        byte[] json = null;
        try {
            xlsx = excelDocRenderer.render(doc);
        } catch (Exception e) {
            log.warn("資產總覽 xlsx render 失敗 owner={}：{}", ownerId, e.getMessage(), e);
        }
        try {
            json = jsonDocRenderer.render(doc);
        } catch (Exception e) {
            log.warn("資產總覽 json render 失敗 owner={}：{}", ownerId, e.getMessage(), e);
        }
        String baseName = "資產總覽_" + ownerId + "_" + LocalDate.now(TW_ZONE).format(FILE_DATE);
        return dualWriter.write(ownerId, resolveDir(subpath), baseName, json, xlsx,
                s.isGdriveEnabled(), s.getGdriveSubpath());
    }

    /** 寫回 Drive 狀態欄。未啟用時 {@code gdriveStatus} 為 null，此時兩欄一律不碰（沿用既有語意）。 */
    private void applyGdriveStatus(ExportScheduleSetting s,
                                   com.steven.assets.service.export.DualFormatExportWriter.DualResult r) {
        if (r.gdriveStatus() == null) return;
        s.setGdriveLastRunAt(LocalDateTime.now(TW_ZONE));
        s.setGdriveLastStatus(truncate(r.gdriveStatus(), GDRIVE_STATUS_MAX));
    }

    /**
     * 本機檔寫成功後的 Drive 同步（best-effort）。<b>順序不可顛倒</b>：本機那一份是既有的留存機制，
     * 必須先確定它寫成功才上傳；本機失敗時傳 {@code null} ——完全不上傳，絕不上傳前一次的舊檔。
     *
     * <p>不擲例外、不 rollback 本機檔、不改既有 {@code lastRunStatus}——「本機成功、Drive 失敗」是正常
     * 且必須可分辨的狀態，共用一欄會讓本機明明寫成功卻顯示失敗。owner 權限在 {@code syncQuietly} 內
     * <b>每一輪重驗</b>（背景排程沒有 request context，{@code PUT} 當下的檢查在此不適用）。
     *
     * @return 未啟用時回 {@code null}（不碰狀態欄）；否則為本輪結果，供 run-now 回報落點
     */
    private GdriveOutputSupport.SyncResult syncGdrive(ExportScheduleSetting s, Path localFile) {
        if (!s.isGdriveEnabled()) return null;
        GdriveOutputSupport.SyncResult r =
                gdrive.syncQuietly(s.getOwnerUserId(), s.getGdriveSubpath(), localFile);
        s.setGdriveLastRunAt(LocalDateTime.now(TW_ZONE));
        s.setGdriveLastStatus(truncate(r.status(), GDRIVE_STATUS_MAX));
        return r;
    }

    private ExportScheduleDto.SettingResponse toResponse(ExportScheduleSetting s) {
        return toResponse(s, null);   // 讀取路徑不做自檢：自檢只在「使用者這次把開關打開」時才有意義
    }

    /**
     * @param gdriveSelfCheckWarning 本次啟用 Drive 時的自檢警告（Task 247.3.5）；正常時為 {@code null}。
     *                               <b>不入庫</b>——尤其不得寫進 {@code gdriveLastStatus}，那一欄是「上次上傳」
     */
    private ExportScheduleDto.SettingResponse toResponse(ExportScheduleSetting s, String gdriveSelfCheckWarning) {
        return ExportScheduleDto.SettingResponse.builder()
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
