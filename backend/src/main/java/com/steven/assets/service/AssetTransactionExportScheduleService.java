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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 資產交易紀錄每日排程自動匯出（Requirement 49 / Task 238）。
 *
 * <p>整套形狀比照 Requirement 39（已實現損益排程匯出，Task 196）：因 {@code @Scheduled} 的 cron 於啟動期固定、
 * 無法吃 DB 可調時間，改採「每分鐘 poll ＋ 當日 guard ＋ 開機自癒補跑」。
 *
 * <p>租戶隔離：GET/PUT/run-now 走 HTTP（BFF→business），由 {@code TenantFilterAspect} 自動 owner-scoped 到本人；
 * 背景 poll 無 request context → {@code ownerFilter} 不啟用，{@code findAll()} 讀全部 owner 列，
 * 產檔時才以 {@link ExcelExportService#exportAssetTransactionsForOwner(Long)} 對該列 owner 手動 {@code enableFilter}。
 * （交易紀錄為 per-user 資料，若不逐列縮 owner 會把所有人的交易寫進每個人的檔案。）
 *
 * <p>路徑安全：使用者只設定「相對子路徑」，實際寫入 = 容器基底 {@code EXPORT_OUTPUT_DIR} resolve 子路徑，
 * 並驗證 normalize 後仍在基底內（拒 {@code ..}／絕對路徑跳脫）。
 *
 * <p>資料夾瀏覽不在此服務：沿用 Requirement 34 既有的 {@code GET /api/export-schedule/browse}。
 */
@Service
@Slf4j
public class AssetTransactionExportScheduleService {

    private static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AssetTransactionExportScheduleRepository settingRepo;
    private final ExcelExportService excelExportService;
    private final ObjectProvider<CurrentUserContext> currentUserProvider;

    /** 容器內基底輸出目錄，經 docker volume 對映到 host（見 docker-compose.yml）。 */
    private final String baseDir;

    /** 避免每分鐘 poll 在上一輪尚未跑完時重入。 */
    private final AtomicBoolean ticking = new AtomicBoolean(false);

    public AssetTransactionExportScheduleService(AssetTransactionExportScheduleRepository settingRepo,
                                                 ExcelExportService excelExportService,
                                                 ObjectProvider<CurrentUserContext> currentUserProvider,
                                                 @Value("${EXPORT_OUTPUT_DIR:/home/steven}") String baseDir) {
        this.settingRepo = settingRepo;
        this.excelExportService = excelExportService;
        this.currentUserProvider = currentUserProvider;
        this.baseDir = baseDir;
    }

    // ===== HTTP（owner-scoped）=====

    /** 取當前使用者排程設定；無則回預設值（不寫入 DB）。 */
    public AssetTransactionExportDto.SettingResponse getForCurrentUser() {
        Long ownerId = requireOwnerId();
        AssetTransactionExportSchedule s = settingRepo.findByOwnerUserId(ownerId).orElseGet(() ->
                AssetTransactionExportSchedule.builder().ownerUserId(ownerId).build());
        return toResponse(s);
    }

    /** upsert 當前使用者設定。 */
    public AssetTransactionExportDto.SettingResponse updateForCurrentUser(
            AssetTransactionExportDto.SettingRequest req) {
        Long ownerId = requireOwnerId();
        int hour = req.runHour() == null ? 8 : req.runHour();
        int minute = req.runMinute() == null ? 0 : req.runMinute();
        if (hour < 0 || hour > 23) throw new IllegalArgumentException("執行時(hour)必須介於 0～23");
        if (minute < 0 || minute > 59) throw new IllegalArgumentException("執行分(minute)必須介於 0～59");
        String subpath = normalizeSubpath(req.outputSubpath());
        resolveDir(subpath); // 驗證不跳脫基底（丟出即擋下）

        AssetTransactionExportSchedule s = settingRepo.findByOwnerUserId(ownerId).orElseGet(() ->
                AssetTransactionExportSchedule.builder().ownerUserId(ownerId).build());
        s.setOwnerUserId(ownerId);
        s.setEnabled(Boolean.TRUE.equals(req.enabled()));
        s.setRunHour(hour);
        s.setRunMinute(minute);
        s.setOutputSubpath(subpath);
        s.setUpdatedAt(LocalDateTime.now(TW_ZONE));
        return toResponse(settingRepo.save(s));
    }

    /** 立即以當前使用者身分產檔寫入其設定目錄（供驗證）。不動當日 guard。 */
    public AssetTransactionExportDto.RunNowResponse runNowForCurrentUser() {
        Long ownerId = requireOwnerId();
        AssetTransactionExportSchedule s = settingRepo.findByOwnerUserId(ownerId).orElseGet(() ->
                AssetTransactionExportSchedule.builder().ownerUserId(ownerId).build());
        String subpath = normalizeSubpath(s.getOutputSubpath());
        try {
            // HTTP 情境：exportAssetTransactions() 由 TenantFilterAspect 自動 owner-scoped 到當前使用者。
            byte[] data = excelExportService.exportAssetTransactions();
            Path file = writeToDir(ownerId, subpath, data);
            s.setOwnerUserId(ownerId);
            s.setLastRunAt(LocalDateTime.now(TW_ZONE));
            s.setLastRunStatus("成功：" + file);
            settingRepo.save(s);
            return AssetTransactionExportDto.RunNowResponse.builder()
                    .path(file.toString())
                    .sizeBytes(data.length)
                    .build();
        } catch (IOException | RuntimeException e) {
            s.setOwnerUserId(ownerId);
            s.setLastRunAt(LocalDateTime.now(TW_ZONE));
            s.setLastRunStatus("失敗：" + e.getMessage());
            settingRepo.save(s);
            throw new RuntimeException("立即匯出失敗：" + e.getMessage(), e);
        }
    }

    // ===== 背景排程 =====

    /** 每分鐘檢查各使用者設定，命中執行時間且當日未跑者即產檔。 */
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
     * 掃所有啟用中的設定，對「今日尚未執行且排程時間已到」者產檔。
     *
     * <p>用 {@code now >= 排程時間}（而非「分鐘精確相等」）＋ {@code lastRunDate} 當日 guard：任何被排程執行緒
     * 延遲／跳過的分鐘都會在後續 tick 自動補跑，直到當日成功並把 lastRunDate 設為今日為止。
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

    /** 背景：對指定 owner 產檔並更新 guard／狀態。單一使用者失敗只記錄、不影響其他人。 */
    private void runScheduled(AssetTransactionExportSchedule s, LocalDate today) {
        try {
            byte[] data = excelExportService.exportAssetTransactionsForOwner(s.getOwnerUserId());
            Path file = writeToDir(s.getOwnerUserId(), normalizeSubpath(s.getOutputSubpath()), data);
            s.setLastRunStatus("成功：" + file);
            log.info("交易紀錄排程匯出成功 owner={} → {}（{} bytes）", s.getOwnerUserId(), file, data.length);
        } catch (Exception e) {
            s.setLastRunStatus("失敗：" + e.getMessage());
            log.warn("交易紀錄排程匯出失敗 owner={}：{}", s.getOwnerUserId(), e.getMessage(), e);
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

    /** 建立目錄並寫入 xlsx，回傳實際檔案路徑。檔名含 ownerId，避免多使用者同 subpath 時同名互相覆蓋。 */
    private Path writeToDir(Long ownerId, String subpath, byte[] data) throws IOException {
        Path dir = resolveDir(subpath);
        Files.createDirectories(dir);
        String filename = "交易紀錄_" + ownerId + "_" + LocalDate.now(TW_ZONE).format(FILE_DATE) + ".xlsx";
        Path file = dir.resolve(filename);
        Files.write(file, data);
        return file;
    }

    private AssetTransactionExportDto.SettingResponse toResponse(AssetTransactionExportSchedule s) {
        return AssetTransactionExportDto.SettingResponse.builder()
                .enabled(Boolean.TRUE.equals(s.getEnabled()))
                .runHour(s.getRunHour())
                .runMinute(s.getRunMinute())
                .outputSubpath(s.getOutputSubpath())
                .lastRunAt(s.getLastRunAt() == null ? null : s.getLastRunAt().format(TS_FMT))
                .lastRunStatus(s.getLastRunStatus())
                .baseDir(baseDir)
                .build();
    }
}
