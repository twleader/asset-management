package com.steven.assets.service;

import com.steven.assets.dto.IndexExportDto;
import com.steven.assets.model.IndexExportSchedule;
import com.steven.assets.repository.IndexExportScheduleRepository;
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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 股市大盤指數日線每日排程自動匯出（Requirement 45 / Task 216）。
 *
 * <p>每個使用者可各自設定啟用開關、每日執行時分、匯出的指數、輸出相對子路徑與匯出範圍。因 {@code @Scheduled}
 * 的 cron 於啟動期固定、無法吃 DB 可調時間，改採「每分鐘 poll ＋ 當日 guard ＋ 開機自癒補跑」
 * （比照 {@link CommodityExportScheduleService}／{@link ExchangeRateExportScheduleService}）。
 *
 * <p><b>租戶隔離</b>：GET/PUT/run-now 走 HTTP（BFF→business），由 {@code TenantFilterAspect} 自動 owner-scoped 到本人；
 * 背景 poll 無 request context → {@code ownerFilter} 不啟用，{@code findAll()} 讀全部 owner 列。
 * 但<b>產檔本身不需縮 owner</b>：兩張指數日線表為全域公開行情（無 owner 欄位、無 {@code @Filter}），
 * 人人看到的大盤相同，故直接呼叫 {@link ExcelExportService#exportIndexDaily} 即可
 * （同油價金價／匯率；與 per-user 的已實現損益相反）。
 *
 * <p><b>滾動區間</b>：{@code rangeMonths} 為 null 時匯出全部十年，否則以「執行當日往前推 N 個月」計算起訖，
 * 使每日留存的檔案跟著時間滾動，而非固定區間。
 *
 * <p>路徑安全：使用者只設定「相對子路徑」，實際寫入 = 容器基底 {@code EXPORT_OUTPUT_DIR} resolve 子路徑，
 * 並驗證 normalize 後仍在基底內（拒 {@code ..}／絕對路徑跳脫）。
 *
 * <p>資料夾瀏覽不在此服務：沿用 Requirement 34 既有的 {@code GET /api/export-schedule/browse}，
 * 避免同義能力在 business 端出現第六份實作。
 */
@Service
@Slf4j
public class IndexExportScheduleService {

    private static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 匯出範圍上限（月）：十年，與指數日線的保留視窗一致。 */
    private static final int MAX_RANGE_MONTHS = 120;

    private static final String DEFAULT_MARKET = "TWSE";

    private final IndexExportScheduleRepository settingRepo;
    private final ExcelExportService excelExportService;
    private final ObjectProvider<CurrentUserContext> currentUserProvider;
    private final GdriveOutputSupport gdrive;

    /** 容器內基底輸出目錄，經 docker volume 對映到 host（見 docker-compose.yml）。 */
    private final String baseDir;

    /** 避免每分鐘 poll 在上一輪尚未跑完時重入。 */
    private final AtomicBoolean ticking = new AtomicBoolean(false);

    public IndexExportScheduleService(IndexExportScheduleRepository settingRepo,
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

    /** 取當前使用者排程設定；無則回預設值（不寫入 DB）。 */
    public IndexExportDto.SettingResponse getForCurrentUser() {
        Long ownerId = requireOwnerId();
        IndexExportSchedule s = settingRepo.findByOwnerUserId(ownerId).orElseGet(() ->
                IndexExportSchedule.builder().ownerUserId(ownerId).build());
        return toResponse(s);
    }

    /** upsert 當前使用者設定。 */
    public IndexExportDto.SettingResponse updateForCurrentUser(IndexExportDto.SettingRequest req) {
        Long ownerId = requireOwnerId();
        int hour = req.runHour() == null ? 8 : req.runHour();
        int minute = req.runMinute() == null ? 0 : req.runMinute();
        if (hour < 0 || hour > 23) throw new IllegalArgumentException("執行時(hour)必須介於 0～23");
        if (minute < 0 || minute > 59) throw new IllegalArgumentException("執行分(minute)必須介於 0～59");
        String market = normalizeMarket(req.market());
        Integer rangeMonths = req.rangeMonths();
        if (rangeMonths != null && (rangeMonths < 1 || rangeMonths > MAX_RANGE_MONTHS)) {
            throw new IllegalArgumentException("匯出範圍(月)必須介於 1～" + MAX_RANGE_MONTHS + "，或留空代表全部十年");
        }
        String subpath = normalizeSubpath(req.outputSubpath());
        resolveDir(subpath); // 驗證不跳脫基底（丟出即擋下）

        IndexExportSchedule s = settingRepo.findByOwnerUserId(ownerId).orElseGet(() ->
                IndexExportSchedule.builder().ownerUserId(ownerId).build());
        // Drive 兩欄一律走共用元件：null 解析（未送出＝不變更）、驗證、啟用時必填、只有主要管理者能啟用（403）。
        // 本機路徑與排程時間刻意維持所有使用者皆可設定——只有 Drive 這一項會把資料送出本機。
        GdriveOutputSupport.DriveSettings drive = gdrive.resolveUpdate(
                ownerId, req.gdriveEnabled(), req.gdriveSubpath(), s.isGdriveEnabled(), s.getGdriveSubpath());

        s.setOwnerUserId(ownerId);
        s.setEnabled(Boolean.TRUE.equals(req.enabled()));
        s.setRunHour(hour);
        s.setRunMinute(minute);
        s.setMarket(market);
        s.setOutputSubpath(subpath);
        s.setRangeMonths(rangeMonths);
        s.setGdriveEnabled(drive.enabled());
        s.setGdriveSubpath(drive.subpath());
        // 刻意不碰 gdriveLastRunAt／gdriveLastStatus：那是執行結果，不是使用者設定。
        s.setUpdatedAt(LocalDateTime.now(TW_ZONE));
        return toResponse(settingRepo.save(s));
    }

    /** 立即產檔寫入當前使用者設定的目錄（供驗證路徑正確）。不動當日 guard。 */
    public IndexExportDto.RunNowResponse runNowForCurrentUser() {
        Long ownerId = requireOwnerId();
        IndexExportSchedule s = settingRepo.findByOwnerUserId(ownerId).orElseGet(() ->
                IndexExportSchedule.builder().ownerUserId(ownerId).build());
        try {
            Path file = export(s, ownerId);
            long size = Files.size(file);
            s.setOwnerUserId(ownerId);
            s.setLastRunAt(LocalDateTime.now(TW_ZONE));
            s.setLastRunStatus("成功：" + file);
            // 本機寫成功後才上傳；run-now 的用途就是驗證落點正確，故它也要上傳並回報。
            GdriveOutputSupport.SyncResult drive = syncGdrive(s, file);
            settingRepo.save(s);
            return IndexExportDto.RunNowResponse.builder()
                    .path(file.toString())
                    .sizeBytes(size)
                    .gdrivePath(drive == null ? null : drive.path())
                    .gdriveStatus(drive == null ? null : drive.status())
                    .build();
        } catch (IOException | RuntimeException e) {
            s.setOwnerUserId(ownerId);
            s.setLastRunAt(LocalDateTime.now(TW_ZONE));
            s.setLastRunStatus("失敗：" + e.getMessage());
            syncGdrive(s, null); // 本機失敗＝完全不上傳，但已啟用時仍須寫狀態欄說明原因
            settingRepo.save(s);
            throw new RuntimeException("立即匯出失敗：" + e.getMessage(), e);
        }
    }

    // ===== 背景排程 =====

    /** 每分鐘檢查各使用者設定，命中執行時間且當日未跑者即產檔。 */
    @Scheduled(cron = "0 * * * * *", zone = "Asia/Taipei")
    public void tick() {
        if (!ticking.compareAndSet(false, true)) {
            log.debug("上一輪大盤指數排程匯出尚未結束，跳過本次 tick");
            return;
        }
        try {
            runDueExports();
        } catch (RuntimeException e) {
            log.warn("大盤指數排程匯出 tick 發生例外：{}", e.getMessage(), e);
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
            log.warn("大盤指數排程匯出開機自癒失敗：{}", e.getMessage(), e);
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
        for (IndexExportSchedule s : settingRepo.findAll()) {
            if (!Boolean.TRUE.equals(s.getEnabled())) continue;
            if (today.equals(s.getLastRunDate())) continue;
            if (!now.isBefore(LocalTime.of(s.getRunHour(), s.getRunMinute()))) {
                runScheduled(s, today);
            }
        }
    }

    /** 背景：對指定設定產檔並更新 guard／狀態。單一使用者失敗只記錄、不影響其他人。 */
    private void runScheduled(IndexExportSchedule s, LocalDate today) {
        try {
            Path file = export(s, s.getOwnerUserId());
            s.setLastRunStatus("成功：" + file);
            log.info("大盤指數排程匯出成功 owner={} market={} → {}", s.getOwnerUserId(), s.getMarket(), file);
            syncGdrive(s, file);
        } catch (Exception e) {
            s.setLastRunStatus("失敗：" + e.getMessage());
            log.warn("大盤指數排程匯出失敗 owner={}：{}", s.getOwnerUserId(), e.getMessage(), e);
            syncGdrive(s, null); // 本機失敗＝不上傳；已啟用時仍寫狀態欄，否則會停在上一次的成功
        } finally {
            // 成功或失敗都設 guard，避免命中分鐘後每 poll 重試整天。
            s.setLastRunDate(today);
            s.setLastRunAt(LocalDateTime.now(TW_ZONE));
            settingRepo.save(s);
        }
    }

    // ===== 輔助 =====

    /**
     * 依設定的指數與滾動區間產檔並寫入目錄，回傳實際落點。
     * 與手動匯出走同一支 {@code exportIndexDaily}，確保兩種途徑內容一致。
     */
    private Path export(IndexExportSchedule s, Long ownerId) throws IOException {
        LocalDate end = LocalDate.now(TW_ZONE);
        Integer months = s.getRangeMonths();
        LocalDate start = months == null ? end.minusYears(10) : end.minusMonths(months);
        String market = normalizeMarket(s.getMarket());
        byte[] data = excelExportService.exportIndexDaily(market, start, end);
        String filename = ExcelExportService.indexLabel(market) + "_" + ownerId + "_" + end.format(FILE_DATE) + ".xlsx";
        return writeAtomically(normalizeSubpath(s.getOutputSubpath()), filename, data);
    }

    private Long requireOwnerId() {
        CurrentUserContext ctx = currentUserProvider.getObject();
        if (!ctx.hasUser()) {
            throw new UnauthenticatedException("未識別使用者，無法存取排程設定");
        }
        return ctx.getEffectiveUserId();
    }

    /**
     * 指數代碼正規化＋白名單驗證。清單取自 {@link MacroHistoryService#DAILY_INDEX_CODES}（單一來源，
     * 與頁面下拉一致），未知代碼直接擋下——該值會流入查詢與產出檔名（資安 Requirement 29）。
     * null／空白視為未設定，回預設 TWSE（相容從未儲存過設定的列）。
     */
    private static String normalizeMarket(String market) {
        String m = market == null ? "" : market.trim().toUpperCase();
        if (m.isEmpty()) return DEFAULT_MARKET;
        if (!MacroHistoryService.DAILY_INDEX_CODES.contains(m)) {
            throw new IllegalArgumentException("未知指數代碼: " + market);
        }
        return m;
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
     * 先寫 {@code .tmp} 再 atomic move（比照 Requirement 37）：避免覆寫既有檔時中途失敗留下半截殘檔，
     * 讓使用者永遠讀到完整的前一版或完整的新版。
     */
    private Path writeAtomically(String subpath, String filename, byte[] data) throws IOException {
        Path dir = resolveDir(subpath);
        Files.createDirectories(dir);
        Path file = dir.resolve(filename);
        Path tmp = dir.resolve(filename + ".tmp");
        Files.write(tmp, data);
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return file;
    }

    private IndexExportDto.SettingResponse toResponse(IndexExportSchedule s) {
        String market = normalizeMarket(s.getMarket());
        return IndexExportDto.SettingResponse.builder()
                .enabled(Boolean.TRUE.equals(s.getEnabled()))
                .runHour(s.getRunHour())
                .runMinute(s.getRunMinute())
                .market(market)
                .marketLabel(ExcelExportService.indexLabel(market))
                .outputSubpath(s.getOutputSubpath())
                .rangeMonths(s.getRangeMonths())
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
                .build();
    }

    /**
     * 本機檔寫成功後的 Drive 同步（best-effort）。<b>順序不可顛倒</b>：本機那一份是既有的留存機制，
     * 必須先確定它寫成功才上傳；本機失敗時傳 {@code null} ——完全不上傳，絕不上傳前一次的舊檔。
     *
     * <p>不擲例外、不 rollback 本機檔、不改既有 {@code lastRunStatus}——「本機成功、Drive 失敗」是正常
     * 且必須可分辨的狀態。owner 權限在 {@code syncQuietly} 內<b>每一輪重驗</b>（背景排程沒有 request
     * context，{@code PUT} 當下的檢查在此不適用）。
     *
     * @return 未啟用時回 {@code null}（不碰狀態欄）；否則為本輪結果，供 run-now 回報落點
     */
    private GdriveOutputSupport.SyncResult syncGdrive(IndexExportSchedule s, Path localFile) {
        if (!s.isGdriveEnabled()) return null;
        GdriveOutputSupport.SyncResult r =
                gdrive.syncQuietly(s.getOwnerUserId(), s.getGdriveSubpath(), localFile);
        s.setGdriveLastRunAt(LocalDateTime.now(TW_ZONE));
        s.setGdriveLastStatus(r.status());
        return r;
    }
}
