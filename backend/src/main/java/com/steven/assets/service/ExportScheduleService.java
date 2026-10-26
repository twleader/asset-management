package com.steven.assets.service;

import com.steven.assets.dto.ExportScheduleDto;
import com.steven.assets.model.ExportScheduleSetting;
import com.steven.assets.repository.ExportScheduleSettingRepository;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.security.UnauthenticatedException;
import com.steven.assets.service.export.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.io.IOException;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/** Independent owner-scoped schedules; all export I/O runs outside database transactions. */
@Service
@Slf4j
public class ExportScheduleService {
    private static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern SCHEDULE_NAME = Pattern.compile("^[\\p{IsHan}A-Za-z0-9_ -]{1,20}$");
    private final ExportScheduleSettingRepository settingRepo;
    private final ExportScheduleExecutionStore executionStore;
    private final ExcelExportService excelExportService;
    private final ObjectProvider<CurrentUserContext> currentUserProvider;
    private final GdriveOutputSupport gdrive;
    private final ExcelDocRenderer excelDocRenderer;
    private final JsonDocRenderer jsonDocRenderer;
    private final DualFormatExportWriter dualWriter;
    private final String baseDir;
    private final AtomicBoolean ticking = new AtomicBoolean(false);

    public ExportScheduleService(ExportScheduleSettingRepository settingRepo, ExportScheduleExecutionStore executionStore,
                   ExcelExportService excelExportService, ObjectProvider<CurrentUserContext> currentUserProvider,
                   GdriveOutputSupport gdrive, ExcelDocRenderer excelDocRenderer,
                   JsonDocRenderer jsonDocRenderer, DualFormatExportWriter dualWriter,
                   @Value("${EXPORT_OUTPUT_DIR:/home/steven}") String baseDir) {
        this.settingRepo = settingRepo; this.executionStore = executionStore;
        this.excelExportService = excelExportService; this.currentUserProvider = currentUserProvider;
        this.gdrive = gdrive; this.excelDocRenderer = excelDocRenderer;
        this.jsonDocRenderer = jsonDocRenderer; this.dualWriter = dualWriter; this.baseDir = baseDir;
    }
    public ExportScheduleDto.SettingResponse getForCurrentUser() {
        return response(executionStore.listForOwner(requireOwnerId()), null);
    }
    public ExportScheduleDto.SettingResponse createForCurrentUser(ExportScheduleDto.ScheduleRequest request) {
        Long owner = requireOwnerId();
        validate(request);
        return executionStore.write(owner, rows -> {
            if (rows.size() >= 10) throw new IllegalArgumentException("每位使用者最多建立 10 筆匯出排程");
            var drive = gdrive.resolveUpdate(owner, request.gdriveEnabled(), request.gdriveSubpath(), false, null);
            ExportScheduleSetting row = ExportScheduleSetting.builder().ownerUserId(owner).build();
            apply(row, request, drive);
            settingRepo.saveAndFlush(row); rows.add(row);
            return response(rows, drive.selfCheckWarning());
        });
    }
    public ExportScheduleDto.SettingResponse updateForCurrentUser(Long id, ExportScheduleDto.ScheduleRequest request) {
        Long owner = requireOwnerId();
        validate(request);
        return executionStore.write(owner, rows -> {
            ExportScheduleSetting row = owned(rows, id);
            var drive = gdrive.resolveUpdate(owner, request.gdriveEnabled(), request.gdriveSubpath(),
                    row.isGdriveEnabled(), row.getGdriveSubpath());
            apply(row, request, drive);
            return response(rows, drive.selfCheckWarning());
        });
    }
    public ExportScheduleDto.SettingResponse deleteForCurrentUser(Long id) {
        Long owner = requireOwnerId();
        return executionStore.write(owner, rows -> {
            ExportScheduleSetting row = owned(rows, id);
            settingRepo.delete(row); settingRepo.flush(); rows.remove(row);
            return response(rows, null);
        });
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ExportScheduleDto.RunNowResponse runNowForCurrentUser(Long id) {
        Long owner = requireOwnerId();
        AssetExportScheduleCapture captured = executionStore.manual(id, owner).orElseThrow(() -> notFound(id));
        DualFormatExportWriter.DualResult result;
        try {
            result = writeDual(captured, excelExportService.liveAssetsDoc());
        } catch (IOException | RuntimeException exception) {
            executionStore.complete(captured, LocalDateTime.now(TW_ZONE), "失敗：" + exception.getMessage(),
                    failedDriveStatus(captured));
            throw new IllegalStateException("立即匯出失敗：" + exception.getMessage(), exception);
        }
        executionStore.complete(captured, LocalDateTime.now(TW_ZONE), result.localStatus(), result.gdriveStatus());
        return ExportScheduleDto.RunNowResponse.builder()
                .path(path(result.xlsxFile())).sizeBytes(size(result.xlsxFile()))
                .gdrivePath(result.xlsxGdrivePath()).gdriveStatus(result.gdriveStatus())
                .jsonPath(path(result.jsonFile())).jsonSizeBytes(size(result.jsonFile()))
                .jsonGdrivePath(result.jsonGdrivePath()).build();
    }
    @Scheduled(cron = "0 * * * * *", zone = "Asia/Taipei")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void tick() { tryRunDueExports(); }
    @EventListener(ApplicationReadyEvent.class)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void selfHealOnStartup() { tryRunDueExports(); }
    private void tryRunDueExports() {
        if (!ticking.compareAndSet(false, true)) return;
        try { runDueExports(); }
        catch (RuntimeException exception) { log.warn("匯出排程檢查失敗", exception); }
        finally { ticking.set(false); }
    }
    void runDueExports() {
        LocalDate today = LocalDate.now(TW_ZONE);
        LocalTime now = LocalTime.now(TW_ZONE);
        for (ExportScheduleSetting row : settingRepo.findAll().stream().sorted(scheduleOrder()).toList()) {
            try {
                for (AssetExportScheduleCapture captured : executionStore.due(row.getId(), row.getOwnerUserId(), today, now)) {
                    runScheduled(captured);
                }
            } catch (RuntimeException exception) {
                log.warn("匯出排程執行失敗 owner={} schedule={}", row.getOwnerUserId(), row.getId(), exception);
            }
        }
    }
    private void runScheduled(AssetExportScheduleCapture captured) {
        String localStatus, driveStatus;
        try {
            var result = writeDual(captured, excelExportService.liveAssetsDocForOwner(captured.ownerId()));
            localStatus = result.localStatus(); driveStatus = result.gdriveStatus();
        } catch (Exception exception) {
            localStatus = "失敗：" + exception.getMessage(); driveStatus = failedDriveStatus(captured);
            log.warn("排程匯出失敗 owner={} schedule={}", captured.ownerId(), captured.scheduleId(), exception);
        }
        executionStore.complete(captured, LocalDateTime.now(TW_ZONE), localStatus, driveStatus);
    }
    private String failedDriveStatus(AssetExportScheduleCapture captured) {
        return captured.gdriveEnabled() ? gdrive.syncQuietly(captured.ownerId(), captured.gdriveSubpath(), null).status() : null;
    }
    private DualFormatExportWriter.DualResult writeDual(AssetExportScheduleCapture captured, ExportDoc document) throws IOException {
        resolveDir(normalizeSubpath(captured.outputSubpath()));
        String name = normalizeName(captured.name()); validateName(name);
        byte[] xlsx = null, json = null;
        try { xlsx = excelDocRenderer.render(document); }
        catch (Exception exception) { log.warn("xlsx render 失敗 owner={}", captured.ownerId(), exception); }
        try { json = jsonDocRenderer.render(document); }
        catch (Exception exception) { log.warn("json render 失敗 owner={}", captured.ownerId(), exception); }
        String baseName = "資產總覽_" + captured.ownerId() + (name == null ? "" : "_" + name)
                + "_" + LocalDate.now(TW_ZONE).format(FILE_DATE);
        // Resolve aliases again immediately before local writes, after potentially slow rendering.
        return dualWriter.write(captured.ownerId(), resolveDir(normalizeSubpath(captured.outputSubpath())),
                baseName, json, xlsx, captured.gdriveEnabled(), captured.gdriveSubpath());
    }
    private void validate(ExportScheduleDto.ScheduleRequest request) {
        if (request == null) throw new IllegalArgumentException("排程設定不可為空");
        if (request.runHour() == null || request.runMinute() == null || request.runHour() < 0
                || request.runHour() > 23 || request.runMinute() < 0 || request.runMinute() > 59)
            throw new IllegalArgumentException("時間必須介於 00:00～23:59");
        validateName(normalizeName(request.name()));
        String subpath = normalizeSubpath(request.outputSubpath());
        if (subpath.length() > 255) throw new IllegalArgumentException("輸出子路徑最多 255 字");
        resolveDir(subpath);
        if (request.gdriveSubpath() != null && request.gdriveSubpath().length() > 512)
            throw new IllegalArgumentException("Drive 子路徑最多 512 字");
    }
    private void apply(ExportScheduleSetting row, ExportScheduleDto.ScheduleRequest request, GdriveOutputSupport.DriveSettings drive) {
        row.setName(normalizeName(request.name())); row.setEnabled(Boolean.TRUE.equals(request.enabled()));
        row.setRunHour(request.runHour()); row.setRunMinute(request.runMinute());
        row.setOutputSubpath(normalizeSubpath(request.outputSubpath()));
        row.setGdriveEnabled(drive.enabled()); row.setGdriveSubpath(drive.subpath());
        row.setUpdatedAt(LocalDateTime.now(TW_ZONE));
    }
    private ExportScheduleDto.SettingResponse response(List<ExportScheduleSetting> rows, String warning) {
        var items = rows.stream().sorted(scheduleOrder()).map(row -> new ExportScheduleDto.ScheduleItem(row.getId(),
                row.getName(), Boolean.TRUE.equals(row.getEnabled()), row.getRunHour(), row.getRunMinute(),
                row.getOutputSubpath(), format(row.getLastRunAt()), row.getLastRunStatus(), row.isGdriveEnabled(),
                row.getGdriveSubpath(), format(row.getGdriveLastRunAt()), row.getGdriveLastStatus())).toList();
        return new ExportScheduleDto.SettingResponse(baseDir, gdrive.remoteName(), items, warning);
    }
    private static Comparator<ExportScheduleSetting> scheduleOrder() {
        return Comparator.comparing(ExportScheduleSetting::getRunHour).thenComparing(ExportScheduleSetting::getRunMinute)
                .thenComparing(ExportScheduleSetting::getId);
    }
    private static ExportScheduleSetting owned(List<ExportScheduleSetting> rows, Long id) {
        return rows.stream().filter(row -> Objects.equals(row.getId(), id)).findFirst().orElseThrow(() -> notFound(id));
    }
    private static NoSuchElementException notFound(Long id) { return new NoSuchElementException("找不到排程 " + id); }
    private static void validateName(String name) {
        if (name != null && !SCHEDULE_NAME.matcher(name).matches())
            throw new IllegalArgumentException("排程名稱限 20 字，且只能使用漢字、英數、底線、連字號與空白");
    }
    private static String normalizeName(String name) { return name == null || name.trim().isEmpty() ? null : name.trim(); }
    private static String normalizeSubpath(String value) { return value == null || value.trim().isEmpty() ? "input" : value.trim(); }
    private static String format(LocalDateTime value) { return value == null ? null : value.format(TS_FMT); }
    private static String path(Path value) { return value == null ? null : value.toString(); }
    private static long size(Path value) {
        try { return value == null ? 0 : Files.size(value); }
        catch (IOException exception) { return 0; }
    }
    private Path resolveDir(String subpath) {
        try {
            Path base = Path.of(baseDir).toAbsolutePath().normalize();
            Path target = base.resolve(subpath).normalize();
            if (!target.startsWith(base)) throw new IllegalArgumentException("輸出子路徑不可跳脫基底目錄：" + subpath);
            Path canonicalBase = LocalExportPathComparator.resolveExistingAliases(base);
            Path canonicalTarget = LocalExportPathComparator.resolveExistingAliases(target);
            if (!canonicalTarget.startsWith(canonicalBase)) throw new IllegalArgumentException("輸出子路徑不可跳脫基底目錄：" + subpath);
            return canonicalTarget;
        } catch (InvalidPathException exception) { throw new IllegalArgumentException("輸出子路徑不合法：" + subpath, exception); }
        catch (IOException | SecurityException exception) { throw new IllegalArgumentException("輸出子路徑無法解析：" + subpath, exception); }
    }
    private Long requireOwnerId() {
        var user = currentUserProvider.getObject();
        if (!user.hasUser()) throw new UnauthenticatedException("未識別使用者，無法存取排程設定");
        return user.getEffectiveUserId();
    }
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


}
