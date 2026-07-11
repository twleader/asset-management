package com.steven.assets.service;

import com.steven.assets.dto.ExportScheduleDto;
import com.steven.assets.model.ExportScheduleSetting;
import com.steven.assets.repository.ExportScheduleSettingRepository;
import com.steven.assets.security.CurrentUserContext;
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
 * 歷年資產每日排程自動匯出（Requirement 34 / Task 171）。
 *
 * <p>每個使用者可各自設定啟用開關、每日執行時分、輸出相對子路徑。因 {@code @Scheduled} 的 cron 於啟動期固定、
 * 無法吃 DB 可調時間，改採「每分鐘 poll ＋ 當日 guard ＋ 開機自癒補跑」（比照 {@code MarketAnalysisScheduler}）。
 *
 * <p>租戶隔離：GET/PUT/run-now 走 HTTP（BFF→business），由 {@code TenantFilterAspect} 自動 owner-scoped 到本人；
 * 背景 poll 無 request context → {@code ownerFilter} 不啟用，{@code findAll()} 讀全部 owner 列，
 * 產檔時才以 {@link ExcelExportService#exportFullForOwner(Long)} 對該列 owner 手動 {@code enableFilter}。
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

    private final ExportScheduleSettingRepository settingRepo;
    private final ExcelExportService excelExportService;
    private final ObjectProvider<CurrentUserContext> currentUserProvider;

    /** 容器內基底輸出目錄，經 docker volume 對映到 host（見 docker-compose.yml）。 */
    private final String baseDir;

    /** 避免每分鐘 poll 在上一輪尚未跑完時重入。 */
    private final AtomicBoolean ticking = new AtomicBoolean(false);

    public ExportScheduleService(ExportScheduleSettingRepository settingRepo,
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
    public ExportScheduleDto.SettingResponse getForCurrentUser() {
        Long ownerId = requireOwnerId();
        ExportScheduleSetting s = settingRepo.findByOwnerUserId(ownerId).orElseGet(() ->
                ExportScheduleSetting.builder().ownerUserId(ownerId).build());
        return toResponse(s);
    }

    /** upsert 當前使用者設定。 */
    public ExportScheduleDto.SettingResponse updateForCurrentUser(ExportScheduleDto.SettingRequest req) {
        Long ownerId = requireOwnerId();
        int hour = req.getRunHour() == null ? 8 : req.getRunHour();
        int minute = req.getRunMinute() == null ? 0 : req.getRunMinute();
        if (hour < 0 || hour > 23) throw new IllegalArgumentException("執行時(hour)必須介於 0～23");
        if (minute < 0 || minute > 59) throw new IllegalArgumentException("執行分(minute)必須介於 0～59");
        String subpath = normalizeSubpath(req.getOutputSubpath());
        resolveDir(subpath); // 驗證不跳脫基底（丟出即擋下）

        ExportScheduleSetting s = settingRepo.findByOwnerUserId(ownerId).orElseGet(() ->
                ExportScheduleSetting.builder().ownerUserId(ownerId).build());
        s.setOwnerUserId(ownerId);
        s.setEnabled(Boolean.TRUE.equals(req.getEnabled()));
        s.setRunHour(hour);
        s.setRunMinute(minute);
        s.setOutputSubpath(subpath);
        s.setUpdatedAt(LocalDateTime.now(TW_ZONE));
        return toResponse(settingRepo.save(s));
    }

    /** 立即以當前使用者身分產檔寫入其設定目錄（供驗證）。不動當日 guard。 */
    public ExportScheduleDto.RunNowResponse runNowForCurrentUser() {
        Long ownerId = requireOwnerId();
        ExportScheduleSetting s = settingRepo.findByOwnerUserId(ownerId).orElseGet(() ->
                ExportScheduleSetting.builder().ownerUserId(ownerId).build());
        String subpath = normalizeSubpath(s.getOutputSubpath());
        try {
            // HTTP 情境：exportLiveAssets() 由 TenantFilterAspect 自動 owner-scoped 到當前使用者。
            byte[] data = excelExportService.exportLiveAssets();
            Path file = writeToDir(ownerId, subpath, data);
            s.setOwnerUserId(ownerId);
            s.setLastRunAt(LocalDateTime.now(TW_ZONE));
            s.setLastRunStatus("成功：" + file);
            settingRepo.save(s);
            return ExportScheduleDto.RunNowResponse.builder()
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
            byte[] data = excelExportService.exportLiveAssetsForOwner(s.getOwnerUserId());
            Path file = writeToDir(s.getOwnerUserId(), normalizeSubpath(s.getOutputSubpath()), data);
            s.setLastRunStatus("成功：" + file);
            log.info("排程匯出成功 owner={} → {}（{} bytes）", s.getOwnerUserId(), file, data.length);
        } catch (Exception e) {
            s.setLastRunStatus("失敗：" + e.getMessage());
            log.warn("排程匯出失敗 owner={}：{}", s.getOwnerUserId(), e.getMessage(), e);
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
            throw new IllegalStateException("未識別使用者，無法存取排程設定");
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
        String filename = "資產總覽_" + ownerId + "_" + LocalDate.now(TW_ZONE).format(FILE_DATE) + ".xlsx";
        Path file = dir.resolve(filename);
        Files.write(file, data);
        return file;
    }

    private ExportScheduleDto.SettingResponse toResponse(ExportScheduleSetting s) {
        return ExportScheduleDto.SettingResponse.builder()
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
