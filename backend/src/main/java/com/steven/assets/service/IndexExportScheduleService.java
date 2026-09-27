package com.steven.assets.service;

import com.steven.assets.dto.IndexExportDto;
import com.steven.assets.model.IndexExportSchedule;
import com.steven.assets.repository.IndexExportScheduleRepository;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.security.UnauthenticatedException;
import com.steven.assets.service.export.DualFormatExportWriter;
import com.steven.assets.service.export.ExcelDocRenderer;
import com.steven.assets.service.export.JsonDocRenderer;
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
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Independent per-owner index schedules with short database transactions around immutable captures. */
@Service
@Slf4j
public class IndexExportScheduleService {
    private static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_RANGE_MONTHS = 120;
    private static final int MAX_SCHEDULES_PER_OWNER = 10;
    private static final Pattern SCHEDULE_NAME = Pattern.compile("^[\\p{IsHan}A-Za-z0-9_ -]{1,20}$");

    private final IndexExportScheduleRepository scheduleRepository;
    private final IndexExportScheduleExecutionStore executionStore;
    private final ExcelExportService excelExportService;
    private final ObjectProvider<CurrentUserContext> currentUserProvider;
    private final GdriveOutputSupport gdrive;
    private final ExcelDocRenderer excelDocRenderer;
    private final JsonDocRenderer jsonDocRenderer;
    private final DualFormatExportWriter dualWriter;
    private final String baseDir;
    private final AtomicBoolean ticking = new AtomicBoolean(false);

    public IndexExportScheduleService(IndexExportScheduleRepository scheduleRepository,
                                      IndexExportScheduleExecutionStore executionStore,
                                      ExcelExportService excelExportService,
                                      ObjectProvider<CurrentUserContext> currentUserProvider,
                                      GdriveOutputSupport gdrive,
                                      ExcelDocRenderer excelDocRenderer,
                                      JsonDocRenderer jsonDocRenderer,
                                      DualFormatExportWriter dualWriter,
                                      @Value("${EXPORT_OUTPUT_DIR:/home/steven}") String baseDir) {
        this.scheduleRepository = scheduleRepository;
        this.executionStore = executionStore;
        this.excelExportService = excelExportService;
        this.currentUserProvider = currentUserProvider;
        this.gdrive = gdrive;
        this.excelDocRenderer = excelDocRenderer;
        this.jsonDocRenderer = jsonDocRenderer;
        this.dualWriter = dualWriter;
        this.baseDir = baseDir;
    }

    @Transactional(readOnly = true)
    public IndexExportDto.SettingResponse getForCurrentUser() {
        return response(executionStore.listForOwner(requireOwnerId()), null);
    }

    public IndexExportDto.SettingResponse createForCurrentUser(IndexExportDto.ScheduleRequest request) {
        Long ownerId = requireOwnerId();
        ScheduleDraft draft = validate(request);
        return executionStore.write(ownerId, schedules -> {
            if (schedules.size() >= MAX_SCHEDULES_PER_OWNER) {
                throw new IllegalArgumentException("每位使用者最多建立 10 筆大盤指數匯出排程");
            }
            GdriveOutputSupport.DriveSettings driveSettings = gdrive.resolveUpdate(ownerId,
                    request.gdriveEnabled(), request.gdriveSubpath(), false, null);
            IndexExportSchedule created = toEntity(ownerId, draft, driveSettings);
            assertNoCollision(schedules, created, null);
            scheduleRepository.saveAndFlush(created);
            schedules.add(created);
            sortSchedules(schedules);
            return response(schedules, driveSettings.selfCheckWarning());
        });
    }

    public IndexExportDto.SettingResponse updateForCurrentUser(Long scheduleId,
                                                               IndexExportDto.ScheduleRequest request) {
        Long ownerId = requireOwnerId();
        ScheduleDraft draft = validate(request);
        return executionStore.write(ownerId, schedules -> {
            IndexExportSchedule current = schedules.stream()
                    .filter(schedule -> Objects.equals(schedule.getId(), scheduleId))
                    .findFirst().orElseThrow(() -> notFound(scheduleId));
            GdriveOutputSupport.DriveSettings driveSettings = gdrive.resolveUpdate(ownerId,
                    request.gdriveEnabled(), request.gdriveSubpath(),
                    current.isGdriveEnabled(), current.getGdriveSubpath());
            IndexExportSchedule proposed = toEntity(ownerId, draft, driveSettings);
            proposed.setId(current.getId());
            assertNoCollision(schedules, proposed, scheduleId);
            copySettings(proposed, current);
            current.setUpdatedAt(LocalDateTime.now(TW_ZONE));
            sortSchedules(schedules);
            return response(schedules, driveSettings.selfCheckWarning());
        });
    }

    public IndexExportDto.SettingResponse deleteForCurrentUser(Long scheduleId) {
        Long ownerId = requireOwnerId();
        return executionStore.write(ownerId, schedules -> {
            IndexExportSchedule current = schedules.stream()
                    .filter(schedule -> Objects.equals(schedule.getId(), scheduleId))
                    .findFirst().orElseThrow(() -> notFound(scheduleId));
            // Repeat the explicit id-and-owner predicate at the persistence boundary.
            IndexExportSchedule owned = scheduleRepository.findByIdAndOwnerUserId(scheduleId, ownerId)
                    .orElseThrow(() -> notFound(scheduleId));
            scheduleRepository.delete(owned);
            schedules.remove(current);
            return response(schedules, null);
        });
    }

    /** The id is resolved before the export try/catch so a foreign/missing id stays a 404. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public IndexExportDto.RunNowResponse runNowForCurrentUser(Long scheduleId) {
        Long ownerId = requireOwnerId();
        IndexExportScheduleCapture capture = executionStore.manual(scheduleId, ownerId)
                .orElseThrow(() -> notFound(scheduleId));
        if (capture.markets().isEmpty()) {
            throw new IllegalArgumentException("此排程尚未選擇指數，請先選擇至少一個指數再執行");
        }

        List<IndexExportDto.FileResult> results = new ArrayList<>();
        for (String market : capture.markets()) {
            try {
                results.add(exportOne(capture, market));
            } catch (RuntimeException exception) {
                log.warn("大盤指數立即匯出失敗 owner={} schedule={} market={}", ownerId, scheduleId, market, exception);
                results.add(failedResult(market, exception));
            }
        }
        LocalDateTime completedAt = LocalDateTime.now(TW_ZONE);
        String localStatus = localSummary(results);
        String driveStatus = capture.gdriveEnabled() ? driveSummary(results) : null;
        executionStore.complete(capture, completedAt, localStatus, driveStatus);
        return new IndexExportDto.RunNowResponse(scheduleId, List.copyOf(results), driveStatus);
    }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Taipei")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void tick() {
        if (!ticking.compareAndSet(false, true)) return;
        try {
            runDueExports();
        } catch (RuntimeException exception) {
            log.warn("大盤指數排程 tick 失敗：{}", exception.getMessage(), exception);
        } finally {
            ticking.set(false);
        }
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void selfHealOnStartup() {
        try {
            runDueExports();
        } catch (RuntimeException exception) {
            log.warn("大盤指數開機自癒失敗：{}", exception.getMessage(), exception);
        }
    }

    private void runDueExports() {
        LocalDate today = LocalDate.now(TW_ZONE);
        LocalTime now = LocalTime.now(TW_ZONE);
        for (IndexExportSchedule candidate : scheduleRepository.findAll()) {
            try {
                for (IndexExportScheduleCapture capture : executionStore.due(
                        candidate.getId(), candidate.getOwnerUserId(), today, now)) {
                    runScheduled(capture);
                }
            } catch (RuntimeException exception) {
                log.warn("匯出設定捕捉失敗 owner={} schedule={}",
                        candidate.getOwnerUserId(), candidate.getId(), exception);
            }
        }
    }

    private void runScheduled(IndexExportScheduleCapture capture) {
        List<IndexExportDto.FileResult> results = new ArrayList<>();
        for (String market : capture.markets()) {
            try {
                results.add(exportOne(capture, market));
            } catch (RuntimeException exception) {
                log.warn("大盤指數排程匯出失敗 owner={} schedule={} market={}",
                        capture.ownerId(), capture.scheduleId(), market, exception);
                results.add(failedResult(market, exception));
            }
        }
        executionStore.complete(capture, LocalDateTime.now(TW_ZONE), localSummary(results),
                capture.gdriveEnabled() ? driveSummary(results) : null);
    }

    private IndexExportDto.FileResult exportOne(IndexExportScheduleCapture capture, String market) {
        Path outputDir = resolveDir(normalizeSubpath(capture.outputSubpath()));
        LocalDate end = LocalDate.now(TW_ZONE);
        LocalDate start = capture.rangeMonths() == null
                ? end.minusYears(10) : end.minusMonths(capture.rangeMonths());
        var document = excelExportService.indexDailyDoc(market, start, end);
        String name = validatedStoredName(capture.name());
        String baseName = ExcelExportService.indexLabel(market) + "_" + capture.ownerId()
                + (name == null ? "" : "_" + name) + "_" + end.format(FILE_DATE);
        byte[] json = renderJson(document);
        byte[] excel = renderExcel(document);
        outputDir = resolveDir(normalizeSubpath(capture.outputSubpath()));
        try {
            var written = dualWriter.write(capture.ownerId(), outputDir,
                    baseName, json, excel,
                    capture.gdriveEnabled(), capture.gdriveSubpath());
            String jsonPath = written.jsonFile() == null ? null : written.jsonFile().toString();
            String xlsxPath = written.xlsxFile() == null ? null : written.xlsxFile().toString();
            String error = written.jsonFile() == null || written.xlsxFile() == null
                    ? written.localStatus() : null;
            return new IndexExportDto.FileResult(market, ExcelExportService.indexLabel(market), xlsxPath,
                    size(written.xlsxFile()), jsonPath, size(written.jsonFile()), written.xlsxGdrivePath(),
                    written.jsonGdrivePath(), written.gdriveStatus(), error);
        } catch (IOException exception) {
            throw new IllegalStateException("匯出 " + market + " 失敗：" + exception.getMessage(), exception);
        }
    }

    private byte[] renderExcel(com.steven.assets.service.export.ExportDoc document) {
        try {
            return excelDocRenderer.render(document);
        } catch (Exception exception) {
            log.warn("xlsx render 失敗：{}", exception.getMessage());
            return null;
        }
    }

    private byte[] renderJson(com.steven.assets.service.export.ExportDoc document) {
        try {
            return jsonDocRenderer.render(document);
        } catch (Exception exception) {
            log.warn("json render 失敗：{}", exception.getMessage());
            return null;
        }
    }

    private IndexExportDto.FileResult failedResult(String market, RuntimeException exception) {
        return new IndexExportDto.FileResult(market, ExcelExportService.indexLabel(market), null, 0,
                null, 0, null, null, null, message(exception));
    }

    private static long size(Path file) {
        try {
            return file == null ? 0 : Files.size(file);
        } catch (IOException exception) {
            return 0;
        }
    }

    private static String localSummary(List<IndexExportDto.FileResult> results) {
        if (results.isEmpty()) return "略過：未選指數";
        return IndexExportScheduleCapture.truncate(results.stream().map(result -> {
            if (result.error() != null) return result.market() + " 失敗：" + result.error();
            return result.market() + (result.path() != null && result.jsonPath() != null
                    ? " 成功：" + result.path() + "／" + result.jsonPath()
                    : " 部分失敗：" + result.error());
        }).reduce((left, right) -> left + "；" + right).orElse("略過：未選指數"), 500);
    }

    private static String driveSummary(List<IndexExportDto.FileResult> results) {
        String summary = results.stream().map(result -> result.market() + "："
                + (result.gdriveStatus() == null ? (result.error() == null ? "略過" : "失敗：" + result.error())
                : result.gdriveStatus())).reduce((left, right) -> left + "；" + right).orElse("略過：未選指數");
        return IndexExportScheduleCapture.truncate(summary, 512);
    }

    private static String message(RuntimeException exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    private ScheduleDraft validate(IndexExportDto.ScheduleRequest request) {
        if (request == null) throw new IllegalArgumentException("排程設定不可為空");
        if (request.enabled() == null) throw new IllegalArgumentException("請指定排程啟用狀態");
        if (request.runHour() == null || request.runMinute() == null
                || request.runHour() < 0 || request.runHour() > 23
                || request.runMinute() < 0 || request.runMinute() > 59) {
            throw new IllegalArgumentException("時間必須介於 00:00～23:59");
        }
        if (request.rangeMonths() != null
                && (request.rangeMonths() < 1 || request.rangeMonths() > MAX_RANGE_MONTHS)) {
            throw new IllegalArgumentException("匯出範圍(月)必須介於 1～" + MAX_RANGE_MONTHS + "，或留空代表全部十年");
        }
        String normalizedName = normalizeName(request.name());
        if (normalizedName != null && !SCHEDULE_NAME.matcher(normalizedName).matches()) {
            throw new IllegalArgumentException("排程名稱限 20 字，且只能使用漢字、英數、底線、連字號與空白");
        }
        List<String> markets = normalizeMarkets(request.markets());
        String outputSubpath = normalizeSubpath(request.outputSubpath());
        resolveDir(outputSubpath);
        return new ScheduleDraft(normalizedName, request.enabled(), request.runHour(), request.runMinute(),
                markets, request.rangeMonths(), outputSubpath);
    }

    private List<String> normalizeMarkets(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("至少選擇一個匯出指數");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("指數代碼不可為空白");
            }
            String market = value.trim().toUpperCase(Locale.ROOT);
            if (!MacroHistoryService.DAILY_INDEX_CODES.contains(market)) {
                throw new IllegalArgumentException("未知指數代碼：" + value);
            }
            normalized.add(market);
        }
        if (normalized.isEmpty()) throw new IllegalArgumentException("至少選擇一個匯出指數");
        return List.copyOf(normalized);
    }

    private IndexExportSchedule toEntity(Long ownerId, ScheduleDraft draft,
                                         GdriveOutputSupport.DriveSettings drive) {
        return IndexExportSchedule.builder().ownerUserId(ownerId).name(draft.name())
                .enabled(draft.enabled()).runHour(draft.runHour()).runMinute(draft.runMinute())
                .markets(new LinkedHashSet<>(draft.markets())).rangeMonths(draft.rangeMonths())
                .outputSubpath(draft.outputSubpath()).gdriveEnabled(drive.enabled())
                .gdriveSubpath(drive.subpath()).build();
    }

    private static void copySettings(IndexExportSchedule source, IndexExportSchedule target) {
        target.setName(source.getName());
        target.setEnabled(source.getEnabled());
        target.setRunHour(source.getRunHour());
        target.setRunMinute(source.getRunMinute());
        target.setMarkets(new LinkedHashSet<>(source.getMarkets()));
        target.setRangeMonths(source.getRangeMonths());
        target.setOutputSubpath(source.getOutputSubpath());
        target.setGdriveEnabled(source.isGdriveEnabled());
        target.setGdriveSubpath(source.getGdriveSubpath());
    }

    private void assertNoCollision(List<IndexExportSchedule> schedules, IndexExportSchedule candidate,
                                   Long excludedId) {
        Path candidateLocal = resolveDir(normalizeSubpath(candidate.getOutputSubpath()));
        String candidateName = normalizeName(candidate.getName());
        String candidateDrive = gdrive.normalizeSubpath(candidate.getGdriveSubpath());
        for (IndexExportSchedule existing : schedules) {
            if (Objects.equals(existing.getId(), excludedId)) continue;
            Path existingLocal;
            try {
                existingLocal = resolveDir(normalizeSubpath(existing.getOutputSubpath()));
            } catch (RuntimeException exception) {
                log.warn("略過無法解析舊排程輸出路徑 owner={} schedule={}：{}",
                        existing.getOwnerUserId(), existing.getId(), exception.getMessage());
                continue;
            }
            boolean caseInsensitiveVolume = LocalExportPathComparator.caseInsensitiveVolume(candidateLocal, existingLocal);
            boolean sameLocalName = LocalExportPathComparator.sameName(
                    candidateName, normalizeName(existing.getName()), caseInsensitiveVolume);
            boolean sameLocalTarget = LocalExportPathComparator.sameTarget(
                    candidateLocal, existingLocal, caseInsensitiveVolume);
            boolean sameDriveName = Objects.equals(candidateName, normalizeName(existing.getName()));
            for (String market : candidate.getMarkets()) {
                if (sameLocalName && sameLocalTarget && existing.getMarkets().contains(market)) {
                    throw new IllegalArgumentException("指數 " + market + " 與排程 ID " + existing.getId()
                            + " 的名稱及本機輸出落點衝突");
                }
                String existingDrive = gdrive.normalizeSubpath(existing.getGdriveSubpath());
                if (candidate.isGdriveEnabled() && existing.isGdriveEnabled() && sameDriveName
                        && Objects.equals(candidateDrive, existingDrive)
                        && existing.getMarkets().contains(market)) {
                    throw new IllegalArgumentException("指數 " + market + " 與排程 ID " + existing.getId()
                            + " 的名稱及 Google Drive 落點衝突");
                }
            }
        }
    }

    private IndexExportDto.SettingResponse response(List<IndexExportSchedule> schedules, String warning) {
        List<IndexExportDto.ScheduleItem> items = schedules.stream()
                .sorted(scheduleOrder())
                .map(schedule -> new IndexExportDto.ScheduleItem(schedule.getId(), schedule.getName(),
                        schedule.getEnabled(), schedule.getRunHour(), schedule.getRunMinute(),
                        schedule.getMarkets().stream().sorted().toList(), schedule.getRangeMonths(),
                        schedule.getOutputSubpath(), schedule.getLastRunDate(),
                        format(schedule.getLastRunAt()), schedule.getLastRunStatus(),
                        schedule.isGdriveEnabled(), schedule.getGdriveSubpath(),
                        format(schedule.getGdriveLastRunAt()), schedule.getGdriveLastStatus()))
                .toList();
        List<IndexExportDto.MarketOption> options = Stream.concat(Stream.of("TWSE"),
                        MacroHistoryService.PAGE_CODED_INDEX_CODES.stream())
                .distinct().map(value -> new IndexExportDto.MarketOption(value,
                        ExcelExportService.indexLabel(value))).toList();
        return new IndexExportDto.SettingResponse(items, baseDir, gdrive.remoteName(), warning, options);
    }

    private static String format(LocalDateTime value) {
        return value == null ? null : value.format(TS_FMT);
    }

    private static Comparator<IndexExportSchedule> scheduleOrder() {
        return Comparator.comparing(IndexExportSchedule::getRunHour, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(IndexExportSchedule::getRunMinute, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(IndexExportSchedule::getId, Comparator.nullsLast(Long::compareTo));
    }

    private static void sortSchedules(List<IndexExportSchedule> schedules) {
        schedules.sort(scheduleOrder());
    }

    private static NoSuchElementException notFound(Long id) {
        return new NoSuchElementException("找不到排程 " + id);
    }

    private static String normalizeName(String name) {
        if (name == null) return null;
        String trimmed = name.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String validatedStoredName(String name) {
        String normalized = normalizeName(name);
        if (normalized != null && !SCHEDULE_NAME.matcher(normalized).matches()) {
            throw new IllegalArgumentException("資料庫排程名稱不合法，已停止匯出");
        }
        return normalized;
    }

    private String normalizeSubpath(String subpath) {
        String value = subpath == null ? "" : subpath.trim();
        return value.isEmpty() ? "input" : value;
    }

    private Path resolveDir(String subpath) {
        try {
            Path base = Path.of(baseDir).toAbsolutePath().normalize();
            Path target = base.resolve(subpath).normalize();
            if (!target.startsWith(base)) {
                throw new IllegalArgumentException("輸出子路徑不可跳脫基底目錄：" + subpath);
            }
            Path canonicalBase = LocalExportPathComparator.resolveExistingAliases(base);
            Path canonicalTarget = LocalExportPathComparator.resolveExistingAliases(target);
            if (!canonicalTarget.startsWith(canonicalBase)) {
                throw new IllegalArgumentException("輸出子路徑不可跳脫基底目錄：" + subpath);
            }
            return canonicalTarget;
        } catch (InvalidPathException exception) {
            throw new IllegalArgumentException("輸出子路徑不合法：" + subpath, exception);
        } catch (IOException | SecurityException exception) {
            throw new IllegalArgumentException("輸出子路徑無法解析：" + subpath, exception);
        }
    }

    private Long requireOwnerId() {
        CurrentUserContext user = currentUserProvider.getObject();
        if (!user.hasUser()) throw new UnauthenticatedException("未識別使用者，無法存取排程設定");
        return user.getEffectiveUserId();
    }

    private record ScheduleDraft(String name, Boolean enabled, Integer runHour, Integer runMinute,
                                 List<String> markets, Integer rangeMonths, String outputSubpath) {}
}
