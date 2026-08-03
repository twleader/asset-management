package com.steven.assets.service;

import com.steven.assets.dto.IndexExportDto;
import com.steven.assets.model.IndexExportSchedule;
import com.steven.assets.model.IndexExportScheduleTime;
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
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 大盤指數日線匯出：每位 owner 共用設定、多時間點、每時間點多指數。 */
@Service @Slf4j
public class IndexExportScheduleService {
    private static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final int MAX_RANGE_MONTHS = 120;
    private static final String DEFAULT_MARKET = "TWSE";

    private final IndexExportScheduleRepository settingRepo;
    private final ExcelExportService excelExportService;
    private final ObjectProvider<CurrentUserContext> currentUserProvider;
    private final GdriveOutputSupport gdrive;
    private final com.steven.assets.service.export.ExcelDocRenderer excelDocRenderer;
    private final com.steven.assets.service.export.JsonDocRenderer jsonDocRenderer;
    private final com.steven.assets.service.export.DualFormatExportWriter dualWriter;
    private final String baseDir;
    private final AtomicBoolean ticking = new AtomicBoolean(false);

    public IndexExportScheduleService(IndexExportScheduleRepository settingRepo,
                                      ExcelExportService excelExportService,
                                      ObjectProvider<CurrentUserContext> currentUserProvider,
                                      GdriveOutputSupport gdrive,
                                      com.steven.assets.service.export.ExcelDocRenderer excelDocRenderer,
                                      com.steven.assets.service.export.JsonDocRenderer jsonDocRenderer,
                                      com.steven.assets.service.export.DualFormatExportWriter dualWriter,
                                      @Value("${EXPORT_OUTPUT_DIR:/home/steven}") String baseDir) {
        this.settingRepo = settingRepo; this.excelExportService = excelExportService;
        this.currentUserProvider = currentUserProvider; this.gdrive = gdrive;
        this.excelDocRenderer = excelDocRenderer; this.jsonDocRenderer = jsonDocRenderer;
        this.dualWriter = dualWriter; this.baseDir = baseDir;
    }

    @Transactional(readOnly = true)
    public IndexExportDto.SettingResponse getForCurrentUser() {
        Long owner = requireOwnerId();
        return toResponse(settingRepo.findByOwnerUserId(owner).orElseGet(() -> defaultSchedule(owner)));
    }

    @Transactional
    public IndexExportDto.SettingResponse updateForCurrentUser(IndexExportDto.SettingRequest req) {
        Long owner = requireOwnerId();
        Integer range = req.rangeMonths();
        if (range != null && (range < 1 || range > MAX_RANGE_MONTHS))
            throw new IllegalArgumentException("匯出範圍(月)必須介於 1～" + MAX_RANGE_MONTHS + "，或留空代表全部十年");
        String subpath = normalizeSubpath(req.outputSubpath());
        resolveDir(subpath);
        List<IndexExportDto.TimeRequest> requests = req.times() == null ? List.of() : req.times();
        Map<String, IndexExportDto.TimeRequest> normalized = new LinkedHashMap<>();
        for (IndexExportDto.TimeRequest t : requests) {
            int h = t.runHour() == null ? 8 : t.runHour();
            int m = t.runMinute() == null ? 0 : t.runMinute();
            if (h < 0 || h > 23 || m < 0 || m > 59) throw new IllegalArgumentException("時間必須介於 00:00～23:59");
            String key = h + ":" + m;
            if (normalized.putIfAbsent(key, new IndexExportDto.TimeRequest(h, m, t.enabled(), normalizeMarkets(t.markets()))) != null)
                throw new IllegalArgumentException("時間點不可重複：" + String.format("%02d:%02d", h, m));
        }
        IndexExportSchedule s = settingRepo.findByOwnerUserId(owner).orElseGet(() -> IndexExportSchedule.builder().ownerUserId(owner).build());
        GdriveOutputSupport.DriveSettings drive = gdrive.resolveUpdate(owner, req.gdriveEnabled(), req.gdriveSubpath(), s.isGdriveEnabled(), s.getGdriveSubpath());
        Map<String, IndexExportScheduleTime> old = s.getTimes().stream().collect(Collectors.toMap(this::timeKey, Function.identity(), (a,b) -> a));
        List<IndexExportScheduleTime> next = new ArrayList<>();
        for (IndexExportDto.TimeRequest t : normalized.values()) {
            String key = t.runHour() + ":" + t.runMinute();
            IndexExportScheduleTime child = old.get(key);
            if (child == null) child = IndexExportScheduleTime.builder().runHour(t.runHour()).runMinute(t.runMinute()).build();
            child.setEnabled(!Boolean.FALSE.equals(t.enabled()));
            child.setMarkets(new LinkedHashSet<>(t.markets()));
            child.setSchedule(s); child.setUpdatedAt(LocalDateTime.now(TW_ZONE));
            next.add(child);
        }
        s.getTimes().clear();
        next.forEach(s::addTime);
        s.setOwnerUserId(owner); s.setEnabled(Boolean.TRUE.equals(req.enabled()));
        s.setOutputSubpath(subpath); s.setRangeMonths(range);
        s.setGdriveEnabled(drive.enabled()); s.setGdriveSubpath(drive.subpath()); s.setUpdatedAt(LocalDateTime.now(TW_ZONE));
        return toResponse(settingRepo.save(s), drive.selfCheckWarning());
    }

    /** 未儲存設定沿用 transient 預設，但不可因立即匯出把它寫回 DB。 */
    @Transactional
    public IndexExportDto.RunNowResponse runNowForCurrentUser() {
        Long owner = requireOwnerId();
        Optional<IndexExportSchedule> found = settingRepo.findByOwnerUserId(owner);
        boolean transientDefault = found.isEmpty();
        IndexExportSchedule s = found.orElseGet(() -> defaultSchedule(owner));
        List<String> markets = s.getTimes().stream().flatMap(t -> t.getMarkets().stream())
                .map(this::normalizeMarket).distinct().toList();
        if (markets.isEmpty()) throw new IllegalArgumentException("至少選擇一個匯出指數");
        List<IndexExportDto.FileResult> files = new ArrayList<>();
        for (String market : markets) files.add(exportOne(s, owner, market));
        IndexExportDto.FileResult first = files.get(0);
        if (!transientDefault) {
            s.setGdriveLastRunAt(LocalDateTime.now(TW_ZONE)); s.setGdriveLastStatus(first.gdriveStatus());
            s.setUpdatedAt(LocalDateTime.now(TW_ZONE)); settingRepo.save(s);
        }
        return new IndexExportDto.RunNowResponse(first.path(), first.sizeBytes(), first.gdrivePath(), first.gdriveStatus(),
                first.jsonPath(), first.jsonSizeBytes(), first.jsonGdrivePath(), files);
    }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Taipei")
    public void tick() {
        if (!ticking.compareAndSet(false, true)) return;
        try { runDueExports(); } catch (RuntimeException e) { log.warn("大盤指數排程 tick 失敗：{}", e.getMessage(), e); }
        finally { ticking.set(false); }
    }

    @EventListener(ApplicationReadyEvent.class)
    public void selfHealOnStartup() { try { runDueExports(); } catch (RuntimeException e) { log.warn("大盤指數開機自癒失敗：{}", e.getMessage(), e); } }

    private void runDueExports() {
        LocalDate today = LocalDate.now(TW_ZONE); LocalTime now = LocalTime.now(TW_ZONE);
        for (IndexExportSchedule s : settingRepo.findAll()) {
            if (!Boolean.TRUE.equals(s.getEnabled())) continue;
            for (IndexExportScheduleTime t : new ArrayList<>(s.getTimes())) {
                if (!Boolean.TRUE.equals(t.getEnabled()) || today.equals(t.getLastRunDate())) continue;
                if (!now.isBefore(LocalTime.of(t.getRunHour(), t.getRunMinute()))) runScheduled(s, t, today);
            }
        }
    }

    private void runScheduled(IndexExportSchedule s, IndexExportScheduleTime t, LocalDate today) {
        List<String> markets = t.getMarkets().stream().map(this::normalizeMarket).distinct().toList();
        List<String> statuses = new ArrayList<>();
        for (String market : markets) {
            try {
                IndexExportDto.FileResult file = exportOne(s, s.getOwnerUserId(), market);
                statuses.add(file.path() != null && file.jsonPath() != null
                        ? "成功：" + file.path() + "／" + file.jsonPath()
                        : "部分失敗(" + market + ")：xlsx=" + file.path() + ", json=" + file.jsonPath());
            }
            catch (Exception e) { statuses.add("失敗(" + market + ")：" + e.getMessage()); log.warn("大盤指數排程 owner={} market={} 失敗", s.getOwnerUserId(), market, e); }
        }
        t.setLastRunDate(today); t.setLastRunAt(LocalDateTime.now(TW_ZONE));
        t.setLastRunStatus(statuses.isEmpty() ? "略過：未選指數" : statuses.stream().filter(Objects::nonNull).collect(Collectors.joining("；")));
        t.setUpdatedAt(LocalDateTime.now(TW_ZONE)); s.setUpdatedAt(LocalDateTime.now(TW_ZONE)); settingRepo.save(s);
    }

    private IndexExportDto.FileResult exportOne(IndexExportSchedule s, Long owner, String market) {
        try {
            LocalDate end = LocalDate.now(TW_ZONE);
            LocalDate start = s.getRangeMonths() == null ? end.minusYears(10) : end.minusMonths(s.getRangeMonths());
            var doc = excelExportService.indexDailyDoc(market, start, end);
            String baseName = ExcelExportService.indexLabel(market) + "_" + owner + "_" + end.format(FILE_DATE);
            var result = dualWriter.write(owner, resolveDir(normalizeSubpath(s.getOutputSubpath())), baseName,
                    renderJson(doc), renderExcel(doc), s.isGdriveEnabled(), s.getGdriveSubpath());
            return IndexExportDto.FileResult.builder().market(market).marketLabel(ExcelExportService.indexLabel(market))
                    .path(result.xlsxFile() == null ? null : result.xlsxFile().toString())
                    .sizeBytes(size(result.xlsxFile())).jsonPath(result.jsonFile() == null ? null : result.jsonFile().toString())
                    .jsonSizeBytes(size(result.jsonFile())).gdrivePath(result.xlsxGdrivePath())
                    .jsonGdrivePath(result.jsonGdrivePath()).gdriveStatus(result.gdriveStatus()).build();
        } catch (IOException e) { throw new RuntimeException("匯出 " + market + " 失敗：" + e.getMessage(), e); }
    }

    private byte[] renderExcel(com.steven.assets.service.export.ExportDoc doc) {
        try { return excelDocRenderer.render(doc); } catch (Exception e) { log.warn("xlsx render 失敗：{}", e.getMessage()); return null; }
    }
    private byte[] renderJson(com.steven.assets.service.export.ExportDoc doc) {
        try { return jsonDocRenderer.render(doc); } catch (Exception e) { log.warn("json render 失敗：{}", e.getMessage()); return null; }
    }
    private static long size(Path p) { try { return p == null ? 0 : Files.size(p); } catch (IOException e) { return 0; } }

    private IndexExportSchedule defaultSchedule(Long owner) {
        IndexExportSchedule s = IndexExportSchedule.builder().ownerUserId(owner).build();
        IndexExportScheduleTime t = IndexExportScheduleTime.builder().runHour(8).runMinute(0).markets(new LinkedHashSet<>(Set.of(DEFAULT_MARKET))).build();
        s.addTime(t); return s;
    }

    private List<String> normalizeMarkets(Collection<String> values) {
        if (values == null || values.isEmpty()) throw new IllegalArgumentException("每個時間點至少選擇一個指數");
        return values.stream().map(this::normalizeMarket).distinct().toList();
    }
    private String normalizeMarket(String market) {
        String m = market == null ? "" : market.trim().toUpperCase();
        if (!MacroHistoryService.DAILY_INDEX_CODES.contains(m)) throw new IllegalArgumentException("未知指數代碼: " + market);
        return m;
    }
    private String timeKey(IndexExportScheduleTime t) { return t.getRunHour() + ":" + t.getRunMinute(); }
    private String normalizeSubpath(String subpath) { String s = subpath == null ? "" : subpath.trim(); return s.isEmpty() ? "input" : s; }
    private Path resolveDir(String subpath) {
        Path base = Path.of(baseDir).toAbsolutePath().normalize(); Path target = base.resolve(subpath).normalize();
        if (!target.startsWith(base)) throw new IllegalArgumentException("輸出子路徑不可跳脫基底目錄：" + subpath); return target;
    }
    private Long requireOwnerId() { CurrentUserContext ctx = currentUserProvider.getObject(); if (!ctx.hasUser()) throw new UnauthenticatedException("未識別使用者，無法存取排程設定"); return ctx.getEffectiveUserId(); }

    private IndexExportDto.SettingResponse toResponse(IndexExportSchedule s) { return toResponse(s, null); }
    private IndexExportDto.SettingResponse toResponse(IndexExportSchedule s, String warning) {
        List<IndexExportDto.TimeItem> times = s.getTimes().stream().map(t -> IndexExportDto.TimeItem.builder().id(t.getId())
                .runHour(t.getRunHour()).runMinute(t.getRunMinute()).enabled(t.getEnabled())
                .markets(t.getMarkets().stream().sorted().toList())
                .lastRunAt(t.getLastRunAt() == null ? null : t.getLastRunAt().format(TS_FMT)).lastRunStatus(t.getLastRunStatus()).build()).toList();
        return IndexExportDto.SettingResponse.builder().enabled(Boolean.TRUE.equals(s.getEnabled())).outputSubpath(s.getOutputSubpath())
                .rangeMonths(s.getRangeMonths()).baseDir(baseDir).times(times).gdriveEnabled(s.isGdriveEnabled())
                .gdriveSubpath(s.getGdriveSubpath()).gdriveRemote(gdrive.remoteName())
                .gdriveLastRunAt(s.getGdriveLastRunAt() == null ? null : s.getGdriveLastRunAt().format(TS_FMT))
                .gdriveLastStatus(s.getGdriveLastStatus()).gdriveSelfCheckWarning(warning).build();
    }
}
