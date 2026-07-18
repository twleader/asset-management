package com.steven.assets.service;

import com.steven.assets.dto.ExchangeRateExportDto;
import com.steven.assets.model.ExchangeRateExportSchedule;
import com.steven.assets.repository.ExchangeRateExportScheduleRepository;
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
 * 台幣兌美元匯率每日排程自動匯出（Requirement 42 / Task 204）。
 *
 * <p>每個使用者可各自設定啟用開關、每日執行時分、輸出相對子路徑與匯出範圍。因 {@code @Scheduled} 的 cron
 * 於啟動期固定、無法吃 DB 可調時間，改採「每分鐘 poll ＋ 當日 guard ＋ 開機自癒補跑」
 * （比照 {@link ExportScheduleService}／{@link CommodityExportScheduleService}）。
 *
 * <p><b>租戶隔離</b>：GET/PUT/run-now 走 HTTP（BFF→business），由 {@code TenantFilterAspect} 自動 owner-scoped 到本人；
 * 背景 poll 無 request context → {@code ownerFilter} 不啟用，{@code findAll()} 讀全部 owner 列。
 * 但<b>產檔本身不需縮 owner</b>：{@code exchange_rate_history} 為全域公開資料（無 owner 欄位、無 {@code @Filter}），
 * 人人看到的匯率相同，故直接呼叫 {@link ExcelExportService#exportExchangeRates} 即可
 * （同 {@link CommodityExportScheduleService}，與 per-user 的 {@link RealizedGainExportScheduleService} 相反）。
 *
 * <p><b>幣別固定 USD</b>：本頁為「台幣兌美元」單一幣別頁，設定表不設 currency 欄，以 {@link #CURRENCY} 常數產檔。
 *
 * <p><b>滾動區間</b>：{@code rangeMonths} 為 null 時匯出全部十年，否則以「執行當日往前推 N 個月」計算起訖，
 * 使每日留存的檔案跟著時間滾動，而非固定區間。
 *
 * <p>路徑安全：使用者只設定「相對子路徑」，實際寫入 = 容器基底 {@code EXPORT_OUTPUT_DIR} resolve 子路徑，
 * 並驗證 normalize 後仍在基底內（拒 {@code ..}／絕對路徑跳脫）。
 *
 * <p>資料夾瀏覽不在此服務：沿用 Requirement 34 既有的 {@code GET /api/export-schedule/browse}，
 * 避免同義能力在 business 端出現第五份實作。
 */
@Service
@Slf4j
public class ExchangeRateExportScheduleService {

    private static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 匯出範圍上限（月）：十年，與 exchange_rate_history 的保留視窗一致。 */
    private static final int MAX_RANGE_MONTHS = 120;

    /** 本頁為單一幣別頁（台幣兌美元），排程不暴露幣別維度。 */
    private static final String CURRENCY = "USD";

    private final ExchangeRateExportScheduleRepository settingRepo;
    private final ExcelExportService excelExportService;
    private final ObjectProvider<CurrentUserContext> currentUserProvider;

    /** 容器內基底輸出目錄，經 docker volume 對映到 host（見 docker-compose.yml）。 */
    private final String baseDir;

    /** 避免每分鐘 poll 在上一輪尚未跑完時重入。 */
    private final AtomicBoolean ticking = new AtomicBoolean(false);

    public ExchangeRateExportScheduleService(ExchangeRateExportScheduleRepository settingRepo,
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
    public ExchangeRateExportDto.SettingResponse getForCurrentUser() {
        Long ownerId = requireOwnerId();
        ExchangeRateExportSchedule s = settingRepo.findByOwnerUserId(ownerId).orElseGet(() ->
                ExchangeRateExportSchedule.builder().ownerUserId(ownerId).build());
        return toResponse(s);
    }

    /** upsert 當前使用者設定。 */
    public ExchangeRateExportDto.SettingResponse updateForCurrentUser(ExchangeRateExportDto.SettingRequest req) {
        Long ownerId = requireOwnerId();
        int hour = req.runHour() == null ? 8 : req.runHour();
        int minute = req.runMinute() == null ? 0 : req.runMinute();
        if (hour < 0 || hour > 23) throw new IllegalArgumentException("執行時(hour)必須介於 0～23");
        if (minute < 0 || minute > 59) throw new IllegalArgumentException("執行分(minute)必須介於 0～59");
        Integer rangeMonths = req.rangeMonths();
        if (rangeMonths != null && (rangeMonths < 1 || rangeMonths > MAX_RANGE_MONTHS)) {
            throw new IllegalArgumentException("匯出範圍(月)必須介於 1～" + MAX_RANGE_MONTHS + "，或留空代表全部十年");
        }
        String subpath = normalizeSubpath(req.outputSubpath());
        resolveDir(subpath); // 驗證不跳脫基底（丟出即擋下）

        ExchangeRateExportSchedule s = settingRepo.findByOwnerUserId(ownerId).orElseGet(() ->
                ExchangeRateExportSchedule.builder().ownerUserId(ownerId).build());
        s.setOwnerUserId(ownerId);
        s.setEnabled(Boolean.TRUE.equals(req.enabled()));
        s.setRunHour(hour);
        s.setRunMinute(minute);
        s.setOutputSubpath(subpath);
        s.setRangeMonths(rangeMonths);
        s.setUpdatedAt(LocalDateTime.now(TW_ZONE));
        return toResponse(settingRepo.save(s));
    }

    /** 立即產檔寫入當前使用者設定的目錄（供驗證路徑正確）。不動當日 guard。 */
    public ExchangeRateExportDto.RunNowResponse runNowForCurrentUser() {
        Long ownerId = requireOwnerId();
        ExchangeRateExportSchedule s = settingRepo.findByOwnerUserId(ownerId).orElseGet(() ->
                ExchangeRateExportSchedule.builder().ownerUserId(ownerId).build());
        try {
            Path file = export(s, ownerId);
            long size = Files.size(file);
            s.setOwnerUserId(ownerId);
            s.setLastRunAt(LocalDateTime.now(TW_ZONE));
            s.setLastRunStatus("成功：" + file);
            settingRepo.save(s);
            return ExchangeRateExportDto.RunNowResponse.builder()
                    .path(file.toString())
                    .sizeBytes(size)
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
            log.debug("上一輪台幣兌美元排程匯出尚未結束，跳過本次 tick");
            return;
        }
        try {
            runDueExports();
        } catch (RuntimeException e) {
            log.warn("台幣兌美元排程匯出 tick 發生例外：{}", e.getMessage(), e);
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
            log.warn("台幣兌美元排程匯出開機自癒失敗：{}", e.getMessage(), e);
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
        for (ExchangeRateExportSchedule s : settingRepo.findAll()) {
            if (!Boolean.TRUE.equals(s.getEnabled())) continue;
            if (today.equals(s.getLastRunDate())) continue;
            if (!now.isBefore(LocalTime.of(s.getRunHour(), s.getRunMinute()))) {
                runScheduled(s, today);
            }
        }
    }

    /** 背景：對指定設定產檔並更新 guard／狀態。單一使用者失敗只記錄、不影響其他人。 */
    private void runScheduled(ExchangeRateExportSchedule s, LocalDate today) {
        try {
            Path file = export(s, s.getOwnerUserId());
            s.setLastRunStatus("成功：" + file);
            log.info("台幣兌美元排程匯出成功 owner={} → {}", s.getOwnerUserId(), file);
        } catch (Exception e) {
            s.setLastRunStatus("失敗：" + e.getMessage());
            log.warn("台幣兌美元排程匯出失敗 owner={}：{}", s.getOwnerUserId(), e.getMessage(), e);
        } finally {
            // 成功或失敗都設 guard，避免命中分鐘後每 poll 重試整天。
            s.setLastRunDate(today);
            s.setLastRunAt(LocalDateTime.now(TW_ZONE));
            settingRepo.save(s);
        }
    }

    // ===== 輔助 =====

    /**
     * 依設定的滾動區間產檔並寫入目錄，回傳實際落點。
     * 與手動匯出走同一支 {@code exportExchangeRates}，確保兩種途徑內容一致。
     */
    private Path export(ExchangeRateExportSchedule s, Long ownerId) throws IOException {
        LocalDate end = LocalDate.now(TW_ZONE);
        Integer months = s.getRangeMonths();
        LocalDate start = months == null ? end.minusYears(10) : end.minusMonths(months);
        byte[] data = excelExportService.exportExchangeRates(CURRENCY, start, end);
        String filename = ExcelExportService.exchangeRateLabel(CURRENCY)
                + "_" + ownerId + "_" + end.format(FILE_DATE) + ".xlsx";
        return writeAtomically(normalizeSubpath(s.getOutputSubpath()), filename, data);
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

    private ExchangeRateExportDto.SettingResponse toResponse(ExchangeRateExportSchedule s) {
        return ExchangeRateExportDto.SettingResponse.builder()
                .enabled(Boolean.TRUE.equals(s.getEnabled()))
                .runHour(s.getRunHour())
                .runMinute(s.getRunMinute())
                .outputSubpath(s.getOutputSubpath())
                .rangeMonths(s.getRangeMonths())
                .lastRunAt(s.getLastRunAt() == null ? null : s.getLastRunAt().format(TS_FMT))
                .lastRunStatus(s.getLastRunStatus())
                .baseDir(baseDir)
                .build();
    }
}
