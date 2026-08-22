package com.steven.assets.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.service.export.ExportDoc;
import java.util.ArrayList;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.model.StockAlert;
import com.steven.assets.model.StockAlertExportSetting;
import com.steven.assets.model.StockAlertTrigger;
import com.steven.assets.repository.StockAlertExportSettingRepository;
import com.steven.assets.repository.StockAlertRepository;
import com.steven.assets.repository.StockAlertTriggerRepository;
import com.steven.assets.util.MarketZones;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 警示觸發的即時 JSON 匯出（Requirement 54 / Task 254）。
 *
 * <p>由 {@link StockAlertService#recordTrigger} / {@code recordGroupTrigger} 在<b>寫入
 * {@code stock_alert_trigger} 之後</b>呼叫（先寫檔會漏掉當次那一筆），依該觸發的 owner 重查其<b>當日</b>
 * 全部觸發並<b>全量重寫</b> {@code alert_triggers_{ownerUserId}_{yyyyMMdd}.json}。
 *
 * <p><b>三個入口、語意各異：</b>
 * <table border="1">
 *   <tr><th>方法</th><th>看 {@code enabled}</th><th>Drive 上傳去抖</th><th>per-owner 鎖</th></tr>
 *   <tr><td>{@link #writeExport}</td><td>否（閘門在上層）</td><td>依參數</td><td>是</td></tr>
 *   <tr><td>{@link #exportForTrigger}</td><td>是，關閉即 return</td><td>是</td><td>經 writeExport</td></tr>
 *   <tr><td>{@link #runNow}</td><td>否（驗證工具）</td><td>否</td><td>經 writeExport</td></tr>
 * </table>
 * 本機寫檔<b>永遠是同步、即時的</b>，不受去抖影響。
 *
 * <p><b>本機同步寫、Drive 非同步合併</b>（這一條不遵守會讓整條即時價管線停擺）：
 * {@code PriceStreamService.onPriceUpdate} 對 {@code checkAlertsFor} 是<b>同步呼叫</b>，其上游是 Redis
 * 訂閱者執行緒，同時還負責 SSE 廣播與交易雷達評估。rclone 上傳逾時上限 45 秒，一次卡住就是整條價格
 * 更新管線停擺 45 秒。故本機寫檔（一次查詢＋一次本機寫檔，毫秒級）留在觸發執行緒，Drive 上傳丟
 * {@link ScheduledExecutorService} 背景執行並以最小間隔合併。
 *
 * <p><b>去抖一律是「延後至間隔到期的單一排定任務」</b>，不可寫成「距上次上傳未滿間隔就只標記 pending、
 * 由已排入的那個任務完成後補跑」——當天最後一次觸發若發生在上一次上傳<b>完成之後</b>，就沒有任何執行中
 * 的任務會回頭看 pending，Drive 那一份會永久缺最後一筆，隔天檔名滾到新日期後再也不會被修正。
 */
@Slf4j
@Service
public class StockAlertTriggerExportService {

    private static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 檔名樣式，供設定頁顯示。<b>固定單檔、不含日期</b>（Task 256）。
     *
     * <p><b>為什麼不用「每日一檔」</b>：美股交易時段（紐約 09:30–16:00）換算台北是 21:30 → 隔日 04:00、
     * 橫跨午夜，任何以台北日期分檔的方案都會把同一個美股交易日切成兩個檔案。實測紐約 07-28 的四筆觸發
     * 被切成台北 07-28 三筆（VT/QQQ/VOO）＋ 07-29 一筆（AMZN），使用者打開當日檔只看得到一筆。
     * 台股 09:00–13:30、英股 15:00–23:30 換算台北都不跨午夜，只有美股每天中。
     */
    // 供前端設定頁顯示；Requirement 55 / Task 272 起一律兩份、主檔名相同
    public static final String FILENAME_PATTERN = "alert_triggers_{使用者ID}.json / .xlsx";

    /**
     * 匯出視窗長度（台北日曆日）：今日 ＋ 前 2 日。
     *
     * <p><b>以日曆日而非「now − 72 小時」界定</b>：滾動小時數會讓同一筆觸發隨匯出時刻在檔案裡忽隱忽現
     * ——下游兩次讀到不同結果，卻沒有任何事件發生。3 天足以涵蓋週末與連假（週五盤中觸發，週一早上讀
     * 仍讀得到）。
     *
     * <p>非 final：測試需要調整才驗得到視窗邊界。不做成 DB 設定欄位（多一個沒人會調的欄位）。
     */
    private int windowDays = 3;

    /**
     * Drive 上傳的最小間隔（毫秒）。
     *
     * <p>Drive 端每次上傳都有固定成本（token 幾乎每次呼叫都已過期需 refresh、未設 {@code root_folder_id}
     * 需從根目錄逐層查找），開盤時段連續觸發會讓上傳次數遠多於有意義的內容變化。合併之所以安全，是因為
     * 檔案<b>每次都是當日全量重寫</b>——晚一點上傳的那一份必然包含先前所有內容。
     *
     * <p>非 final：測試需要把它調小才測得動去抖與尾端補跑。正式執行期間不會被改。
     */
    private long debounceIntervalMillis = 60_000L;

    private final StockAlertExportSettingRepository settingRepo;
    private final StockAlertTriggerRepository triggerRepo;
    private final StockAlertRepository alertRepo;
    private final StockMasterService stockMasterService;
    private final GdriveOutputSupport gdrive;
    private final ObjectMapper objectMapper;
    // 雙格式匯出（Requirement 55 / Task 272）：既有 JSON 一行不動，另由同一份 payload 攤出 xlsx
    private final com.steven.assets.service.export.ExcelDocRenderer excelDocRenderer;
    private final com.steven.assets.service.export.DualFormatExportWriter dualWriter;

    /** 容器內基底輸出目錄，經 docker volume 對映到 host（見 docker-compose.yml）。 */
    private final String baseDir;

    /**
     * per-owner 寫檔鎖。<b>三條路徑會寫進同一個檔名</b>：Redis 訂閱者執行緒的觸發、
     * {@code POST /api/stock-alerts/check} 的 HTTP 執行緒、run-now 的 HTTP 執行緒。
     * 刻意不是單一全域鎖——一個使用者的慢速磁碟不該拖累其他人。
     */
    private final Map<Long, ReentrantLock> writeLocks = new ConcurrentHashMap<>();

    /** per-owner 的 Drive 去抖狀態。 */
    private final Map<Long, DriveDebounceState> debounce = new ConcurrentHashMap<>();

    /**
     * Drive 上傳專用的單執行緒排程 executor。
     *
     * <p>必須是 {@code Scheduled} 版本——去抖靠 {@link ScheduledExecutorService#schedule} 延後。
     * 刻意不用 {@code @Async}：本專案 backend 未啟用 {@code @EnableAsync}，加上去會影響全域。
     * daemon thread，服務關閉時不阻擋 JVM 結束。
     */
    private final ScheduledExecutorService driveExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "alert-export-gdrive");
        t.setDaemon(true);
        return t;
    });

    public StockAlertTriggerExportService(StockAlertExportSettingRepository settingRepo,
                                          StockAlertTriggerRepository triggerRepo,
                                          StockAlertRepository alertRepo,
                                          StockMasterService stockMasterService,
                                          GdriveOutputSupport gdrive,
                                          ObjectMapper objectMapper,
                                          com.steven.assets.service.export.ExcelDocRenderer excelDocRenderer,
                                          com.steven.assets.service.export.DualFormatExportWriter dualWriter,
                                          @Value("${EXPORT_OUTPUT_DIR:/home/steven}") String baseDir) {
        this.settingRepo = settingRepo;
        this.triggerRepo = triggerRepo;
        this.alertRepo = alertRepo;
        this.stockMasterService = stockMasterService;
        this.gdrive = gdrive;
        this.objectMapper = objectMapper;
        this.excelDocRenderer = excelDocRenderer;
        this.dualWriter = dualWriter;
        this.baseDir = baseDir;
    }

    /** 測試用：把去抖間隔調小才測得動合併與尾端補跑。 */
    void setDebounceIntervalMillis(long millis) {
        this.debounceIntervalMillis = millis;
    }

    /** 測試用：調整視窗長度才驗得到邊界。 */
    void setWindowDays(int days) {
        this.windowDays = days;
    }

    /** 視窗長度（天），供 JSON 與測試對照。 */
    public int windowDays() {
        return windowDays;
    }

    /** per-owner 的 Drive 去抖狀態：是否已有排定中的任務 ＋ 最近一次<b>實際上傳完成</b>的時刻。 */
    private static final class DriveDebounceState {
        boolean scheduled;
        long lastUploadAtMillis;
    }

    // ===== 入口 1：觸發路徑 =====

    /**
     * 觸發後的匯出（由 {@code recordTrigger} / {@code recordGroupTrigger} 呼叫）。
     *
     * <p><b>絕不擲例外</b>：匯出失敗不得讓 {@code stock_alert_trigger} 的 INSERT 回滾、不得中斷 email
     * enqueue、不得回拋而中止 {@code evaluate} / {@code evaluateGroup} 的整輪檢查。
     *
     * <p>{@code enabled=false}（預設）時<b>直接 return，什麼都不做</b>——不查觸發、不寫檔、不碰 Drive、
     * 不寫狀態欄，既有部署升級後行為與現況完全一致。
     */
    public void exportForTrigger(Long ownerUserId) {
        try {
            if (ownerUserId == null) return;
            StockAlertExportSetting s = settingRepo.findByOwnerUserId(ownerUserId).orElse(null);
            if (s == null || !s.isEnabled()) return;
            writeExport(ownerUserId, true);
        } catch (Exception e) {
            log.warn("警示觸發匯出失敗 owner={}：{}", ownerUserId, e.getMessage(), e);
        }
    }

    // ===== 入口 2：立即匯出（驗證用） =====

    /**
     * 以指定 owner 身分把<b>當日已發生的觸發</b>重新產檔並（啟用時）上傳 Drive。
     *
     * <p><b>不看 {@code enabled}</b>——它是驗證工具，設定還沒啟用時使用者同樣需要確認落點正確
     * （但不改變 {@code enabled} 的值）。<b>不套去抖</b>，使用者是刻意要驗證這一次；且<b>不更新去抖用的
     * 「最近一次上傳時間」</b>，否則按一次按鈕就會讓觸發路徑的 Drive 同步靜默停一個間隔。
     *
     * <p>當日尚無任何觸發時仍寫出 {@code triggers: []} 的合法 JSON——按下按鈕的目的是驗證落點，
     * 空檔案同樣達成該目的。
     */
    public ExportResult runNow(Long ownerUserId) throws IOException {
        return writeExport(ownerUserId, false);
    }

    /** 一次匯出的結果（本機落點、bytes、觸發筆數，以及 Drive 那一側的結果）。 */
    /**
     * <b>既有欄位維持指向 {@code .json}</b>（Requirement 55 / Task 272）——與其餘八個匯出點相反。
     * 理由：{@code alert_triggers_*.json} 是本匯出點的對外契約，既有 {@code file}／{@code sizeBytes}
     * 指的就是那一份，改成指向 xlsx 會靜默改變既有回應語意。新增的是 xlsx 那三欄。
     */
    public record ExportResult(Path file, long sizeBytes, int triggerCount,
                               String gdrivePath, String gdriveStatus,
                               Path xlsxFile, long xlsxSizeBytes, String xlsxGdrivePath) {}

    // ===== 核心：真正產檔 =====

    /**
     * 產生並落檔，回傳結果。<b>本機這一段永遠同步完成</b>（毫秒級），與 {@code debounceDriveUpload} 無關。
     *
     * @param debounceDriveUpload {@code true}＝Drive 上傳走延後合併（觸發路徑）；
     *                            {@code false}＝直接上傳（run-now）。
     *                            <b>少了這個鑑別參數，唯一能收斂的寫法是「一律去抖」，run-now 就失去意義。</b>
     */
    private ExportResult writeExport(Long ownerUserId, boolean debounceDriveUpload) throws IOException {
        ReentrantLock lock = writeLocks.computeIfAbsent(ownerUserId, k -> new ReentrantLock());
        lock.lock();
        try {
            StockAlertExportSetting s = settingRepo.findByOwnerUserId(ownerUserId).orElseGet(() ->
                    StockAlertExportSetting.builder().ownerUserId(ownerUserId).build());

            // 視窗起點＝今日台北日期 −(windowDays−1) 天的 00:00；無上界（未來時間不存在）
            LocalDateTime since = LocalDate.now(TW_ZONE).minusDays(windowDays - 1L).atStartOfDay();
            List<StockAlertTrigger> triggers =
                    triggerRepo.findByOwnerAndCreatedAtAfter(ownerUserId, since);
            // 一次組 payload，兩種格式共用——JSON 是對外契約，xlsx 由同一份 payload 攤出來
            ObjectNode payload = buildPayload(ownerUserId, since, triggers);
            byte[] data = toJsonBytes(payload);
            // xlsx 的 render 單獨包 try/catch：新加的 POI 產檔若擲例外而沒被接住，
            // 會連既有的 JSON（Requirement 54 的對外契約檔）一起弄丟。
            byte[] xlsx = null;
            try {
                xlsx = excelDocRenderer.render(alertTriggersDoc(payload));
            } catch (Exception e) {
                log.warn("警示觸發 xlsx render 失敗 owner={}：{}", ownerUserId, e.getMessage(), e);
            }

            com.steven.assets.service.export.DualFormatExportWriter.DualResult dual;
            try {
                // gdriveEnabled=false：本機兩份由共用元件寫，Drive 仍走下方既有的去抖／立即兩條分支
                dual = dualWriter.write(ownerUserId, resolveDir(s.getOutputSubpath()),
                        "alert_triggers_" + ownerUserId, data, xlsx, false, null);
            } catch (IOException | RuntimeException e) {
                s.setOwnerUserId(ownerUserId);
                s.setLastRunAt(LocalDateTime.now(TW_ZONE));
                s.setLastRunStatus(truncate("失敗：" + e.getMessage()));
                // 本機失敗＝完全不上傳（絕不上傳前一次的舊檔）；已啟用 Drive 時仍寫狀態欄說明原因，
                // 否則狀態欄會停在上一次的成功、顯示過期的好消息。
                if (s.isGdriveEnabled()) {
                    s.setGdriveLastRunAt(LocalDateTime.now(TW_ZONE));
                    s.setGdriveLastStatus(gdrive.skipped("本輪未產生本機檔案"));
                }
                settingRepo.save(s);
                throw e;
            }

            Path file = dual.jsonFile();
            s.setOwnerUserId(ownerUserId);
            s.setLastRunAt(LocalDateTime.now(TW_ZONE));
            // 一律沿用共用元件算好、已截斷的字串：自組會在其中一份失敗時說謊，且無截斷會溢位欄位長度。
            s.setLastRunStatus(truncate(dual.localStatus() + "（" + triggers.size() + " 筆觸發）"));
            settingRepo.save(s);

            // 本機檔寫成功之後才上傳，順序不可顛倒。
            String gdrivePath = null;
            String gdriveStatus = null;
            String xlsxGdrivePath = null;
            if (s.isGdriveEnabled()) {
                if (debounceDriveUpload) {
                    scheduleDriveUpload(ownerUserId);
                } else {
                    // run-now：立即上傳，且同樣要上傳兩份
                    BothUploaded r = uploadBothNow(ownerUserId, s.getGdriveSubpath(),
                            file, dual.xlsxFile(), false);
                    gdrivePath = r.jsonPath();
                    gdriveStatus = r.status();
                    xlsxGdrivePath = r.xlsxPath();
                }
            }
            return new ExportResult(file, data.length, triggers.size(), gdrivePath, gdriveStatus,
                    dual.xlsxFile(),
                    dual.xlsxFile() == null ? 0L : Files.size(dual.xlsxFile()), xlsxGdrivePath);
        } finally {
            lock.unlock();
        }
    }

    // ===== JSON 組裝 =====

    /**
     * 組裝當日全量 JSON。
     *
     * <p>條件文案一律取自 {@link StockAlertService#buildLabel} / {@link StockAlertService#buildGroupLabel}
     * ——警示頁／觀察頁／email digest／補發四條既有路徑已共用同一支，第五份必然分歧。兩支都是
     * {@code public static}，以類別名靜態呼叫即可，<b>不注入 {@code StockAlertService}</b>（會形成建構子
     * 循環依賴，Spring Boot 2.6+ 預設禁止，結果是整個 business-services 起不來）。
     *
     * <p>技術指標一律用<b>該 trigger 列自己的值</b>構造 {@code FullIndicators}，不重算——重算得到的是
     * 「匯出當下」的均線，會讓 MA% 條件的換算觸發價與觸發當下不符，而且一次匯出要為每筆觸發打一次
     * 指標計算。
     */
    /**
     * 組裝 payload。<b>內容一行未改</b>（Requirement 55 / Task 272 只是把它從序列化拆開，
     * 好讓新增的 xlsx 吃到<b>同一份</b> payload）——這份 JSON 是 Requirement 54 對下游的契約，
     * 輸出 byte 必須與改動前逐 byte 相同。
     */
    private ObjectNode buildPayload(Long ownerUserId, LocalDateTime since, List<StockAlertTrigger> triggers) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("ownerUserId", ownerUserId);
        // windowDays / since 讓下游知道「沒有更早的資料」是視窗造成的，不是真的沒觸發過。
        // 刻意不輸出 date：固定單檔沒有「這是哪一天的檔」這個語意，留著會誤導下游以為只含那一天。
        root.put("windowDays", windowDays);
        root.put("since", since.toString());
        root.put("exportedAt", LocalDateTime.now(TW_ZONE).toString());
        root.put("triggerCount", triggers.size());

        ArrayNode arr = root.putArray("triggers");
        for (StockAlertTrigger t : triggers) {
            ObjectNode n = arr.addObject();
            n.put("triggerId", t.getId());
            boolean group = t.getGroupId() != null;
            n.put("source", group ? "GROUP" : "ALERT");
            n.put("alertId", t.getAlertId());
            n.put("groupId", t.getGroupId());
            n.put("stockCode", t.getStockCode());
            n.put("stockName", stockMasterService.resolveNameLocalOnly(t.getStockCode(), t.getMarket()));
            n.put("market", t.getMarket());
            n.put("condition", buildCondition(t));
            // triggeredAt 是「該股市場」的牆鐘（美股紐約、英股倫敦），createdAt 才是台北牆鐘。
            // 沒有 triggeredAtZone 這一欄，下游無法正確解讀 triggeredAt。
            n.put("triggeredAt", t.getTriggeredAt() == null ? null : t.getTriggeredAt().toString());
            n.put("triggeredAtZone", MarketZones.resolve(t.getMarket()).getId());
            n.put("createdAt", t.getCreatedAt() == null ? null : t.getCreatedAt().toString());
            // 數值一律輸出為 JSON number；指標資料不足時輸出 null，不得以 0 或空字串充數。
            putDecimal(n, "price", t.getPrice());
            putDecimal(n, "monthlyMa", t.getMonthlyMa());
            putDecimal(n, "quarterlyMa", t.getQuarterlyMa());
            putDecimal(n, "annualMa", t.getAnnualMa());
            putDecimal(n, "kValue", t.getKValue());
            putDecimal(n, "dValue", t.getDValue());
        }
        return root;
    }

    /** 序列化那三行：原地搬出來，一字未改。 */
    private byte[] toJsonBytes(ObjectNode root) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(root).getBytes(StandardCharsets.UTF_8);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("警示觸發 JSON 序列化失敗：" + e.getMessage(), e);
        }
    }

    /**
     * 把既有 payload 攤成 {@link ExportDoc}，供 xlsx render（Requirement 55 / Task 272）。
     *
     * <p>參數型別是 {@code ObjectNode} 而非 {@code Map<String,Object>}——payload 本來就是 ObjectNode，
     * 硬轉成 Map 會多一層轉換並丟失型別。
     *
     * <p><b>headers 由 payload 動態推導，不得在 Excel 側硬編一份欄位清單</b>：硬編的那一份在
     * Requirement 54 日後加欄位時會靜默少一欄，而 JSON 有、Excel 沒有，兩份檔就不一致了。
     * 推導方式為「所有元素 key 的聯集、以第一個元素的順序為基準」。
     */
    ExportDoc alertTriggersDoc(ObjectNode payload) {
        List<ExportDoc.Kv> meta = new ArrayList<>();
        for (String key : List.of("ownerUserId", "windowDays", "since", "exportedAt", "triggerCount")) {
            JsonNode v = payload.path(key);
            meta.add(new ExportDoc.Kv(key, v.isMissingNode() || v.isNull() ? null : jsonScalar(v),
                    ExportDoc.Format.TEXT));
        }

        JsonNode arr = payload.path("triggers");
        List<String> headers = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode e : arr) {
                e.fieldNames().forEachRemaining(f -> { if (!headers.contains(f)) headers.add(f); });
            }
        }
        if (headers.isEmpty()) {
            // 空觸發時仍須產出含表頭的合法檔——欄位順序取自 buildPayload 內實際寫入的順序（單一來源）
            headers.addAll(TRIGGER_FIELDS);
        }

        List<List<Object>> rows = new ArrayList<>();
        if (arr.isArray()) {
            for (JsonNode e : arr) {
                List<Object> row = new ArrayList<>(headers.size());
                for (String h : headers) {
                    JsonNode v = e.path(h);
                    row.add(v.isMissingNode() || v.isNull() ? null : jsonScalar(v));
                }
                rows.add(row);
            }
        }
        List<ExportDoc.Format> formats = new ArrayList<>(headers.size());
        for (int i = 0; i < headers.size(); i++) formats.add(ExportDoc.Format.TEXT);

        return new ExportDoc("警示觸發", List.of(new ExportDoc.Sheet("警示觸發", List.of(
                new ExportDoc.KvRow(meta),
                new ExportDoc.Blank(),
                new ExportDoc.Table("觸發明細", ExportDoc.LineStyle.SECTION_13, headers,
                        true, false, false, formats, rows)),
                Math.max(headers.size(), meta.size() * 2))));
    }

    /** JsonNode → 原始型別（數值保留 BigDecimal，boolean 保留 Boolean），供兩個 renderer 各自呈現。 */
    private static Object jsonScalar(JsonNode v) {
        if (v.isNumber()) return v.decimalValue();
        if (v.isBoolean()) return v.booleanValue();
        return v.asText();
    }

    /** 空觸發時的表頭順序；與 {@link #buildPayload} 內寫入 trigger 物件的順序一致。 */
    private static final List<String> TRIGGER_FIELDS = List.of(
            "triggerId", "source", "alertId", "groupId", "stockCode", "stockName", "market",
            "condition", "triggeredAt", "triggeredAtZone", "createdAt", "price",
            "monthlyMa", "quarterlyMa", "annualMa", "kValue", "dValue");

    /**
     * 該筆觸發的條件文案。alert／group 已被刪除時回 {@code null} 而非讓整份匯出失敗
     * （{@code buildGroupLabel} 對空 list 本來就回空字串，比照放行）。
     */
    private String buildCondition(StockAlertTrigger t) {
        TechnicalIndicatorService.FullIndicators ind = new TechnicalIndicatorService.FullIndicators(
                // weeklyMa 傳 null：觸發紀錄未留存 MA5，且本方法只用來組條件文案、不讀週線（Task 265）。
                t.getMonthlyMa(), t.getQuarterlyMa(), t.getAnnualMa(), t.getKValue(), t.getDValue(),
                null, null, null, TechnicalIndicatorService.ExtendedIndicators.EMPTY,
                // ma10 傳 null：同上，觸發紀錄未留存 10 日均線（Task 356.3b 的純追加欄）。
                null);
        try {
            if (t.getGroupId() != null) {
                List<StockAlert> members = alertRepo.findByGroupIdOrderByDisplayOrderAsc(t.getGroupId());
                if (members.isEmpty()) return null;
                return StockAlertService.buildGroupLabel(members, ind);
            }
            if (t.getAlertId() == null) return null;
            return alertRepo.findById(t.getAlertId())
                    .map(a -> StockAlertService.buildLabel(a, ind))
                    .orElse(null);
        } catch (Exception e) {
            log.warn("組裝觸發條件文案失敗 trigger={}：{}", t.getId(), e.getMessage());
            return null;
        }
    }

    private static void putDecimal(ObjectNode n, String field, BigDecimal v) {
        if (v == null) n.putNull(field);
        else n.put(field, v);
    }

    // ===== 本機落檔 =====


    /** 基底 resolve 子路徑並驗證仍在基底內（拒 `..`／絕對路徑跳脫）。 */
    Path resolveDir(String subpath) {
        String sub = subpath == null ? "" : subpath.trim();
        if (sub.isEmpty()) sub = "input";
        Path base = Path.of(baseDir).toAbsolutePath().normalize();
        Path target = base.resolve(sub).normalize();
        if (!target.startsWith(base)) {
            throw new IllegalArgumentException("輸出子路徑不可跳脫基底目錄：" + subpath);
        }
        return target;
    }

    // ===== Drive 同步（非同步 ＋ 合併去抖） =====

    /**
     * 排定一次 Drive 上傳（觸發路徑專用）。
     *
     * <p><b>一律是「延後至間隔到期的單一排定任務」</b>：該 owner 沒有排定中的任務就 schedule 一個
     * （延遲 = 距上次上傳完成滿一個間隔所需的時間，已滿則為 0）；已有排定中的任務就<b>什麼都不做</b>
     * ——那個任務到期時會讀最新的檔案，內容自然是最新的。
     *
     * <p><b>被合併的那幾次不碰 {@code gdriveLastRunAt} / {@code gdriveLastStatus}</b>：那兩欄的語意是
     * 「上次<b>上傳</b>的結果」，寫進去會覆蓋掉前一次真正成功的落點與 bytes，並把時間欄寫成一個根本
     * 沒發生過上傳的時刻。使用者要知道「本機即時、Drive 最多延遲約一分鐘」，走 UI 常駐文案，不入庫。
     */
    private void scheduleDriveUpload(Long ownerUserId) {
        DriveDebounceState st = debounce.computeIfAbsent(ownerUserId, k -> new DriveDebounceState());
        long delay;
        synchronized (st) {
            if (st.scheduled) return;   // 已有任務排定中：到期時會讀到最新的檔案，不必再排一個
            st.scheduled = true;
            long since = System.currentTimeMillis() - st.lastUploadAtMillis;
            delay = Math.max(0L, debounceIntervalMillis - since);
        }
        driveExecutor.schedule(() -> {
            synchronized (st) {
                st.scheduled = false;   // 先清旗標再上傳：上傳期間若有新觸發，會排下一個任務
            }
            try {
                StockAlertExportSetting cur = settingRepo.findByOwnerUserId(ownerUserId).orElse(null);
                if (cur == null || !cur.isGdriveEnabled()) return;
                // 兩個 Path 在任務內以固定檔名重新解析，**不捕捉排定當下的值**：
                // 檔名固定、內容為當日全量重寫，故到期時磁碟上那一份必然是最新的。
                // 若捕捉排定當下的 xlsxFile，第一次觸發剛好 render 失敗（null）時，該視窗內
                // 後續觸發全被合併掉，到期仍以 null 執行——那天最後一次觸發就再也補不回來。
                Path dir = resolveDir(cur.getOutputSubpath());
                Path json = dir.resolve("alert_triggers_" + ownerUserId + ".json");
                Path xlsx = dir.resolve("alert_triggers_" + ownerUserId + ".xlsx");
                uploadBothNow(ownerUserId, cur.getGdriveSubpath(),
                        Files.exists(json) ? json : null,
                        Files.exists(xlsx) ? xlsx : null, true);
            } catch (Exception e) {
                log.warn("警示觸發匯出的 Drive 上傳失敗 owner={}：{}", ownerUserId, e.getMessage(), e);
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    /**
     * 實際上傳並寫回狀態欄。一律走 {@link GdriveOutputSupport#syncQuietly}——它是全 backend 唯一的
     * Drive 出口，內含<b>每次上傳前的 owner 權限複驗</b>（背景執行緒沒有 request context，PUT 當下的
     * 檢查在此完全不適用）、子路徑驗證、逾時／速率限制／失敗三種措辭的區分與 512 字元截斷。
     *
     * <p>背景執行緒不跨執行緒傳遞 entity：需要更新狀態欄時在任務內重新查一次再 save。
     *
     * @param updateDebounceClock run-now 傳 {@code false}——按一次「立即匯出」不該讓觸發路徑的 Drive
     *                            同步靜默停一個間隔
     */
    private BothUploaded uploadBothNow(Long ownerUserId, String subpath,
                                       Path jsonFile, Path xlsxFile, boolean updateDebounceClock) {
        GdriveOutputSupport.SyncResult j = gdrive.syncQuietly(ownerUserId, subpath, jsonFile);
        GdriveOutputSupport.SyncResult x = xlsxFile == null
                ? new GdriveOutputSupport.SyncResult(gdrive.skipped("本輪未產生 xlsx"), null)
                : gdrive.syncQuietly(ownerUserId, subpath, xlsxFile);
        // 字串契約沿用共用元件的 "xlsx …／json …"；兩半各自先截斷再合併——合併後才截尾會把
        // ／json 那一整段切掉，違反「必須能分辨是哪一份成功、哪一份失敗」。
        String merged = truncate("xlsx " + half(x.status()) + "／json " + half(j.status()));
        if (updateDebounceClock) {
            DriveDebounceState st = debounce.computeIfAbsent(ownerUserId, k -> new DriveDebounceState());
            synchronized (st) {
                st.lastUploadAtMillis = System.currentTimeMillis();
            }
        }
        settingRepo.findByOwnerUserId(ownerUserId).ifPresent(cur -> {
            cur.setGdriveLastRunAt(LocalDateTime.now(TW_ZONE));
            cur.setGdriveLastStatus(merged);
            settingRepo.save(cur);
        });
        return new BothUploaded(merged, x.path(), j.path());
    }

    /** 兩份的 Drive 落點與合併後的狀態字串。 */
    private record BothUploaded(String status, String xlsxPath, String jsonPath) {}

    /** 合併前每半的上限：扣掉 {@code "xlsx "} 與 {@code "／json "} 共 12 字元的固定開銷後對半分。 */
    private static String half(String s) {
        if (s == null) return "";
        int max = (512 - 12) / 2;
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ===== 設定讀寫（供 controller）=====

    /** 取當前使用者設定；無則回預設值（<b>不寫入 DB</b>）。 */
    public StockAlertExportSetting getOrDefault(Long ownerUserId) {
        return settingRepo.findByOwnerUserId(ownerUserId).orElseGet(() ->
                StockAlertExportSetting.builder().ownerUserId(ownerUserId).build());
    }

    /**
     * upsert 設定。
     *
     * <p>Drive 兩欄一律走 {@link GdriveOutputSupport#resolveUpdate}：它一次做完 null 解析（未送出＝不變更）、
     * 正規化、驗證（開頭 {@code /}／含 {@code ..} 段／含 {@code :}／啟用時必填 → 400）與<b>「明確要求啟用時」
     * 的主要管理者權限檢查</b>（不通過擲 {@code AdminRequiredException} → 403）。
     * <b>不得自行查 {@code AppUserRepository}、不得用 {@code role == ADMIN}</b>——{@code role} 是 DB 欄位、
     * 可以有多列 ADMIN，判準只能是 {@code isConfiguredAdmin(email)}，而它已封裝在該方法內。
     *
     * <p>本機 {@code outputSubpath} 與 {@code enabled} 維持所有使用者皆可設定——只有 Drive 那一項會把
     * 資料送出本機，此不對稱是刻意的。
     *
     * <p>刻意不碰四個執行結果欄位（{@code lastRun*} / {@code gdriveLast*}）：那是執行結果，不是使用者設定。
     * 啟用當下的自檢結果同樣不入庫，只走當次回應。
     *
     * @return 本次若把 Drive 開關從 false 翻成 true 的自檢警告（正常時 null），供 controller 放進回應
     */
    public String update(Long ownerUserId, Boolean enabled, String outputSubpath,
                         Boolean gdriveEnabled, String gdriveSubpath) {
        StockAlertExportSetting s = getOrDefault(ownerUserId);
        GdriveOutputSupport.DriveSettings drive = gdrive.resolveUpdate(
                ownerUserId, gdriveEnabled, gdriveSubpath, s.isGdriveEnabled(), s.getGdriveSubpath());

        if (outputSubpath != null) {
            String sub = outputSubpath.trim();
            if (sub.isEmpty()) sub = "input";
            resolveDir(sub);   // 驗證不跳脫基底（丟出即擋下）
            s.setOutputSubpath(sub);
        }
        if (enabled != null) s.setEnabled(enabled);
        s.setOwnerUserId(ownerUserId);
        s.setGdriveEnabled(drive.enabled());
        s.setGdriveSubpath(drive.subpath());
        s.setUpdatedAt(LocalDateTime.now(TW_ZONE));
        settingRepo.save(s);
        return drive.selfCheckWarning();
    }

    /** 供 controller 組 response 用的衍生顯示值。 */
    public String baseDir() {
        return baseDir;
    }

    /**
     * 供設定頁顯示的實際落點目錄。<b>不合法時回 {@code null} 而非擲例外</b>——DB 值可能被繞過 API 直改，
     * 若讀取也擲例外，設定頁會 500 而使用者失去唯一的修正入口（同既有九頁 {@code absolutePathOrNull}
     * 的理由）。存檔時才驗。
     */
    public String resolvedDirDisplay(String subpath) {
        try {
            return resolveDir(subpath).toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 供 controller 組 response 用的衍生顯示值（不入庫）。 */
    public String gdriveRemoteName() {
        return gdrive.remoteName();
    }

    /** 時間欄格式化，與其他九個匯出頁一致。 */
    public static String fmt(LocalDateTime t) {
        return t == null ? null : t.format(TS_FMT);
    }

    private static String truncate(String s) {
        if (s == null) return null;
        return s.length() <= 512 ? s : s.substring(0, 511) + "…";
    }
}
