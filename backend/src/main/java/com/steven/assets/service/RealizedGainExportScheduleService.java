package com.steven.assets.service;

import com.steven.assets.dto.RealizedGainExportDto;
import com.steven.assets.model.RealizedGainExportSchedule;
import com.steven.assets.repository.RealizedGainExportScheduleRepository;
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
public class RealizedGainExportScheduleService {
    private static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern SCHEDULE_NAME = Pattern.compile("^[\\p{IsHan}A-Za-z0-9_ -]{1,20}$");
    private final RealizedGainExportScheduleRepository settingRepo;
    private final RealizedGainExportScheduleExecutionStore executionStore;
    private final ExcelExportService excelExportService;
    private final ObjectProvider<CurrentUserContext> currentUserProvider;
    private final GdriveOutputSupport gdrive;
    private final ExcelDocRenderer excelDocRenderer;
    private final JsonDocRenderer jsonDocRenderer;
    private final DualFormatExportWriter dualWriter;
    private final String baseDir;
    private final AtomicBoolean ticking = new AtomicBoolean(false);

    public RealizedGainExportScheduleService(RealizedGainExportScheduleRepository settingRepo, RealizedGainExportScheduleExecutionStore executionStore,
                   ExcelExportService excelExportService, ObjectProvider<CurrentUserContext> currentUserProvider,
                   GdriveOutputSupport gdrive, ExcelDocRenderer excelDocRenderer,
                   JsonDocRenderer jsonDocRenderer, DualFormatExportWriter dualWriter,
                   @Value("${EXPORT_OUTPUT_DIR:/home/steven}") String baseDir) {
        this.settingRepo = settingRepo; this.executionStore = executionStore;
        this.excelExportService = excelExportService; this.currentUserProvider = currentUserProvider;
        this.gdrive = gdrive; this.excelDocRenderer = excelDocRenderer;
        this.jsonDocRenderer = jsonDocRenderer; this.dualWriter = dualWriter; this.baseDir = baseDir;
    }
    public RealizedGainExportDto.SettingResponse getForCurrentUser() {
        return response(executionStore.listForOwner(requireOwnerId()), null);
    }
    public RealizedGainExportDto.SettingResponse createForCurrentUser(RealizedGainExportDto.ScheduleRequest request) {
        Long owner = requireOwnerId();
        validate(request);
        return executionStore.write(owner, rows -> {
            if (rows.size() >= 10) throw new IllegalArgumentException("每位使用者最多建立 10 筆匯出排程");
            var drive = gdrive.resolveUpdate(owner, request.gdriveEnabled(), request.gdriveSubpath(), false, null);
            RealizedGainExportSchedule row = RealizedGainExportSchedule.builder().ownerUserId(owner).build();
            apply(row, request, drive);
            settingRepo.saveAndFlush(row); rows.add(row);
            return response(rows, drive.selfCheckWarning());
        });
    }
    public RealizedGainExportDto.SettingResponse updateForCurrentUser(Long id, RealizedGainExportDto.ScheduleRequest request) {
        Long owner = requireOwnerId();
        validate(request);
        return executionStore.write(owner, rows -> {
            RealizedGainExportSchedule row = owned(rows, id);
            var drive = gdrive.resolveUpdate(owner, request.gdriveEnabled(), request.gdriveSubpath(),
                    row.isGdriveEnabled(), row.getGdriveSubpath());
            apply(row, request, drive);
            return response(rows, drive.selfCheckWarning());
        });
    }
    public RealizedGainExportDto.SettingResponse deleteForCurrentUser(Long id) {
        Long owner = requireOwnerId();
        return executionStore.write(owner, rows -> {
            RealizedGainExportSchedule row = owned(rows, id);
            settingRepo.delete(row); settingRepo.flush(); rows.remove(row);
            return response(rows, null);
        });
    }
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RealizedGainExportDto.RunNowResponse runNowForCurrentUser(Long id) {
        Long owner = requireOwnerId();
        RealizedGainExportScheduleCapture captured = executionStore.manual(id, owner).orElseThrow(() -> notFound(id));
        DualFormatExportWriter.DualResult result;
        try {
            result = writeDual(captured, excelExportService.realizedGainsDoc());
        } catch (IOException | RuntimeException exception) {
            executionStore.complete(captured, LocalDateTime.now(TW_ZONE), "失敗：" + exception.getMessage(),
                    failedDriveStatus(captured));
            throw new IllegalStateException("立即匯出失敗：" + exception.getMessage(), exception);
        }
        executionStore.complete(captured, LocalDateTime.now(TW_ZONE), result.localStatus(), result.gdriveStatus());
        return RealizedGainExportDto.RunNowResponse.builder()
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
        for (RealizedGainExportSchedule row : settingRepo.findAll().stream().sorted(scheduleOrder()).toList()) {
            try {
                for (RealizedGainExportScheduleCapture captured : executionStore.due(row.getId(), row.getOwnerUserId(), today, now)) {
                    runScheduled(captured);
                }
            } catch (RuntimeException exception) {
                log.warn("匯出排程執行失敗 owner={} schedule={}", row.getOwnerUserId(), row.getId(), exception);
            }
        }
    }
    private void runScheduled(RealizedGainExportScheduleCapture captured) {
        String localStatus, driveStatus;
        try {
            var result = writeDual(captured, excelExportService.realizedGainsDocForOwner(captured.ownerId()));
            localStatus = result.localStatus(); driveStatus = result.gdriveStatus();
        } catch (Exception exception) {
            localStatus = "失敗：" + exception.getMessage(); driveStatus = failedDriveStatus(captured);
            log.warn("排程匯出失敗 owner={} schedule={}", captured.ownerId(), captured.scheduleId(), exception);
        }
        executionStore.complete(captured, LocalDateTime.now(TW_ZONE), localStatus, driveStatus);
    }
    private String failedDriveStatus(RealizedGainExportScheduleCapture captured) {
        return captured.gdriveEnabled() ? gdrive.syncQuietly(captured.ownerId(), captured.gdriveSubpath(), null).status() : null;
    }
    private DualFormatExportWriter.DualResult writeDual(RealizedGainExportScheduleCapture captured, ExportDoc document) throws IOException {
        resolveDir(normalizeSubpath(captured.outputSubpath()));
        String name = normalizeName(captured.name()); validateName(name);
        byte[] xlsx = null, json = null;
        try { xlsx = excelDocRenderer.render(document); }
        catch (Exception exception) { log.warn("xlsx render 失敗 owner={}", captured.ownerId(), exception); }
        try { json = jsonDocRenderer.render(document); }
        catch (Exception exception) { log.warn("json render 失敗 owner={}", captured.ownerId(), exception); }
        String baseName = "已實現損益_" + captured.ownerId() + (name == null ? "" : "_" + name)
                + "_" + LocalDate.now(TW_ZONE).format(FILE_DATE);
        // Resolve aliases again immediately before local writes, after potentially slow rendering.
        return dualWriter.write(captured.ownerId(), resolveDir(normalizeSubpath(captured.outputSubpath())),
                baseName, json, xlsx, captured.gdriveEnabled(), captured.gdriveSubpath());
    }
    private void validate(RealizedGainExportDto.ScheduleRequest request) {
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
    private void apply(RealizedGainExportSchedule row, RealizedGainExportDto.ScheduleRequest request, GdriveOutputSupport.DriveSettings drive) {
        row.setName(normalizeName(request.name())); row.setEnabled(Boolean.TRUE.equals(request.enabled()));
        row.setRunHour(request.runHour()); row.setRunMinute(request.runMinute());
        row.setOutputSubpath(normalizeSubpath(request.outputSubpath()));
        row.setGdriveEnabled(drive.enabled()); row.setGdriveSubpath(drive.subpath());
        row.setUpdatedAt(LocalDateTime.now(TW_ZONE));
    }
    private RealizedGainExportDto.SettingResponse response(List<RealizedGainExportSchedule> rows, String warning) {
        var items = rows.stream().sorted(scheduleOrder()).map(row -> new RealizedGainExportDto.ScheduleItem(row.getId(),
                row.getName(), Boolean.TRUE.equals(row.getEnabled()), row.getRunHour(), row.getRunMinute(),
                row.getOutputSubpath(), format(row.getLastRunAt()), row.getLastRunStatus(), row.isGdriveEnabled(),
                row.getGdriveSubpath(), format(row.getGdriveLastRunAt()), row.getGdriveLastStatus())).toList();
        return new RealizedGainExportDto.SettingResponse(baseDir, gdrive.remoteName(), items, warning);
    }
    private static Comparator<RealizedGainExportSchedule> scheduleOrder() {
        return Comparator.comparing(RealizedGainExportSchedule::getRunHour).thenComparing(RealizedGainExportSchedule::getRunMinute)
                .thenComparing(RealizedGainExportSchedule::getId);
    }
    private static RealizedGainExportSchedule owned(List<RealizedGainExportSchedule> rows, Long id) {
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

}
