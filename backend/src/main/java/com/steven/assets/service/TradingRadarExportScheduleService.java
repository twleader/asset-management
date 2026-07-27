package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarExportDto;
import com.steven.assets.model.TradingRadarExportSetting;
import com.steven.assets.model.TradingRadarExportTime;
import com.steven.assets.repository.TradingRadarExportSettingRepository;
import com.steven.assets.repository.TradingRadarExportTimeRepository;
import com.steven.assets.security.CurrentUserContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 交易雷達排程自動匯出到指定伺服器目錄（Requirement 48 追加 / Task 231）。
 *
 * <p>比照公開資訊爬蟲，使用者可設定<b>多個</b>每日執行時間點與一個輸出資料夾；到點把該 owner
 * <b>當日</b>的 Redis 快照產成 Excel 寫入該資料夾（檔名 {@code 交易雷達_{ownerId}_{yyyyMMdd}.xlsx}，
 * 同日覆寫、跨日新檔）。與手動匯出共用 {@link TradingRadarExportService} 同一支產檔邏輯。
 *
 * <p><b>當日 guard 放在時間點列</b>（{@code trading_radar_export_time.last_run_date}）而非 owner 層，
 * 否則同日設多個時間點只會跑第一個。
 *
 * <p><b>背景無 request context</b> → {@code ownerFilter} 不啟用，{@code findAll()} 讀全部 owner 列；
 * owner 一律取自列上的 {@code ownerUserId}，不得觸碰 request-scoped 的 {@link CurrentUserContext}。
 *
 * <p>路徑安全：使用者只設定「相對子路徑」，實際寫入 = 容器基底 {@code EXPORT_OUTPUT_DIR} resolve 子路徑，
 * 並驗證 normalize 後仍在基底內（拒 {@code ..}／絕對路徑跳脫）。
 */
@Slf4j
@Service
public class TradingRadarExportScheduleService {

    private static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    /** 當日查無快照時的狀態文字（快照只在使用者開頁的 HTTP 路徑產生，背景不產生）。 */
    static final String NO_SNAPSHOT_STATUS = "當日尚無快照，未產檔";

    private final TradingRadarExportTimeRepository timeRepo;
    private final TradingRadarExportSettingRepository settingRepo;
    private final TradingRadarExportService exportService;
    private final TradingRadarSnapshotStore snapshotStore;
    private final ObjectProvider<CurrentUserContext> currentUserProvider;
    private final GdriveOutputSupport gdrive;
    private final String baseDir;

    private final AtomicBoolean ticking = new AtomicBoolean(false);

    public TradingRadarExportScheduleService(TradingRadarExportTimeRepository timeRepo,
                                            TradingRadarExportSettingRepository settingRepo,
                                            TradingRadarExportService exportService,
                                            TradingRadarSnapshotStore snapshotStore,
                                            ObjectProvider<CurrentUserContext> currentUserProvider,
                                            GdriveOutputSupport gdrive,
                                            @Value("${EXPORT_OUTPUT_DIR:/home/steven}") String baseDir) {
        this.timeRepo = timeRepo;
        this.settingRepo = settingRepo;
        this.exportService = exportService;
        this.snapshotStore = snapshotStore;
        this.currentUserProvider = currentUserProvider;
        this.gdrive = gdrive;
        this.baseDir = baseDir;
    }

    // ===== 設定 CRUD（HTTP 路徑）=====

    @Transactional(readOnly = true)
    public List<TradingRadarExportDto.TimeItem> listTimes() {
        return timeRepo.findAllByOwnerUserIdOrderByRunHourAscRunMinuteAsc(requireOwnerId()).stream()
                .map(t -> new TradingRadarExportDto.TimeItem(
                        t.getRunHour(), t.getRunMinute(), Boolean.TRUE.equals(t.getEnabled())))
                .toList();
    }

    /**
     * 整批覆寫執行時間點。保留既有相同時分列的 {@code lastRunDate}，避免使用者一存檔就讓當日 guard 消失而重跑。
     * 重複時分先擋下丟 {@link IllegalArgumentException}（→400），否則會撞 UNIQUE 而變成 500。
     */
    @Transactional
    public List<TradingRadarExportDto.TimeItem> replaceTimes(List<TradingRadarExportDto.TimeItem> items) {
        long ownerId = requireOwnerId();
        List<TradingRadarExportDto.TimeItem> list = items == null ? List.of() : items;

        Set<String> seen = new HashSet<>();
        for (TradingRadarExportDto.TimeItem i : list) {
            int h = i.runHour() == null ? -1 : i.runHour();
            int m = i.runMinute() == null ? -1 : i.runMinute();
            if (h < 0 || h > 23 || m < 0 || m > 59) {
                throw new IllegalArgumentException("執行時間不合法：" + i.runHour() + ":" + i.runMinute());
            }
            if (!seen.add(h + ":" + m)) {
                throw new IllegalArgumentException("執行時間重複：" + String.format("%02d:%02d", h, m));
            }
        }

        List<TradingRadarExportTime> existing =
                timeRepo.findAllByOwnerUserIdOrderByRunHourAscRunMinuteAsc(ownerId);
        Map<String, LocalDate> guards = new HashMap<>();
        for (TradingRadarExportTime e : existing) {
            guards.put(e.getRunHour() + ":" + e.getRunMinute(), e.getLastRunDate());
        }
        timeRepo.deleteAll(existing);
        timeRepo.flush();   // 先讓 DELETE 落地，避免同交易內與新 INSERT 撞 UNIQUE

        LocalDateTime now = LocalDateTime.now(TW_ZONE);
        List<TradingRadarExportTime> saved = new ArrayList<>();
        for (TradingRadarExportDto.TimeItem i : list) {
            saved.add(TradingRadarExportTime.builder()
                    .ownerUserId(ownerId)
                    .runHour(i.runHour())
                    .runMinute(i.runMinute())
                    .enabled(i.enabled() == null || i.enabled())
                    .lastRunDate(guards.get(i.runHour() + ":" + i.runMinute()))
                    .updatedAt(now)
                    .build());
        }
        timeRepo.saveAll(saved);
        return saved.stream()
                .map(t -> new TradingRadarExportDto.TimeItem(
                        t.getRunHour(), t.getRunMinute(), Boolean.TRUE.equals(t.getEnabled())))
                .toList();
    }

    @Transactional(readOnly = true)
    public TradingRadarExportDto.SettingResponse getSetting() {
        long ownerId = requireOwnerId();
        TradingRadarExportSetting s = settingRepo.findByOwnerUserId(ownerId).orElse(null);
        String sub = (s == null) ? TradingRadarExportSetting.DEFAULT_SUBPATH : s.getOutputSubpath();
        return toSettingResponse(ownerId, sub, s);
    }

    /**
     * 儲存輸出資料夾與 Drive 同步設定。
     *
     * <p><b>收整個 request 而非裸字串</b>：原簽章是 {@code saveSetting(String outputSubpath)}，
     * 新增的 Drive 兩欄在那個形狀下會被靜默丟掉（Task 244.1）。
     */
    @Transactional
    public TradingRadarExportDto.SettingResponse saveSetting(TradingRadarExportDto.SettingRequest req) {
        long ownerId = requireOwnerId();
        String sub = normalizeSubpath(req == null ? null : req.outputSubpath());
        resolveDir(sub);    // 驗證跳脫，非法即 IllegalArgumentException → 400
        TradingRadarExportSetting s = settingRepo.findByOwnerUserId(ownerId)
                .orElseGet(() -> TradingRadarExportSetting.builder().ownerUserId(ownerId).build());
        // Drive 兩欄一律走共用元件：null 解析（未送出＝不變更）、驗證、啟用時必填、只有主要管理者能啟用（403）。
        GdriveOutputSupport.DriveSettings drive = gdrive.resolveUpdate(
                ownerId,
                req == null ? null : req.gdriveEnabled(),
                req == null ? null : req.gdriveSubpath(),
                s.isGdriveEnabled(), s.getGdriveSubpath());

        s.setOutputSubpath(sub);
        s.setGdriveEnabled(drive.enabled());
        s.setGdriveSubpath(drive.subpath());
        // 刻意不碰 gdriveLastRunAt／gdriveLastStatus：那是執行結果，不是使用者設定。
        s.setUpdatedAt(LocalDateTime.now(TW_ZONE));
        settingRepo.save(s);
        return toSettingResponse(ownerId, sub, s);
    }

    /** 立即匯出到目錄（驗證用）。走與排程同一支寫檔邏輯，且**不動任何時間點的當日 guard**。 */
    @Transactional
    public TradingRadarExportDto.RunNowResponse runNow() {
        long ownerId = requireOwnerId();
        LocalDate today = LocalDate.now(TW_ZONE);
        try {
            Path file = writeDailyExport(ownerId, today);
            if (file == null) {
                recordStatus(ownerId, NO_SNAPSHOT_STATUS);
                // 沒產檔就完全不上傳（絕不上傳前一次的舊檔），但已啟用時仍寫狀態欄說明原因，
                // 否則狀態會停在上一次的成功、顯示過期的好消息。
                GdriveOutputSupport.SyncResult skipped = syncGdrive(ownerId, null, "當日無交易雷達快照");
                return new TradingRadarExportDto.RunNowResponse(
                        null, 0L, NO_SNAPSHOT_STATUS + "（請先開啟一次交易雷達頁產生快照）",
                        null, skipped == null ? null : skipped.status());
            }
            long size = Files.size(file);
            recordStatus(ownerId, "成功：" + file);
            // 本機寫成功後才上傳；run-now 的用途就是驗證落點正確，故它也要上傳並回報。
            GdriveOutputSupport.SyncResult drive = syncGdrive(ownerId, file, null);
            return new TradingRadarExportDto.RunNowResponse(file.toString(), size, "匯出完成",
                    drive == null ? null : drive.path(),
                    drive == null ? null : drive.status());
        } catch (IOException e) {
            recordStatus(ownerId, "失敗：" + e.getMessage());
            syncGdrive(ownerId, null, "本機匯出失敗");
            throw new IllegalStateException("匯出失敗：" + e.getMessage(), e);
        }
    }

    // ===== 背景排程 =====

    /** 每分鐘檢查各使用者的各個時間點，命中執行時間且當日未跑者即產檔寫入其設定目錄。 */
    @Scheduled(cron = "0 * * * * *", zone = "Asia/Taipei")
    public void tick() {
        if (!ticking.compareAndSet(false, true)) {
            log.debug("上一輪交易雷達排程匯出尚未結束，跳過本次 tick");
            return;
        }
        try {
            runDueExports();
        } catch (RuntimeException e) {
            log.warn("交易雷達排程匯出 tick 發生例外：{}", e.getMessage(), e);
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
            log.warn("交易雷達排程匯出開機自癒失敗：{}", e.getMessage(), e);
        }
    }

    /**
     * 掃所有啟用中的時間點，對「今日尚未執行且排程時間已到」者產檔。
     *
     * <p>用 {@code now >= 排程時間}（而非「分鐘精確相等」）＋該時間點的 {@code lastRunDate} 當日 guard：
     * 排程執行緒被長工作卡住而跨越分鐘時，後續 tick 會自動補跑，避免整日靜默漏跑。
     */
    private void runDueExports() {
        LocalDate today = LocalDate.now(TW_ZONE);
        LocalTime now = LocalTime.now(TW_ZONE);
        // 背景無 request context → ownerFilter 不啟用，讀全部 owner 的時間點列
        for (TradingRadarExportTime t : timeRepo.findAll()) {
            if (!Boolean.TRUE.equals(t.getEnabled())) continue;
            if (today.equals(t.getLastRunDate())) continue;
            if (!now.isBefore(LocalTime.of(t.getRunHour(), t.getRunMinute()))) {
                runScheduled(t, today);
            }
        }
    }

    /** 背景：對指定時間點產檔並更新 guard／狀態。單一使用者失敗只記錄、不影響其他人。 */
    private void runScheduled(TradingRadarExportTime t, LocalDate today) {
        long ownerId = t.getOwnerUserId();
        try {
            Path file = writeDailyExport(ownerId, today);
            if (file == null) {
                recordStatus(ownerId, NO_SNAPSHOT_STATUS);
                syncGdrive(ownerId, null, "當日無交易雷達快照");
                log.info("交易雷達排程匯出略過（當日無快照）owner={} {}:{}",
                        ownerId, t.getRunHour(), t.getRunMinute());
            } else {
                recordStatus(ownerId, "成功：" + file);
                syncGdrive(ownerId, file, null);
                log.info("交易雷達排程匯出成功 owner={} {}:{} → {}",
                        ownerId, t.getRunHour(), t.getRunMinute(), file);
            }
        } catch (Exception e) {
            recordStatus(ownerId, "失敗：" + e.getMessage());
            syncGdrive(ownerId, null, "本機匯出失敗");
            log.warn("交易雷達排程匯出失敗 owner={} {}:{}：{}",
                    ownerId, t.getRunHour(), t.getRunMinute(), e.getMessage(), e);
        } finally {
            // 成功、略過或失敗都設 guard，避免命中分鐘後每 poll 重試整天
            t.setLastRunDate(today);
            t.setUpdatedAt(LocalDateTime.now(TW_ZONE));
            timeRepo.save(t);
        }
    }

    // ===== 輔助 =====

    /**
     * 產出該 owner 當日（00:00～當下）快照的 Excel 並寫入其設定目錄，回傳實際落點；
     * <b>當日查無快照時回 {@code null} 且不寫檔</b>——快照只在使用者開啟／刷新雷達頁的 HTTP 路徑產生，
     * 背景不產生；若照寫會每天在使用者目錄留下只有表頭的無用檔，並蓋掉同名前一版。
     */
    private Path writeDailyExport(long ownerId, LocalDate today) throws IOException {
        long fromEpoch = today.atStartOfDay(TW_ZONE).toInstant().toEpochMilli();
        long toEpoch = ZonedDateTime.now(TW_ZONE).toInstant().toEpochMilli();

        if (snapshotStore.range(ownerId, fromEpoch, toEpoch).snapshots().isEmpty()) {
            return null;
        }

        byte[] data = exportService.exportForOwner(ownerId, fromEpoch, toEpoch);
        String filename = "交易雷達_" + ownerId + "_" + today.format(FILE_DATE) + ".xlsx";
        return writeAtomically(currentSubpath(ownerId), filename, data);
    }

    private String currentSubpath(long ownerId) {
        return settingRepo.findByOwnerUserId(ownerId)
                .map(TradingRadarExportSetting::getOutputSubpath)
                .map(TradingRadarExportScheduleService::normalizeSubpath)
                .orElse(TradingRadarExportSetting.DEFAULT_SUBPATH);
    }

    /**
     * 寫入上次執行時間與結果。使用者可能「只設時間點、未設資料夾」→ 設定列不存在，故須自行 upsert 建列，
     * 不可假設該列必定存在（{@code IndexExportScheduleService} 是單表、設定列必存在，那段不能直接照抄）。
     */
    private void recordStatus(long ownerId, String status) {
        TradingRadarExportSetting s = settingRepo.findByOwnerUserId(ownerId)
                .orElseGet(() -> TradingRadarExportSetting.builder()
                        .ownerUserId(ownerId)
                        .outputSubpath(TradingRadarExportSetting.DEFAULT_SUBPATH)
                        .build());
        s.setLastRunAt(LocalDateTime.now(TW_ZONE));
        s.setLastRunStatus(status);
        settingRepo.save(s);
    }

    /**
     * 本機檔寫成功後的 Drive 同步（best-effort，Requirement 51 / Task 244）。
     *
     * <p><b>順序不可顛倒</b>：本機那一份是既有的留存機制，必須先確定它寫成功才上傳；本機失敗或當日無快照
     * 時傳 {@code localFile == null} ——完全不上傳，絕不上傳前一次的舊檔，但仍寫狀態欄說明原因
     * （否則狀態會停在上一次的成功、顯示過期的好消息）。
     *
     * <p>不擲例外、不 rollback 本機檔、不改既有 {@code lastRunStatus}——「本機成功、Drive 失敗」是正常
     * 且必須可分辨的狀態。owner 權限在 {@code syncQuietly} 內<b>每一輪重驗</b>（背景排程沒有 request
     * context，{@code PUT} 當下的檢查在此不適用）。
     *
     * <p><b>本頁一天可能上傳多次</b>：執行時間點存於 {@code trading_radar_export_time}（一列一時間點），
     * 與其餘七頁「每日單一時間」不同，故狀態欄是「最後一次」語意。
     *
     * @param skipReason {@code localFile} 為 null 時寫入狀態欄的原因
     * @return 未啟用（或設定列不存在）時回 {@code null}；否則為本輪結果，供 run-now 回報落點
     */
    private GdriveOutputSupport.SyncResult syncGdrive(long ownerId, Path localFile, String skipReason) {
        try {
            TradingRadarExportSetting s = settingRepo.findByOwnerUserId(ownerId).orElse(null);
            if (s == null || !s.isGdriveEnabled()) return null;
            GdriveOutputSupport.SyncResult r = localFile == null
                    ? new GdriveOutputSupport.SyncResult(gdrive.skipped(skipReason), null)
                    : gdrive.syncQuietly(ownerId, s.getGdriveSubpath(), localFile);
            s.setGdriveLastRunAt(LocalDateTime.now(TW_ZONE));
            s.setGdriveLastStatus(r.status());
            settingRepo.save(s);
            return r;
        } catch (RuntimeException e) {
            // 「回報結果」這件事本身不能成為新的失敗來源（DB 短暫不可用時 save 會擲例外）。
            log.error("寫入 Drive 同步狀態失敗 owner={}：{}", ownerId, e.getMessage(), e);
            return null;
        }
    }

    private long requireOwnerId() {
        CurrentUserContext ctx = currentUserProvider.getObject();
        if (!ctx.hasUser()) {
            throw new IllegalArgumentException("未識別使用者，無法存取排程設定");
        }
        return ctx.getEffectiveUserId();
    }

    private static String normalizeSubpath(String subpath) {
        String sub = subpath == null ? "" : subpath.trim();
        return sub.isEmpty() ? TradingRadarExportSetting.DEFAULT_SUBPATH : sub;
    }

    /** 基底 resolve 子路徑並驗證仍在基底內（拒 {@code ..}／絕對路徑跳脫）。 */
    private Path resolveDir(String subpath) {
        Path base = Path.of(baseDir).toAbsolutePath().normalize();
        Path target = base.resolve(subpath).normalize();
        if (!target.startsWith(base)) {
            throw new IllegalArgumentException("輸出子路徑不可跳脫基底目錄：" + subpath);
        }
        return target;
    }

    /**
     * 先寫 {@code .tmp} 再 atomic move：避免覆寫既有檔時中途失敗留下半截殘檔。
     * tmp 檔名帶唯一後綴——{@code selfHealOnStartup} 與 {@code runNow} 都不走 {@code ticking} 旗標，
     * 同一 owner 同一日可能有兩條路徑併發寫檔；固定 tmp 名會讓後者 {@code Files.move} 撞 NoSuchFileException。
     */
    private Path writeAtomically(String subpath, String filename, byte[] data) throws IOException {
        Path dir = resolveDir(subpath);
        Files.createDirectories(dir);
        Path file = dir.resolve(filename);
        Path tmp = dir.resolve(filename + "." + UUID.randomUUID() + ".tmp");
        Files.write(tmp, data);
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
        return file;
    }

    private TradingRadarExportDto.SettingResponse toSettingResponse(
            long ownerId, String subpath, TradingRadarExportSetting s) {
        return new TradingRadarExportDto.SettingResponse(
                subpath,
                resolveDir(subpath).toString(),
                "交易雷達_" + ownerId + "_{YYYYMMDD}.xlsx",
                s == null || s.getLastRunAt() == null ? null : s.getLastRunAt().toString(),
                s == null ? null : s.getLastRunStatus(),
                // 讀取一律不驗證 Drive 子路徑：DB 值可能被繞過 API 直改，若讀取也擲例外，設定頁會 500
                // 而使用者沒有任何入口能把它改回正常值——唯一的修正入口被自己鎖死。存檔時才驗。
                s != null && s.isGdriveEnabled(),
                s == null ? null : s.getGdriveSubpath(),
                gdrive.remoteName(),
                s == null || s.getGdriveLastRunAt() == null ? null : s.getGdriveLastRunAt().toString(),
                s == null ? null : s.getGdriveLastStatus());
    }
}
