package com.steven.assets.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.steven.assets.model.Stock;
import com.steven.assets.model.StockAlert;
import com.steven.assets.model.StockAlertExportSetting;
import com.steven.assets.model.StockAlertTrigger;
import com.steven.assets.repository.StockAlertExportSettingRepository;
import com.steven.assets.repository.StockAlertRepository;
import com.steven.assets.repository.StockAlertTriggerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link StockAlertTriggerExportService} 單元測試（Requirement 54 / Task 254）。
 *
 * <p>這裡的每一條都對應一個「寫錯了不會有錯誤訊息、只會靜默出錯」的點：
 * <ul>
 *   <li><b>當日邊界用 {@code created_at} 而非 {@code triggered_at}</b>——寫反的話台北凌晨觸發的美股
 *       警示會被歸進前一天的檔案，檔名日期與內容日期分家</li>
 *   <li><b>owner 隔離</b>——{@code stock_alert_trigger} 沒掛 {@code @Filter}，漏了 owner 條件就是
 *       把所有人的觸發寫進每個人的檔案</li>
 *   <li><b>Drive 去抖的尾端補跑</b>——「只標記 pending 由已排入任務補跑」的壞寫法會讓當日最後一次
 *       觸發永遠不上 Drive，而「只呼叫一次 copyTo」那條測試在壞實作下也會通過</li>
 *   <li><b>被合併的那幾次不得碰兩個 Drive 狀態欄</b>——寫進去會覆蓋掉前一次真正成功的落點</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class StockAlertTriggerExportTest {

    private static final ZoneId TW = ZoneId.of("Asia/Taipei");
    private static final long OWNER_A = 1L;
    private static final long OWNER_B = 2L;

    @Mock private StockAlertExportSettingRepository settingRepo;
    @Mock private StockAlertTriggerRepository triggerRepo;
    @Mock private StockAlertRepository alertRepo;
    @Mock private StockMasterService stockMasterService;
    @Mock private GdriveOutputSupport gdrive;

    @TempDir Path tmp;

    private StockAlertTriggerExportService service;
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        service = new StockAlertTriggerExportService(settingRepo, triggerRepo, alertRepo,
                stockMasterService, gdrive, mapper, tmp.toString());
        when(settingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(stockMasterService.resolveNameLocalOnly(any(), any())).thenReturn("台積電");
    }

    // ===== 254.10.4 未啟用時完全不做任何事 =====

    @Test
    void 未啟用時不查觸發也不產檔() {
        when(settingRepo.findByOwnerUserId(OWNER_A)).thenReturn(Optional.of(
                setting(OWNER_A, false, false)));

        service.exportForTrigger(OWNER_A);

        verify(triggerRepo, never()).findByOwnerAndCreatedAtInDay(anyLong(), any(), any());
        verify(gdrive, never()).syncQuietly(anyLong(), any(), any());
        assertThat(listJson()).isEmpty();
    }

    @Test
    void 查無設定列時不產檔() {
        when(settingRepo.findByOwnerUserId(OWNER_A)).thenReturn(Optional.empty());

        service.exportForTrigger(OWNER_A);

        verify(triggerRepo, never()).findByOwnerAndCreatedAtInDay(anyLong(), any(), any());
        assertThat(listJson()).isEmpty();
    }

    // ===== 254.10.1 當日邊界一律以 created_at（台北牆鐘）界定 =====

    @Test
    void 查詢窗以台北當日的createdAt界定而非triggeredAt() {
        LocalDate today = LocalDate.now(TW);
        enabled(OWNER_A);
        when(triggerRepo.findByOwnerAndCreatedAtInDay(eq(OWNER_A), any(), any())).thenReturn(List.of());

        service.exportForTrigger(OWNER_A);

        ArgumentCaptor<LocalDateTime> start = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> end = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(triggerRepo).findByOwnerAndCreatedAtInDay(eq(OWNER_A), start.capture(), end.capture());
        assertThat(start.getValue()).isEqualTo(today.atStartOfDay());
        assertThat(end.getValue()).isEqualTo(today.plusDays(1).atStartOfDay());
    }

    @Test
    void 美股觸發的triggeredAt為紐約前一日時仍歸入台北當日檔案() throws Exception {
        LocalDate today = LocalDate.now(TW);
        enabled(OWNER_A);
        // 台北今日 01:00 觸發的美股警示：triggered_at 是紐約時間、日期為前一天；created_at 才是台北今日
        StockAlertTrigger t = trigger(11L, 101L, null, "AAPL", "美股",
                today.minusDays(1).atTime(13, 0),      // triggered_at（紐約牆鐘、前一日）
                today.atTime(1, 0));                    // created_at（台北牆鐘、今日）
        when(triggerRepo.findByOwnerAndCreatedAtInDay(eq(OWNER_A), any(), any())).thenReturn(List.of(t));
        when(alertRepo.findById(101L)).thenReturn(Optional.of(alert(101L, OWNER_A, "PRICE_BELOW", "150")));

        service.exportForTrigger(OWNER_A);

        JsonNode root = readJson(OWNER_A, today);
        assertThat(root.get("date").asText()).isEqualTo(today.toString());
        assertThat(root.get("triggerCount").asInt()).isEqualTo(1);
        JsonNode n = root.get("triggers").get(0);
        assertThat(n.get("stockCode").asText()).isEqualTo("AAPL");
        // 兩個時間都輸出，且標明 triggeredAt 的時區語意，下游才解讀得了
        assertThat(n.get("triggeredAt").asText()).startsWith(today.minusDays(1).toString());
        assertThat(n.get("triggeredAtZone").asText()).isEqualTo("America/New_York");
        assertThat(n.get("createdAt").asText()).startsWith(today.toString());
    }

    // ===== 254.10.2 owner 隔離（查詢必須帶 owner，兩條 join 路徑都涵蓋） =====

    @Test
    void 查詢一律帶ownerId且各自寫進自己的檔名() throws Exception {
        LocalDate today = LocalDate.now(TW);
        enabled(OWNER_A);
        enabled(OWNER_B);
        when(triggerRepo.findByOwnerAndCreatedAtInDay(eq(OWNER_A), any(), any())).thenReturn(List.of(
                trigger(11L, 101L, null, "2330", "台股", today.atTime(10, 0), today.atTime(10, 0))));
        when(triggerRepo.findByOwnerAndCreatedAtInDay(eq(OWNER_B), any(), any())).thenReturn(List.of(
                trigger(22L, null, 201L, "00878", "台股", today.atTime(11, 0), today.atTime(11, 0))));
        when(alertRepo.findById(101L)).thenReturn(Optional.of(alert(101L, OWNER_A, "PRICE_BELOW", "900")));
        when(alertRepo.findByGroupIdOrderByDisplayOrderAsc(201L)).thenReturn(List.of(
                alert(301L, OWNER_B, "KD_BELOW", "15"), alert(302L, OWNER_B, "PRICE_BELOW", "20")));

        service.exportForTrigger(OWNER_A);
        service.exportForTrigger(OWNER_B);

        JsonNode a = readJson(OWNER_A, today);
        JsonNode b = readJson(OWNER_B, today);
        assertThat(a.get("triggers")).hasSize(1);
        assertThat(a.get("triggers").get(0).get("stockCode").asText()).isEqualTo("2330");
        assertThat(b.get("triggers")).hasSize(1);
        assertThat(b.get("triggers").get(0).get("stockCode").asText()).isEqualTo("00878");
        // A 的檔案不得含 B 的標的，反之亦然
        assertThat(a.toString()).doesNotContain("00878");
        assertThat(b.toString()).doesNotContain("2330");
    }

    // ===== 254.10.3 群組觸發的條件文案為合併 label =====

    @Test
    void 群組觸發的condition為成員以且串接的合併label() throws Exception {
        LocalDate today = LocalDate.now(TW);
        enabled(OWNER_A);
        when(triggerRepo.findByOwnerAndCreatedAtInDay(eq(OWNER_A), any(), any())).thenReturn(List.of(
                trigger(33L, null, 201L, "2330", "台股", today.atTime(10, 0), today.atTime(10, 0))));
        when(alertRepo.findByGroupIdOrderByDisplayOrderAsc(201L)).thenReturn(List.of(
                alert(301L, OWNER_A, "KD_BELOW", "15"), alert(302L, OWNER_A, "PRICE_BELOW", "900")));

        service.exportForTrigger(OWNER_A);

        JsonNode n = readJson(OWNER_A, today).get("triggers").get(0);
        assertThat(n.get("source").asText()).isEqualTo("GROUP");
        assertThat(n.get("alertId").isNull()).isTrue();
        assertThat(n.get("groupId").asLong()).isEqualTo(201L);
        // 分隔符字面值為「 且 」（前後各一個半形空白），與警示頁／觀察頁／email／補發四條路徑同源
        assertThat(n.get("condition").asText()).isEqualTo("K 值低於 15 且 股價低於 900");
    }

    // ===== 254.10.7 JSON 形狀：數值為 number、指標不足為 null =====

    @Test
    void 數值輸出為number且指標不足時為null而非0() throws Exception {
        LocalDate today = LocalDate.now(TW);
        enabled(OWNER_A);
        StockAlertTrigger t = trigger(44L, 101L, null, "2330", "台股",
                today.atTime(10, 0), today.atTime(10, 0));
        t.setPrice(new BigDecimal("92.0000"));
        t.setQuarterlyMa(new BigDecimal("102.3200"));
        t.setMonthlyMa(null);   // 資料不足
        when(triggerRepo.findByOwnerAndCreatedAtInDay(eq(OWNER_A), any(), any())).thenReturn(List.of(t));
        when(alertRepo.findById(101L)).thenReturn(Optional.of(alert(101L, OWNER_A, "PRICE_BELOW", "100")));

        service.exportForTrigger(OWNER_A);

        JsonNode n = readJson(OWNER_A, today).get("triggers").get(0);
        assertThat(n.get("price").isNumber()).isTrue();
        assertThat(n.get("price").decimalValue()).isEqualByComparingTo("92.0000");
        assertThat(n.get("quarterlyMa").isNumber()).isTrue();
        assertThat(n.get("monthlyMa").isNull()).isTrue();   // 不得以 0 或空字串充數
        assertThat(n.get("annualMa").isNull()).isTrue();
    }

    @Test
    void 當日無觸發時仍寫出合法JSON且triggers為空陣列() throws Exception {
        LocalDate today = LocalDate.now(TW);
        when(settingRepo.findByOwnerUserId(OWNER_A)).thenReturn(Optional.of(
                setting(OWNER_A, false, false)));   // 刻意未啟用：run-now 不看 enabled
        when(triggerRepo.findByOwnerAndCreatedAtInDay(eq(OWNER_A), any(), any())).thenReturn(List.of());

        StockAlertTriggerExportService.ExportResult r = service.runNow(OWNER_A);

        assertThat(r.triggerCount()).isZero();
        JsonNode root = readJson(OWNER_A, today);
        assertThat(root.get("triggerCount").asInt()).isZero();
        assertThat(root.get("triggers").isArray()).isTrue();
        assertThat(root.get("triggers")).isEmpty();
    }

    // ===== 254.10.6 Drive 合併去抖 =====

    @Test
    void 間隔內連續觸發只上傳一次() {
        enabled(OWNER_A, true);
        when(triggerRepo.findByOwnerAndCreatedAtInDay(eq(OWNER_A), any(), any())).thenReturn(List.of());
        when(gdrive.syncQuietly(anyLong(), any(), any()))
                .thenReturn(new GdriveOutputSupport.SyncResult("成功：x（1 bytes）", "GDriveOutput:x"));
        service.setDebounceIntervalMillis(3_000L);   // 拉長到足以涵蓋三次連續觸發

        service.exportForTrigger(OWNER_A);
        service.exportForTrigger(OWNER_A);
        service.exportForTrigger(OWNER_A);

        // 三次觸發合併成一次上傳（本機那三次都已即時寫完）
        await().atMost(java.time.Duration.ofSeconds(10))
                .untilAsserted(() -> verify(gdrive, times(1)).syncQuietly(eq(OWNER_A), any(), any()));
    }

    @Test
    void 尾端補跑_最後一次觸發即使落在間隔內也必須上傳() {
        // 這條是「只標記 pending、由已排入任務完成後補跑」那個資料遺失寫法的唯一探針：
        // 壞實作下第二次觸發只會設 pending，而第一個任務早已結束，沒人回頭看它 → 只有 1 次 copyTo
        enabled(OWNER_A, true);
        when(triggerRepo.findByOwnerAndCreatedAtInDay(eq(OWNER_A), any(), any())).thenReturn(List.of());
        when(gdrive.syncQuietly(anyLong(), any(), any()))
                .thenReturn(new GdriveOutputSupport.SyncResult("成功：x（1 bytes）", "GDriveOutput:x"));
        service.setDebounceIntervalMillis(500L);

        service.exportForTrigger(OWNER_A);   // 立刻上傳（距上次上傳已超過間隔）
        await().atMost(java.time.Duration.ofSeconds(5))
                .untilAsserted(() -> verify(gdrive, times(1)).syncQuietly(eq(OWNER_A), any(), any()));

        service.exportForTrigger(OWNER_A);   // 落在間隔內，且此刻沒有任何執行中的任務
        await().atMost(java.time.Duration.ofSeconds(5))
                .untilAsserted(() -> verify(gdrive, times(2)).syncQuietly(eq(OWNER_A), any(), any()));
    }

    @Test
    void 被合併的那幾次不得碰兩個Drive狀態欄() {
        StockAlertExportSetting s = setting(OWNER_A, true, true);
        when(settingRepo.findByOwnerUserId(OWNER_A)).thenReturn(Optional.of(s));
        when(triggerRepo.findByOwnerAndCreatedAtInDay(eq(OWNER_A), any(), any())).thenReturn(List.of());
        when(gdrive.syncQuietly(anyLong(), any(), any()))
                .thenReturn(new GdriveOutputSupport.SyncResult("成功：x（1 bytes）", "GDriveOutput:x"));
        service.setDebounceIntervalMillis(30_000L);

        // 第一次觸發：距上次上傳已超過間隔 → 立刻上傳（延遲 0），這一次寫狀態欄是正確的
        service.exportForTrigger(OWNER_A);
        await().atMost(java.time.Duration.ofSeconds(10))
                .untilAsserted(() -> verify(gdrive, times(1)).syncQuietly(eq(OWNER_A), any(), any()));

        // 擺一個哨兵值，代表「前一次真正成功的落點與時刻」
        LocalDateTime sentinel = LocalDateTime.of(2026, 7, 28, 20, 30);
        s.setGdriveLastRunAt(sentinel);
        s.setGdriveLastStatus("成功：GDriveOutput:投資理財/資產管理/舊檔.json（12345 bytes）");

        // 第二次觸發落在間隔內 → 只排定、尚未上傳
        service.exportForTrigger(OWNER_A);

        // 被合併期間兩欄必須原封不動（寫「跳過」「失敗」或任何字樣都會覆蓋掉真正的成功紀錄）
        verify(gdrive, times(1)).syncQuietly(eq(OWNER_A), any(), any());
        assertThat(s.getGdriveLastRunAt()).isEqualTo(sentinel);
        assertThat(s.getGdriveLastStatus()).contains("成功：").contains("舊檔.json");
    }

    // ===== 254.10.6.1 run-now 的路徑語意 =====

    @Test
    void runNow不看enabled且不套去抖() throws Exception {
        StockAlertExportSetting s = setting(OWNER_A, false, true);   // enabled=false，但 Drive 開著
        when(settingRepo.findByOwnerUserId(OWNER_A)).thenReturn(Optional.of(s));
        when(triggerRepo.findByOwnerAndCreatedAtInDay(eq(OWNER_A), any(), any())).thenReturn(List.of());
        when(gdrive.syncQuietly(anyLong(), any(), any()))
                .thenReturn(new GdriveOutputSupport.SyncResult("成功：x（1 bytes）", "GDriveOutput:x"));
        service.setDebounceIntervalMillis(60_000L);

        StockAlertTriggerExportService.ExportResult r = service.runNow(OWNER_A);

        assertThat(r.file()).exists();
        assertThat(s.isEnabled()).isFalse();                       // run-now 不改變 enabled
        verify(gdrive, times(1)).syncQuietly(eq(OWNER_A), any(), any());   // 同步上傳、未被去抖壓住
        assertThat(r.gdrivePath()).isEqualTo("GDriveOutput:x");
    }

    @Test
    void runNow不更新去抖時鐘_按一次立即匯出不會讓觸發路徑靜默停一個間隔() {
        enabled(OWNER_A, true);
        when(triggerRepo.findByOwnerAndCreatedAtInDay(eq(OWNER_A), any(), any())).thenReturn(List.of());
        when(gdrive.syncQuietly(anyLong(), any(), any()))
                .thenReturn(new GdriveOutputSupport.SyncResult("成功：x（1 bytes）", "GDriveOutput:x"));
        service.setDebounceIntervalMillis(30_000L);

        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.runNow(OWNER_A));
        service.exportForTrigger(OWNER_A);

        // run-now 那次不更新 lastUploadAt，故觸發路徑的排定延遲為 0、立刻上傳（共兩次）
        await().atMost(java.time.Duration.ofSeconds(10))
                .untilAsserted(() -> verify(gdrive, times(2)).syncQuietly(eq(OWNER_A), any(), any()));
    }

    // ===== 254.10.5 匯出失敗不影響觸發本身 =====

    @Test
    void 匯出擲例外時exportForTrigger不回拋() {
        when(settingRepo.findByOwnerUserId(OWNER_A)).thenThrow(new RuntimeException("DB 掛了"));

        // recordTrigger / recordGroupTrigger 依賴這一點：匯出失敗不得中止整輪檢查
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.exportForTrigger(OWNER_A));
    }

    @Test
    void 本機寫檔失敗時完全不上傳() {
        StockAlertExportSetting s = setting(OWNER_A, true, true);
        s.setOutputSubpath("nope/../../../etc");   // 跳脫基底 → resolveDir 擋下
        when(settingRepo.findByOwnerUserId(OWNER_A)).thenReturn(Optional.of(s));
        when(triggerRepo.findByOwnerAndCreatedAtInDay(eq(OWNER_A), any(), any())).thenReturn(List.of());
        when(gdrive.skipped(any())).thenReturn("跳過：本輪未產生本機檔案");

        service.exportForTrigger(OWNER_A);

        verify(gdrive, never()).syncQuietly(anyLong(), any(), any());   // 絕不上傳前一次的舊檔
        assertThat(s.getLastRunStatus()).startsWith("失敗：");
    }

    // ===== 輔助 =====

    private void enabled(long owner) {
        enabled(owner, false);
    }

    private void enabled(long owner, boolean gdrive) {
        when(settingRepo.findByOwnerUserId(owner)).thenReturn(Optional.of(setting(owner, true, gdrive)));
    }

    private static StockAlertExportSetting setting(long owner, boolean enabled, boolean gdriveEnabled) {
        return StockAlertExportSetting.builder()
                .ownerUserId(owner)
                .enabled(enabled)
                .outputSubpath("input")
                .gdriveEnabled(gdriveEnabled)
                .gdriveSubpath(gdriveEnabled ? "投資理財/資產管理" : null)
                .build();
    }

    private static StockAlertTrigger trigger(Long id, Long alertId, Long groupId, String code, String market,
                                              LocalDateTime triggeredAt, LocalDateTime createdAt) {
        return StockAlertTrigger.builder()
                .id(id).alertId(alertId).groupId(groupId)
                .stockCode(code).market(market)
                .triggeredAt(triggeredAt).createdAt(createdAt)
                .price(new BigDecimal("100.0000"))
                .build();
    }

    private static StockAlert alert(Long id, Long owner, String type, String threshold) {
        return StockAlert.builder()
                .id(id).ownerUserId(owner)
                .stockCode("2330").market("台股")
                .alertType(type).threshold(new BigDecimal(threshold))
                .build();
    }

    private List<Path> listJson() {
        try (var s = Files.walk(tmp)) {
            return s.filter(p -> p.getFileName().toString().endsWith(".json")).toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    private JsonNode readJson(long owner, LocalDate day) throws Exception {
        Path f = tmp.resolve("input").resolve("alert_triggers_" + owner + "_"
                + day.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")) + ".json");
        assertThat(f).exists();
        return mapper.readTree(Files.readString(f));
    }
}
