package com.steven.assets.service;

import com.steven.assets.dto.AssetTransactionExportDto;
import com.steven.assets.model.AssetTransactionExportSchedule;
import com.steven.assets.repository.AssetTransactionExportScheduleRepository;
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
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 資產交易紀錄每日排程自動匯出（Requirement 49 / Task 238；Task 255 起每人可多筆）。
 *
 * <p>整套形狀比照 Requirement 39（已實現損益排程匯出，Task 196）：因 {@code @Scheduled} 的 cron 於啟動期固定、
 * 無法吃 DB 可調時間，改採「每分鐘 poll ＋ 當日 guard ＋ 開機自癒補跑」。
 *
 * <p><b>Task 255：每位使用者可有多列</b>，一列＝一筆每日排程（各自的名稱／時間／輸出目錄／Drive 設定／
 * 執行狀態）。背景 tick 的判斷單位因此從「每使用者一列」變成「每筆排程一列」，但語意不變——
 * 同一 owner 命中多列時<b>每列各自呼叫</b> {@link ExcelExportService#exportAssetTransactionsForOwner(Long)}，
 * 不得為了省一次查詢而合併：租戶隔離的正確性優先於效能，且合併後單列失敗會連坐其他列。
 *
 * <p>租戶隔離：列表／新增／修改／刪除／run-now 走 HTTP（BFF→business），由 {@code TenantFilterAspect}
 * 自動 owner-scoped 到本人；<b>by-id 操作另以 {@code findByIdAndOwnerUserId} 把關</b>（Hibernate
 * {@code @Filter} 不套用於 {@code findById}）。背景 poll 無 request context → filter 不啟用，
 * {@code findAll()} 讀全部 owner 的全部列，產檔時才對該列 owner 手動 {@code enableFilter}。
 * （交易紀錄為 per-user 資料，若不逐列縮 owner 會把所有人的交易寫進每個人的檔案。）
 *
 * <p>路徑安全：使用者只設定「相對子路徑」，實際寫入 = 容器基底 {@code EXPORT_OUTPUT_DIR} resolve 子路徑，
 * 並驗證 normalize 後仍在基底內（拒 {@code ..}／絕對路徑跳脫）；排程名稱會進檔名，同樣以白名單驗證。
 *
 * <p>資料夾瀏覽不在此服務：沿用 Requirement 34 既有的 {@code GET /api/export-schedule/browse}。
 */
@Service
@Slf4j
public class AssetTransactionExportScheduleService {

    private static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 每人排程數上限：每分鐘 tick 會逐列處理，無上限＝讓單一使用者無限放大背景工作量。
     *
     * <p><b>軟上限、防呆而非防惡意</b>——先 count 再 save 非原子，並發送出多個 POST 時可能短暫超過。
     * 刻意不加 DB 層約束（那需要計數觸發器或部分索引，複雜度遠高於它擋下的風險）。
     */
    private static final int MAX_SCHEDULES_PER_USER = 10;

    /** 名稱會直接進檔名，故白名單驗證：中文／英數／底線／連字號／空白，長度 1..20。 */
    private static final Pattern NAME_OK = Pattern.compile("^[\\p{IsHan}A-Za-z0-9_\\- ]{1,20}$");

    private final AssetTransactionExportScheduleRepository settingRepo;
    private final ExcelExportService excelExportService;
    private final ObjectProvider<CurrentUserContext> currentUserProvider;
    private final GdriveOutputSupport gdrive;

    /** 容器內基底輸出目錄，經 docker volume 對映到 host（見 docker-compose.yml）。 */
    private final String baseDir;

    /** 避免每分鐘 poll 在上一輪尚未跑完時重入。 */
    private final AtomicBoolean ticking = new AtomicBoolean(false);

    public AssetTransactionExportScheduleService(AssetTransactionExportScheduleRepository settingRepo,
                                                 ExcelExportService excelExportService,
                                                 ObjectProvider<CurrentUserContext> currentUserProvider,
                                                 GdriveOutputSupport gdrive,
                                                 @Value("${EXPORT_OUTPUT_DIR:/home/steven}") String baseDir) {
        this.settingRepo = settingRepo;
        this.excelExportService = excelExportService;
        this.currentUserProvider = currentUserProvider;
        this.gdrive = gdrive;
        this.baseDir = baseDir;
    }

    // ===== HTTP（owner-scoped）=====

    /** 列出當前使用者的全部排程；沒有任何列時回空清單（<b>不寫入 DB</b>，讀取端點不得有副作用）。 */
    public AssetTransactionExportDto.SchedulesResponse listForCurrentUser() {
        return toResponse(requireOwnerId(), null);
    }

    /** 新增一筆排程，回傳變更後的完整清單。 */
    public AssetTransactionExportDto.SchedulesResponse createForCurrentUser(
            AssetTransactionExportDto.SettingRequest req) {
        Long ownerId = requireOwnerId();
        if (settingRepo.countByOwnerUserId(ownerId) >= MAX_SCHEDULES_PER_USER) {
            throw new IllegalArgumentException(
                    "排程數量已達上限 " + MAX_SCHEDULES_PER_USER + " 筆，請先刪除不用的排程");
        }
        AssetTransactionExportSchedule s = AssetTransactionExportSchedule.builder()
                .ownerUserId(ownerId).build();
        String warning = applyRequest(ownerId, s, req);
        settingRepo.save(s);
        return toResponse(ownerId, warning);
    }

    /** 修改指定一筆；查無或非本人一律 404（不區分兩者，避免洩漏他人資源是否存在）。 */
    public AssetTransactionExportDto.SchedulesResponse updateForCurrentUser(
            Long id, AssetTransactionExportDto.SettingRequest req) {
        Long ownerId = requireOwnerId();
        AssetTransactionExportSchedule s = requireOwned(id, ownerId);
        String warning = applyRequest(ownerId, s, req);
        settingRepo.save(s);
        return toResponse(ownerId, warning);
    }

    /** 刪除指定一筆。用 {@code delete(entity)} 而非 {@code deleteById(id)}——後者不吃 owner 過濾。 */
    public AssetTransactionExportDto.SchedulesResponse deleteForCurrentUser(Long id) {
        Long ownerId = requireOwnerId();
        settingRepo.delete(requireOwned(id, ownerId));
        return toResponse(ownerId, null);
    }

    /** 以指定那一筆的設定立即產檔（供驗證落點）。不動該筆當日 guard，也不碰其他筆。 */
    public AssetTransactionExportDto.RunNowResponse runNowForCurrentUser(Long id) {
        Long ownerId = requireOwnerId();
        // 取列刻意放在 try 之外：查無要回 404，包進 try 會被下面的 catch 轉成 500。
        AssetTransactionExportSchedule s = requireOwned(id, ownerId);
        try {
            // HTTP 情境：exportAssetTransactions() 由 TenantFilterAspect 自動 owner-scoped 到當前使用者。
            byte[] data = excelExportService.exportAssetTransactions();
            Path file = writeToDir(ownerId, s.getName(), s.getOutputSubpath(), data);
            s.setLastRunAt(LocalDateTime.now(TW_ZONE));
            s.setLastRunStatus("成功：" + file);
            // 本機寫成功後才上傳；run-now 的用途就是驗證落點正確，故它也要上傳並回報。
            GdriveOutputSupport.SyncResult drive = syncGdrive(s, file);
            settingRepo.save(s);
            return AssetTransactionExportDto.RunNowResponse.builder()
                    .path(file.toString())
                    .sizeBytes(data.length)
                    .gdrivePath(drive == null ? null : drive.path())
                    .gdriveStatus(drive == null ? null : drive.status())
                    .build();
        } catch (IOException | RuntimeException e) {
            s.setLastRunAt(LocalDateTime.now(TW_ZONE));
            s.setLastRunStatus("失敗：" + e.getMessage());
            syncGdrive(s, null); // 本機失敗＝完全不上傳，但已啟用時仍須寫狀態欄說明原因
            settingRepo.save(s);
            throw new RuntimeException("立即匯出失敗：" + e.getMessage(), e);
        }
    }

    // ===== 背景排程 =====

    /** 每分鐘檢查各使用者的每一筆排程，命中執行時間且當日未跑者即產檔。 */
    @Scheduled(cron = "0 * * * * *", zone = "Asia/Taipei")
    public void tick() {
        if (!ticking.compareAndSet(false, true)) {
            log.debug("上一輪交易紀錄排程匯出尚未結束，跳過本次 tick");
            return;
        }
        try {
            runDueExports();
        } catch (RuntimeException e) {
            log.warn("交易紀錄排程匯出 tick 發生例外：{}", e.getMessage(), e);
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
            log.warn("交易紀錄排程匯出開機自癒失敗：{}", e.getMessage(), e);
        }
    }

    /**
     * 掃所有啟用中的設定列，對「今日尚未執行且排程時間已到」者產檔。
     *
     * <p>用 {@code now >= 排程時間}（而非「分鐘精確相等」）＋ {@code lastRunDate} 當日 guard：任何被排程執行緒
     * 延遲／跳過的分鐘都會在後續 tick 自動補跑，直到當日成功並把 lastRunDate 設為今日為止。
     *
     * <p>Task 255：同一 owner 可能命中多列，逐列各自處理。
     */
    private void runDueExports() {
        LocalDate today = LocalDate.now(TW_ZONE);
        LocalTime now = LocalTime.now(TW_ZONE);
        for (AssetTransactionExportSchedule s : settingRepo.findAll()) {
            if (!Boolean.TRUE.equals(s.getEnabled())) continue;
            if (today.equals(s.getLastRunDate())) continue;
            if (!now.isBefore(LocalTime.of(s.getRunHour(), s.getRunMinute()))) {
                runScheduled(s, today);
            }
        }
    }

    /**
     * 背景：對指定列產檔並更新該列的 guard／狀態。
     *
     * <p>單列失敗只記錄，不影響同一使用者的其他列、也不影響其他使用者。
     */
    private void runScheduled(AssetTransactionExportSchedule s, LocalDate today) {
        try {
            byte[] data = excelExportService.exportAssetTransactionsForOwner(s.getOwnerUserId());
            Path file = writeToDir(s.getOwnerUserId(), s.getName(), s.getOutputSubpath(), data);
            s.setLastRunStatus("成功：" + file);
            log.info("交易紀錄排程匯出成功 owner={} schedule={} → {}（{} bytes）",
                    s.getOwnerUserId(), s.getId(), file, data.length);
            syncGdrive(s, file);
        } catch (Exception e) {
            s.setLastRunStatus("失敗：" + e.getMessage());
            log.warn("交易紀錄排程匯出失敗 owner={} schedule={}：{}",
                    s.getOwnerUserId(), s.getId(), e.getMessage(), e);
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

    /**
     * by-id 取列並驗歸屬。<b>不可</b>改成先 {@code findById} 再比對 owner 後回 403——
     * 那會洩漏他人排程是否存在（既有 {@code TenantAccessException} 同樣被對映成 404，正是同一考量）。
     */
    private AssetTransactionExportSchedule requireOwned(Long id, Long ownerId) {
        return settingRepo.findByIdAndOwnerUserId(id, ownerId)
                .orElseThrow(() -> new NoSuchElementException("找不到指定的排程設定"));
    }

    /**
     * 把請求套進 entity（新增／修改共用同一段驗證，不得各寫一份）。
     *
     * @return 本次啟用 Drive 時的自檢警告；正常為 {@code null}
     */
    private String applyRequest(Long ownerId, AssetTransactionExportSchedule s,
                                AssetTransactionExportDto.SettingRequest req) {
        int hour = req.runHour() == null ? 8 : req.runHour();
        int minute = req.runMinute() == null ? 0 : req.runMinute();
        if (hour < 0 || hour > 23) throw new IllegalArgumentException("執行時(hour)必須介於 0～23");
        if (minute < 0 || minute > 59) throw new IllegalArgumentException("執行分(minute)必須介於 0～59");
        String subpath = normalizeSubpath(req.outputSubpath());
        resolveDir(subpath); // 驗證不跳脫基底（丟出即擋下）
        String name = normalizeName(req.name());

        // Drive 兩欄一律走共用元件：null 解析（未送出＝不變更）、驗證、啟用時必填、只有主要管理者能啟用（403）。
        // 本機路徑與排程時間刻意維持所有使用者皆可設定——只有 Drive 這一項會把資料送出本機。
        GdriveOutputSupport.DriveSettings drive = gdrive.resolveUpdate(
                ownerId, req.gdriveEnabled(), req.gdriveSubpath(), s.isGdriveEnabled(), s.getGdriveSubpath());

        s.setOwnerUserId(ownerId);
        s.setName(name);
        s.setEnabled(Boolean.TRUE.equals(req.enabled()));
        s.setRunHour(hour);
        s.setRunMinute(minute);
        s.setOutputSubpath(subpath);
        s.setGdriveEnabled(drive.enabled());
        s.setGdriveSubpath(drive.subpath());
        // 刻意不碰 lastRun*／gdriveLastRunAt／gdriveLastStatus：那是執行結果，不是使用者設定。
        // 啟用當下的自檢結果同樣不寫那兩欄（Task 247.3.4），只走當次回應。
        s.setUpdatedAt(LocalDateTime.now(TW_ZONE));
        return drive.selfCheckWarning();
    }

    private static String normalizeSubpath(String subpath) {
        String sub = subpath == null ? "" : subpath.trim();
        if (sub.isEmpty()) sub = "input";
        return sub;
    }

    /** 名稱正規化＋白名單驗證：null／純空白 → null；否則 trim 後須符合 {@link #NAME_OK}。 */
    private static String normalizeName(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) return null;
        if (!NAME_OK.matcher(n).matches()) {
            throw new IllegalArgumentException("排程名稱只能是中英數、底線、連字號與空白，且不超過 20 字");
        }
        return n;
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
     * 建立目錄並寫入 xlsx，回傳實際檔案路徑。
     *
     * <p>檔名含 ownerId，避免多使用者同 subpath 時同名互相覆蓋；{@code name} 非空時再加一段，
     * 讓同一人的多筆排程可各留一份。<b>{@code name} 為空時檔名與 Task 238 逐字元相同</b>
     * （既有排程升級後落點與檔名不變，餵給下游程式的輸入檔不會斷）。
     */
    private Path writeToDir(Long ownerId, String name, String subpath, byte[] data) throws IOException {
        Path dir = resolveDir(normalizeSubpath(subpath));
        Files.createDirectories(dir);
        String date = LocalDate.now(TW_ZONE).format(FILE_DATE);
        String suffix = (name == null || name.isBlank()) ? "" : "_" + name.trim();
        Path file = dir.resolve("交易紀錄_" + ownerId + suffix + "_" + date + ".xlsx");
        // 與 subpath 同等級的執行時重驗：DB 值可能被繞過 API 以 psql 直改（既有 toResponse／
        // GdriveOutputSupport.syncQuietly 都是這個假設），name 進了檔名就等於進了路徑。
        if (!file.normalize().startsWith(dir)) {
            throw new IllegalArgumentException("排程名稱不合法，拒絕寫入：" + name);
        }
        Files.write(file, data);
        return file;
    }

    /**
     * 組清單回應。
     *
     * @param gdriveSelfCheckWarning 本次啟用 Drive 時的自檢警告（Task 247.3.5）；正常時為 {@code null}。
     *                               <b>不入庫</b>——尤其不得寫進 {@code gdriveLastStatus}，那一欄是「上次上傳」
     */
    private AssetTransactionExportDto.SchedulesResponse toResponse(Long ownerId, String gdriveSelfCheckWarning) {
        List<AssetTransactionExportDto.Item> items =
                settingRepo.findByOwnerUserIdOrderByRunHourAscRunMinuteAscIdAsc(ownerId).stream()
                        .map(AssetTransactionExportScheduleService::toItem)
                        .toList();
        return AssetTransactionExportDto.SchedulesResponse.builder()
                .baseDir(baseDir)
                .gdriveRemote(gdrive.remoteName())
                .schedules(items)
                .gdriveSelfCheckWarning(gdriveSelfCheckWarning)
                .build();
    }

    private static AssetTransactionExportDto.Item toItem(AssetTransactionExportSchedule s) {
        return AssetTransactionExportDto.Item.builder()
                .id(s.getId())
                .name(s.getName())
                .enabled(Boolean.TRUE.equals(s.getEnabled()))
                .runHour(s.getRunHour())
                .runMinute(s.getRunMinute())
                .outputSubpath(s.getOutputSubpath())
                .lastRunAt(s.getLastRunAt() == null ? null : s.getLastRunAt().format(TS_FMT))
                .lastRunStatus(s.getLastRunStatus())
                // 讀取一律不驗證 Drive 子路徑：DB 值可能被繞過 API 直改，若讀取也擲例外，設定頁會 500
                // 而使用者沒有任何入口能把它改回正常值——唯一的修正入口被自己鎖死。存檔時才驗。
                .gdriveEnabled(s.isGdriveEnabled())
                .gdriveSubpath(s.getGdriveSubpath())
                .gdriveLastRunAt(s.getGdriveLastRunAt() == null ? null : s.getGdriveLastRunAt().format(TS_FMT))
                .gdriveLastStatus(s.getGdriveLastStatus())
                .build();
    }

    /**
     * 本機檔寫成功後的 Drive 同步（best-effort）。<b>順序不可顛倒</b>：本機那一份是既有的留存機制，
     * 必須先確定它寫成功才上傳；本機失敗時傳 {@code null} ——完全不上傳，絕不上傳前一次的舊檔。
     *
     * <p>不擲例外、不 rollback 本機檔、不改既有 {@code lastRunStatus}——「本機成功、Drive 失敗」是正常
     * 且必須可分辨的狀態。owner 權限在 {@code syncQuietly} 內<b>每一輪重驗</b>（背景排程沒有 request
     * context，{@code PUT} 當下的檢查在此不適用），多列亦是逐列各自重驗。
     *
     * @return 未啟用時回 {@code null}（不碰狀態欄）；否則為本輪結果，供 run-now 回報落點
     */
    private GdriveOutputSupport.SyncResult syncGdrive(AssetTransactionExportSchedule s, Path localFile) {
        if (!s.isGdriveEnabled()) return null;
        GdriveOutputSupport.SyncResult r =
                gdrive.syncQuietly(s.getOwnerUserId(), s.getGdriveSubpath(), localFile);
        s.setGdriveLastRunAt(LocalDateTime.now(TW_ZONE));
        s.setGdriveLastStatus(r.status());
        return r;
    }
}
