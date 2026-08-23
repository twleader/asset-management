package com.steven.assets.service;

import com.fasterxml.jackson.databind.JsonNode;
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
 * <p>比照公開資訊爬蟲，使用者可設定<b>多個</b>每日執行時間點與一個輸出資料夾；到點先回補台股即時行情、
 * 由背景重算一次雷達並寫入快照，再把該 owner<b>當日</b>的 Redis 快照產成 Excel 寫入該資料夾
 * （主檔名 {@code 交易雷達_{ownerId}_{yyyyMMdd}}，<b>同時產出 .xlsx 與 .json 兩份</b>，
 * 同日覆寫、跨日新檔；Task 260、Requirement 55）。
 * 與手動匯出共用 {@link TradingRadarExportService} 同一支產檔邏輯。
 *
 * <p><b>當日 guard 放在時間點列</b>（{@code trading_radar_export_time.last_run_date}）而非 owner 層，
 * 否則同日設多個時間點只會跑第一個。
 *
 * <p><b>背景無 request context</b> → {@code ownerFilter} 不啟用，{@code findAll()} 讀全部 owner 列；
 * owner 一律取自列上的 {@code ownerUserId}，不得觸碰 request-scoped 的 {@link CurrentUserContext}。
 * 產檔前的回補與重算同理：一律用 {@link PriceQueryService#refreshTradingRadarPrices()}／
 * {@link TradingRadarService#recomputeAndStoreForOwner(long)} 的顯式 owner 版本，不得經由
 * request-scoped 的元件。
 *
 * <p>路徑安全：使用者只設定「相對子路徑」，實際寫入 = 容器基底 {@code EXPORT_OUTPUT_DIR} resolve 子路徑，
 * 並驗證 normalize 後仍在基底內（拒 {@code ..}／絕對路徑跳脫）。
 */
@Slf4j
@Service
public class TradingRadarExportScheduleService {

    private static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String TW_MARKET = "台股";
    /** {@code trading_radar_export_setting.last_run_status} 的欄位上限（{@code varchar(500)}）。 */
    private static final int STATUS_MAX = 500;
    /** {@code trading_radar_export_setting.gdrive_last_status} 的欄位上限（{@code varchar(512)}）。 */
    private static final int GDRIVE_STATUS_MAX = 512;
    /** {@code trading_radar_export_setting.blog_last_status} 的欄位上限（{@code varchar(512)}）。 */
    private static final int BLOG_STATUS_MAX = 512;
    /** 背景重算失敗且當日確實零快照時的降級終點（Task 260）。 */
    static final String NO_SNAPSHOT_STATUS = "當日尚無快照，未產檔";
    /** 非台股交易日時的狀態文字（休市日不產檔，Task 260）。 */
    static final String NON_TRADING_DAY_STATUS = "非台股交易日，未產檔";

    private final TradingRadarExportTimeRepository timeRepo;
    private final TradingRadarExportSettingRepository settingRepo;
    private final TradingRadarExportService exportService;
    private final TradingRadarSnapshotStore snapshotStore;
    private final ObjectProvider<CurrentUserContext> currentUserProvider;
    private final GdriveOutputSupport gdrive;
    // 雙格式匯出（Requirement 55 / Task 271）：一次查詢取得 doc，再 render 成 xlsx 與 JSON 兩份
    private final com.steven.assets.service.export.ExcelDocRenderer excelDocRenderer;
    private final com.steven.assets.service.export.JsonDocRenderer jsonDocRenderer;
    private final com.steven.assets.service.export.DualFormatExportWriter dualWriter;
    private final String baseDir;
    private final TradingRadarService radarService;
    private final PriceQueryService priceQueryService;
    private final MarketDataService marketDataService;
    // 排程整合「發布到 Blog」（Requirement 102 / Task 366）：本機／Drive 產出流程完全不動，
    // 只在 runScheduled 最後追加這一步；run-now 刻意不觸發（見 runNow 的既有語意）。
    private final BlogPublishService blogPublishService;

    private final AtomicBoolean ticking = new AtomicBoolean(false);

    public TradingRadarExportScheduleService(TradingRadarExportTimeRepository timeRepo,
                                            TradingRadarExportSettingRepository settingRepo,
                                            TradingRadarExportService exportService,
                                            TradingRadarSnapshotStore snapshotStore,
                                            ObjectProvider<CurrentUserContext> currentUserProvider,
                                            GdriveOutputSupport gdrive,
                                            com.steven.assets.service.export.ExcelDocRenderer excelDocRenderer,
                                            com.steven.assets.service.export.JsonDocRenderer jsonDocRenderer,
                                            com.steven.assets.service.export.DualFormatExportWriter dualWriter,
                                            @Value("${EXPORT_OUTPUT_DIR:/home/steven}") String baseDir,
                                            TradingRadarService radarService,
                                            PriceQueryService priceQueryService,
                                            MarketDataService marketDataService,
                                            BlogPublishService blogPublishService) {
        this.timeRepo = timeRepo;
        this.settingRepo = settingRepo;
        this.exportService = exportService;
        this.snapshotStore = snapshotStore;
        this.currentUserProvider = currentUserProvider;
        this.gdrive = gdrive;
        this.excelDocRenderer = excelDocRenderer;
        this.jsonDocRenderer = jsonDocRenderer;
        this.dualWriter = dualWriter;
        this.baseDir = baseDir;
        this.radarService = radarService;
        this.priceQueryService = priceQueryService;
        this.marketDataService = marketDataService;
        this.blogPublishService = blogPublishService;
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
        // 啟用當下的自檢結果同樣不寫那兩欄（Task 247.3.4），只走當次回應。
        s.setUpdatedAt(LocalDateTime.now(TW_ZONE));
        settingRepo.save(s);
        return toSettingResponse(ownerId, sub, s, drive.selfCheckWarning());
    }

    /**
     * 頁首「匯出 Excel」的結果（Task 283）。
     *
     * @param xlsx       下載用 byte[]，永不為 null（render 失敗直接向外擲 → 5xx）
     * @param dirOutcome {@code "ok"}／{@code "failed"}／{@code "skipped"}，直接作為 {@code X-Dir-Export} 標頭值
     */
    public record ManualExportResult(byte[] xlsx, String dirOutcome) {}

    /**
     * 頁首「匯出 Excel」：下載仍給單一 xlsx（體驗不變），<b>另外</b>落一份 json ＋ xlsx 到輸出目錄
     * 並（啟用時）同步 Drive（Task 283）。
     *
     * <p><b>只查一次 Redis</b>——同一份 doc 同時 render 出下載用與落檔用的檔。Requirement 55 明訂
     * 本匯出點吃 Redis 即時快照、查兩次會產出對不起來的兩份檔。</p>
     *
     * <p><b>本方法一律不寫 {@code trading_radar_export_setting} 的四個狀態欄</b>
     * （{@code recordStatus}／{@code applyGdriveStatus}／{@code syncGdrive} 都不呼叫）：那四欄的語意是
     * 「<b>排程</b>（含 run-now）最後一次的結果」，手動下載寫進去會讓排程卡顯示一個不是排程產生的落點，
     * 使用者無法分辨排程有沒有正常跑。</p>
     */
    public ManualExportResult exportAndWriteManual(String from, String to) throws IOException {
        // 格式錯誤與 from > to 由 manualDoc 內部轉成 IllegalArgumentException → 400；
        // to 的日期解析必須排在它之後，提前自己 parse 會擲 DateTimeParseException → 500。
        TradingRadarExportService.ManualDoc md = exportService.manualDoc(from, to);
        byte[] xlsx = excelDocRenderer.render(md.doc());   // 失敗即向外擲：使用者拿不到檔就該回 5xx

        // 不得用 requireOwnerId()：它在無使用者時擲 IllegalArgumentException → 400，
        // 而本端點必須回「200 ＋ skipped ＋ 含表頭的合法 xlsx」。
        Long ownerId = currentUserProvider.getObject().getEffectiveUserId();
        if (ownerId == null || md.snapshotCount() == 0) {
            // 零快照時照寫會在使用者目錄留下只有表頭的無用檔，並蓋掉同名前一版（見 writeDailyExport 的 javadoc）
            return new ManualExportResult(xlsx, "skipped");
        }

        String outcome = "failed";
        try {
            byte[] json = null;
            try {
                json = jsonDocRenderer.render(md.doc());
            } catch (Exception e) {
                log.warn("交易雷達手動匯出 json render 失敗 owner={}：{}", ownerId, e.getMessage(), e);
            }
            // 檔名與排程完全相同、日期取 to（不是牆鐘今日）：下游以排程檔名取用
            String baseName = "交易雷達_" + ownerId + "_"
                    + LocalDateTime.parse(to).toLocalDate().format(FILE_DATE);
            TradingRadarExportSetting cfg = settingRepo.findByOwnerUserId(ownerId).orElse(null);
            var r = dualWriter.write(ownerId, resolveDir(currentSubpath(ownerId)), baseName, json, xlsx,
                    cfg != null && cfg.isGdriveEnabled(), cfg == null ? null : cfg.getGdriveSubpath());
            if (r.jsonFile() != null && r.xlsxFile() != null) outcome = "ok";
        } catch (Exception e) {
            // 落檔失敗一律不影響下載——使用者當下要的是那個檔
            log.warn("交易雷達手動匯出落檔失敗 owner={}：{}", ownerId, e.getMessage(), e);
        }
        return new ManualExportResult(xlsx, outcome);
    }

    /**
     * 立即匯出到目錄（驗證用）。走與排程同一支寫檔邏輯，且**不動任何時間點的當日 guard**。
     *
     * <p>產檔前一律先回補台股即時行情並重算一次（Task 260）——run-now 的用途就是驗證落點，
     * 使用者明確觸發，**不受交易日限制**，休市日也必須可用並照樣重算。
     */
    @Transactional
    public TradingRadarExportDto.RunNowResponse runNow() {
        long ownerId = requireOwnerId();
        LocalDate today = LocalDate.now(TW_ZONE);
        refreshPricesQuietly();
        recomputeQuietly(ownerId);
        try {
            var outcome = writeDailyExport(ownerId, today);
            if (outcome == null) {
                recordStatus(ownerId, NO_SNAPSHOT_STATUS);
                // 沒產檔就完全不上傳（絕不上傳前一次的舊檔），但已啟用時仍寫狀態欄說明原因，
                // 否則狀態會停在上一次的成功、顯示過期的好消息。
                GdriveOutputSupport.SyncResult skipped = syncGdrive(ownerId, null, "當日無交易雷達快照");
                return new TradingRadarExportDto.RunNowResponse(
                        null, 0L, NO_SNAPSHOT_STATUS + "（重算失敗且當日無既有快照）",
                        null, skipped == null ? null : skipped.status(), null, 0L, null);
            }
            var r = outcome.write();
            recordStatus(ownerId, r.localStatus());
            applyGdriveStatus(ownerId, r);
            // 366.9b：run-now 不觸發 blog 發布，維持既有語意（run-now 的用途是驗證本機／Drive 落點）。
            // 既有三欄語意不變：一律指 xlsx 那一份
            return new TradingRadarExportDto.RunNowResponse(
                    r.xlsxFile() == null ? null : r.xlsxFile().toString(),
                    r.xlsxFile() == null ? 0L : Files.size(r.xlsxFile()),
                    "匯出完成",
                    r.xlsxGdrivePath(), r.gdriveStatus(),
                    r.jsonFile() == null ? null : r.jsonFile().toString(),
                    r.jsonFile() == null ? 0L : Files.size(r.jsonFile()),
                    r.jsonGdrivePath());
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
     * 掃所有啟用中的時間點，對「今日尚未執行且排程時間已到」者，先回補行情、重算一次，再產檔
     * （Task 260）。
     *
     * <p>用 {@code now >= 排程時間}（而非「分鐘精確相等」）＋該時間點的 {@code lastRunDate} 當日 guard：
     * 排程執行緒被長工作卡住而跨越分鐘時，後續 tick 會自動補跑，避免整日靜默漏跑。判斷條件本身
     * 一個都不能改。</p>
     *
     * <p>先收集 due 清單再動作：due 為空時完全不呼叫回補（否則每分鐘都在打 external）；
     * due 非空但當日非台股交易日時，整批只記狀態、設 guard，不回補不重算不產檔不上傳；
     * due 非空且是交易日時，一輪只回補一次（跨 owner 共用同一份市場資料），再逐 owner
     * 重算＋產檔。</p>
     */
    private void runDueExports() {
        LocalDate today = LocalDate.now(TW_ZONE);
        LocalTime now = LocalTime.now(TW_ZONE);
        List<TradingRadarExportTime> due = new ArrayList<>();
        // 背景無 request context → ownerFilter 不啟用，讀全部 owner 的時間點列
        for (TradingRadarExportTime t : timeRepo.findAll()) {
            if (!Boolean.TRUE.equals(t.getEnabled())) continue;
            if (today.equals(t.getLastRunDate())) continue;
            if (!now.isBefore(LocalTime.of(t.getRunHour(), t.getRunMinute()))) {
                due.add(t);
            }
        }
        if (due.isEmpty()) return;

        if (!marketDataService.isTradingDay(TW_MARKET, today)) {
            for (TradingRadarExportTime t : due) {
                long ownerId = t.getOwnerUserId();
                recordStatus(ownerId, NON_TRADING_DAY_STATUS);
                log.info("交易雷達排程匯出略過（非台股交易日）owner={} {}:{}",
                        ownerId, t.getRunHour(), t.getRunMinute());
                t.setLastRunDate(today);
                t.setUpdatedAt(LocalDateTime.now(TW_ZONE));
                timeRepo.save(t);
            }
            return;
        }

        refreshPricesQuietly();
        for (TradingRadarExportTime t : due) {
            recomputeQuietly(t.getOwnerUserId());
            runScheduled(t, today);
        }
    }

    /**
     * 產檔前回補台股即時行情（Task 260）。<b>一輪只回補一次</b>——回補清單是全庫台股標的
     * （跨租戶共用的市場資料），逐 owner 各打一次只是重複打同一份清單。
     *
     * <p><b>不得沿用 TradingRadarRefreshService.refreshAndGet()</b>：其冷卻鍵取自 request-scoped 的
     * CurrentUserContext，背景會落到 "anonymous"，且會與使用者按下「重新整理」互相燒掉冷卻。
     *
     * <p>回補失敗／逾時／BUSY 一律只記 log 後繼續——外部服務不可用不得使當日缺檔，
     * 那是用一個新的失敗模式換掉舊的。開機自癒（ApplicationReadyEvent）時 external 可能尚未就緒，
     * 這條降級路徑即為該情境所需。
     */
    private void refreshPricesQuietly() {
        try {
            JsonNode result = priceQueryService.refreshTradingRadarPrices();
            log.info("交易雷達排程產檔前行情回補完成：{}", result);
        } catch (Exception e) {
            log.warn("交易雷達排程產檔前行情回補失敗（不影響後續重算與產檔）：{}", e.toString());
        }
    }

    /**
     * 產檔前重算（Task 260）。失敗只記 log——重算是「盡力讓檔更新」，不是產檔的新前提；
     * 後續 writeDailyExport 會回退用當日既有快照產檔，當日確實零快照才不寫檔。
     */
    private void recomputeQuietly(long ownerId) {
        try {
            radarService.recomputeAndStoreForOwner(ownerId);
        } catch (Exception e) {
            log.warn("交易雷達排程產檔前重算失敗 owner={}（不影響同輪其他使用者）：{}", ownerId, e.toString());
        }
    }

    /** 背景：對指定時間點產檔並更新 guard／狀態。單一使用者失敗只記錄、不影響其他人。 */
    private void runScheduled(TradingRadarExportTime t, LocalDate today) {
        long ownerId = t.getOwnerUserId();
        try {
            var outcome = writeDailyExport(ownerId, today);
            if (outcome == null) {
                recordStatus(ownerId, NO_SNAPSHOT_STATUS);
                syncGdrive(ownerId, null, "當日無交易雷達快照");
                log.info("交易雷達排程匯出略過（當日無快照）owner={} {}:{}",
                        ownerId, t.getRunHour(), t.getRunMinute());
                // 366.9c：沒有快照可發布時不呼叫 blog 發布（比照既有 Drive 同步在無快照時的既有邏輯）。
            } else {
                var r = outcome.write();
                recordStatus(ownerId, r.localStatus());
                applyGdriveStatus(ownerId, r);
                log.info("交易雷達排程匯出 owner={} {}:{} → {}",
                        ownerId, t.getRunHour(), t.getRunMinute(), r.localStatus());
                // Requirement 102 / Task 366：本機／Drive 產出流程完全不動，只在此追加 blog 發布這一步，
                // 沿用同一輪已讀出的快照（outcome.latestSnapshot()），不得為此再查一次 Redis。
                publishToBlogQuietly(ownerId, outcome.latestSnapshot());
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
     * {@link #writeDailyExport} 的結果：本機／Drive 落檔結果 ＋ 該輪讀到的最新一筆原始快照
     * （Requirement 102 / Task 366：供 {@link #runScheduled} 呼叫 blog 發布復用，不得為此
     * 再查一次 Redis）。
     */
    private record DailyExportOutcome(
            com.steven.assets.service.export.DualFormatExportWriter.DualResult write,
            JsonNode latestSnapshot) {}

    /**
     * 產出該 owner 當日（00:00～當下）快照的 Excel 並寫入其設定目錄，回傳實際落點；
     * <b>當日查無快照時回 {@code null} 且不寫檔</b>——呼叫端已在此之前先回補行情並重算一次
     * （Task 260），故此處查無快照代表「背景重算失敗且當日確實零快照」的降級終點，
     * 不再是常態路徑；若照寫會在使用者目錄留下只有表頭的無用檔，並蓋掉同名前一版。
     */
    private DailyExportOutcome writeDailyExport(long ownerId, LocalDate today) throws IOException {
        long fromEpoch = today.atStartOfDay(TW_ZONE).toInstant().toEpochMilli();
        long toEpoch = ZonedDateTime.now(TW_ZONE).toInstant().toEpochMilli();

        var range = snapshotStore.range(ownerId, fromEpoch, toEpoch);
        if (range.snapshots().isEmpty()) {
            return null;
        }
        JsonNode latestSnapshot = range.snapshots().get(range.snapshots().size() - 1);

        // 一次查詢取得 doc，再 render 兩種格式（不得為兩種格式各查一次 Redis 快照）
        var doc = exportService.radarDoc(ownerId, fromEpoch, toEpoch);
        byte[] xlsx = null;
        byte[] json = null;
        try {
            xlsx = excelDocRenderer.render(doc);
        } catch (Exception e) {
            log.warn("交易雷達 xlsx render 失敗 owner={}：{}", ownerId, e.getMessage(), e);
        }
        try {
            json = jsonDocRenderer.render(doc);
        } catch (Exception e) {
            log.warn("交易雷達 json render 失敗 owner={}：{}", ownerId, e.getMessage(), e);
        }
        String baseName = "交易雷達_" + ownerId + "_" + today.format(FILE_DATE);
        TradingRadarExportSetting cfg = settingRepo.findByOwnerUserId(ownerId).orElse(null);
        var writeResult = dualWriter.write(ownerId, resolveDir(currentSubpath(ownerId)), baseName, json, xlsx,
                cfg != null && cfg.isGdriveEnabled(), cfg == null ? null : cfg.getGdriveSubpath());
        return new DailyExportOutcome(writeResult, latestSnapshot);
    }

    /**
     * 排程「發布到 Blog」（Requirement 102 / Task 366）：{@code blogEnabled} 為真時呼叫
     * {@link BlogPublishService#publish}，沿用同一輪已讀出的快照。<b>永不讓例外往外逸出</b>
     * ——影響本方法既有的 {@code finally} 收尾（{@code lastRunDate} 等）是絕對不允許的；
     * {@link BlogPublishService#publish} 本身承諾永不擲出，這裡的 try/catch 是最後一道防線。
     */
    private void publishToBlogQuietly(long ownerId, JsonNode snapshot) {
        try {
            TradingRadarExportSetting s = settingRepo.findByOwnerUserId(ownerId).orElse(null);
            if (s == null || !s.isBlogEnabled()) return;
            blogPublishService.publish(ownerId, snapshot);
        } catch (Exception e) {
            log.warn("交易雷達排程 blog 發布失敗 owner={}：{}", ownerId, e.getMessage(), e);
            try {
                TradingRadarExportSetting s = settingRepo.findByOwnerUserId(ownerId).orElse(null);
                if (s != null) {
                    s.setBlogLastRunAt(LocalDateTime.now(TW_ZONE));
                    s.setBlogLastStatus(truncate("失敗：" + e.getMessage(), BLOG_STATUS_MAX));
                    settingRepo.save(s);
                }
            } catch (RuntimeException inner) {
                log.error("寫入 blog 發布失敗狀態時再度失敗 owner={}", ownerId, inner);
            }
        }
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
        s.setLastRunStatus(truncate(status, STATUS_MAX));
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
    /**
     * 寫回 Drive 狀態欄（雙格式版）。上傳已由 {@code DualFormatExportWriter} 完成，
     * 此處只負責把合併後的狀態字串存回設定列。未啟用時 {@code gdriveStatus} 為 null，兩欄一律不碰。
     *
     * <p>比照既有 {@link #syncGdrive}：「回報結果」本身不得成為新的失敗來源，故整段包 try/catch。
     */
    private void applyGdriveStatus(long ownerId,
                                   com.steven.assets.service.export.DualFormatExportWriter.DualResult r) {
        if (r.gdriveStatus() == null) return;
        try {
            TradingRadarExportSetting s = settingRepo.findByOwnerUserId(ownerId).orElse(null);
            if (s == null) return;
            s.setGdriveLastRunAt(LocalDateTime.now(TW_ZONE));
            s.setGdriveLastStatus(truncate(r.gdriveStatus(), GDRIVE_STATUS_MAX));
            settingRepo.save(s);
        } catch (RuntimeException e) {
            log.error("寫入 Drive 同步狀態失敗 owner={}：{}", ownerId, e.getMessage(), e);
        }
    }

    private GdriveOutputSupport.SyncResult syncGdrive(long ownerId, Path localFile, String skipReason) {
        try {
            TradingRadarExportSetting s = settingRepo.findByOwnerUserId(ownerId).orElse(null);
            if (s == null || !s.isGdriveEnabled()) return null;
            GdriveOutputSupport.SyncResult r = localFile == null
                    ? new GdriveOutputSupport.SyncResult(gdrive.skipped(skipReason), null)
                    : gdrive.syncQuietly(ownerId, s.getGdriveSubpath(), localFile);
            s.setGdriveLastRunAt(LocalDateTime.now(TW_ZONE));
            s.setGdriveLastStatus(truncate(r.status(), GDRIVE_STATUS_MAX));
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


    private TradingRadarExportDto.SettingResponse toSettingResponse(
            long ownerId, String subpath, TradingRadarExportSetting s) {
        // 讀取路徑不做自檢：自檢只在「使用者這次把開關打開」時才有意義。
        return toSettingResponse(ownerId, subpath, s, null);
    }

    /**
     * @param gdriveSelfCheckWarning 本次啟用 Drive 時的自檢警告（Task 247.3.5）；正常時為 {@code null}。
     *                               <b>不入庫</b>——尤其不得寫進 {@code gdriveLastStatus}，那一欄是「上次上傳」
     */
    private TradingRadarExportDto.SettingResponse toSettingResponse(
            long ownerId, String subpath, TradingRadarExportSetting s, String gdriveSelfCheckWarning) {
        return new TradingRadarExportDto.SettingResponse(
                subpath,
                resolveDir(subpath).toString(),
                // 一律兩份、主檔名相同（Requirement 55 / Task 271）；此字串會顯示在設定頁
                "交易雷達_" + ownerId + "_{YYYYMMDD}.xlsx / .json",
                s == null || s.getLastRunAt() == null ? null : s.getLastRunAt().toString(),
                s == null ? null : s.getLastRunStatus(),
                // 讀取一律不驗證 Drive 子路徑：DB 值可能被繞過 API 直改，若讀取也擲例外，設定頁會 500
                // 而使用者沒有任何入口能把它改回正常值——唯一的修正入口被自己鎖死。存檔時才驗。
                s != null && s.isGdriveEnabled(),
                s == null ? null : s.getGdriveSubpath(),
                gdrive.remoteName(),
                s == null || s.getGdriveLastRunAt() == null ? null : s.getGdriveLastRunAt().toString(),
                s == null ? null : s.getGdriveLastStatus(),
                gdriveSelfCheckWarning);
    }

    /**
     * 狀態字串寫入前的截斷（對應 {@code varchar(500)} 與 {@code varchar(512)}）。
     *
     * <p>成功時狀態內含絕對路徑、失敗時內含 rclone 或 IO 的原始錯誤訊息，長度無上限。
     * <b>寫入失敗會整筆交易回滾</b>，連帶 {@code last_run_date} 這個當日 guard 也寫不進去，
     * 該排程便從到點起每分鐘重試到午夜——截斷是為了擋掉這條回滾路徑，不只是為了好看。
     *
     * <p>{@code null} 進 {@code null} 出：兩欄皆 nullable，既有邏輯靠 null 表達「尚未執行」。
     */
    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
