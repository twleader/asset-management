package com.steven.assets.service.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.TradingRadarExportService;
import com.steven.assets.service.TradingRadarSnapshotStore;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;

/**
 * 交易雷達三分頁改走 {@link ExportDoc} 之後的零回歸與雙格式驗證（Requirement 55 / Task 271）。
 *
 * <p>重點：三分頁只有「快照索引」有標題列（且為 WARN／SECTION_12 條件樣式，本服務的 section 是
 * <b>12pt</b> 不是 13pt）；{@code bool()}／{@code list()} 改放語意值後，Excel 呈現必須不變、
 * JSON 才拿得到 boolean 與陣列。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TradingRadarDualFormatTest {

    @Mock private TradingRadarSnapshotStore store;
    @Mock private CurrentUserContext currentUserContext;

    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonDocRenderer jsonRenderer = new JsonDocRenderer(mapper);
    private TradingRadarExportService service;

    @BeforeEach
    void setUp() {
        service = new TradingRadarExportService(store, currentUserContext, new ExcelDocRenderer());
    }

    // ===== 零回歸 =====

    @Test
    @DisplayName("有快照時逐列逐格與 origin/main 相同（含 WARN 標題列、bool／list 的 Excel 呈現）")
    void 零回歸_有快照() throws Exception {
        when(store.range(1L, 0L, 1L)).thenReturn(
                new TradingRadarSnapshotStore.SnapshotRange(List.of(snapshotNode()), 2, 1));
        Workbook actual = GoldenWorkbooks.read(service.exportForOwner(1L, 0L, 1L));
        GoldenWorkbooks.assertSame(GoldenWorkbooks.golden("radar"), actual);
    }

    @Test
    @DisplayName("零快照時逐列逐格與 origin/main 相同：三分頁都在、各含表頭")
    void 零回歸_零快照() throws Exception {
        when(store.range(2L, 0L, 1L)).thenReturn(
                new TradingRadarSnapshotStore.SnapshotRange(List.of(), 0, 0));
        Workbook actual = GoldenWorkbooks.read(service.exportForOwner(2L, 0L, 1L));
        GoldenWorkbooks.assertSame(GoldenWorkbooks.golden("radar_empty"), actual);
        assertThat(actual.getNumberOfSheets()).isEqualTo(3);
    }

    // ===== 版面關鍵點 =====

    @Test
    @DisplayName("只有「快照索引」有標題列；「大盤總覽」「個股決策」第 0 列就是表頭列")
    void 只有快照索引有標題列() throws Exception {
        when(store.range(1L, 0L, 1L)).thenReturn(
                new TradingRadarSnapshotStore.SnapshotRange(List.of(snapshotNode()), 2, 1));
        Workbook wb = GoldenWorkbooks.read(service.exportForOwner(1L, 0L, 1L));

        Sheet idx = wb.getSheet("快照索引");
        assertThat(idx.getRow(0).getCell(0).getStringCellValue()).startsWith("查得 1 筆");
        assertThat(idx.getRow(1).getCell(0).getStringCellValue()).isEqualTo("快照時間");

        assertThat(wb.getSheet("大盤總覽").getRow(0).getCell(0).getStringCellValue()).isEqualTo("快照時間");
        assertThat(wb.getSheet("個股決策").getRow(0).getCell(0).getStringCellValue()).isEqualTo("快照時間");
    }

    @Test
    @DisplayName("缺漏時標題列走 WARN（紅字），無缺漏時走 SECTION_12（粗體 12pt，不是 13pt）")
    void 標題列的條件樣式與字級() throws Exception {
        when(store.range(1L, 0L, 1L)).thenReturn(
                new TradingRadarSnapshotStore.SnapshotRange(List.of(snapshotNode()), 2, 1));
        Workbook warn = GoldenWorkbooks.read(service.exportForOwner(1L, 0L, 1L));
        var wf = warn.getFontAt(warn.getSheet("快照索引").getRow(0).getCell(0).getCellStyle().getFontIndex());
        assertThat(wf.getColor()).isEqualTo(org.apache.poi.ss.usermodel.IndexedColors.RED.getIndex());

        when(store.range(3L, 0L, 1L)).thenReturn(
                new TradingRadarSnapshotStore.SnapshotRange(List.of(snapshotNode()), 1, 0));
        Workbook ok = GoldenWorkbooks.read(service.exportForOwner(3L, 0L, 1L));
        var sf = ok.getFontAt(ok.getSheet("快照索引").getRow(0).getCell(0).getCellStyle().getFontIndex());
        assertThat(sf.getFontHeightInPoints())
                .as("本服務的 section 是 12pt——與 ExcelExportService 的 13pt 合併會靜默改字級")
                .isEqualTo((short) 12);
        assertThat(sf.getBold()).isTrue();
    }

    @Test
    @DisplayName("ownerId 為 null 時走空 SnapshotRange，不擲 NPE、仍產出含表頭的合法檔")
    void ownerId為null不擲NPE() {
        when(currentUserContext.getEffectiveUserId()).thenReturn(null);
        assertThatCode(() -> {
            Workbook wb = GoldenWorkbooks.read(service.export("2026-07-30T00:00:00", "2026-07-31T00:00:00"));
            assertThat(wb.getNumberOfSheets()).isEqualTo(3);
            assertThat(wb.getSheet("快照索引").getRow(0).getCell(0).getStringCellValue()).contains("查無交易雷達快照");
        }).doesNotThrowAnyException();
    }

    // ===== bool／list 的兩種呈現 =====

    @Test
    @DisplayName("boolean 欄：Excel 是「是」／「否」，JSON 是 boolean；缺值 Excel 空字串格、JSON null")
    void 布林兩種呈現() throws Exception {
        when(store.range(1L, 0L, 1L)).thenReturn(
                new TradingRadarSnapshotStore.SnapshotRange(List.of(snapshotNode()), 2, 1));

        Sheet m = GoldenWorkbooks.read(service.exportForOwner(1L, 0L, 1L)).getSheet("大盤總覽");
        assertThat(m.getRow(1).getCell(4).getStringCellValue()).as("資料完整=true").isEqualTo("是");
        assertThat(m.getRow(1).getCell(5).getStringCellValue()).as("stale=false").isEqualTo("否");

        JsonNode rows = mapper.readTree(jsonRenderer.render(service.radarDoc(1L, 0L, 1L)))
                .at("/sheets/1/tables/0/rows");
        assertThat(rows.get(0).get("資料完整").isBoolean()).isTrue();
        assertThat(rows.get(0).get("資料完整").asBoolean()).isTrue();
        assertThat(rows.get(0).get("stale").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("陣列欄：Excel 是換行串接字串，JSON 是字串陣列；缺欄位 Excel 空字串格、JSON null")
    void 陣列兩種呈現() throws Exception {
        when(store.range(1L, 0L, 1L)).thenReturn(
                new TradingRadarSnapshotStore.SnapshotRange(List.of(snapshotNodeWithArrays()), 2, 1));

        Sheet m = GoldenWorkbooks.read(service.exportForOwner(1L, 0L, 1L)).getSheet("大盤總覽");
        assertThat(m.getRow(1).getCell(18).getStringCellValue()).isEqualTo("均線多頭排列\n量增");

        JsonNode rows = mapper.readTree(jsonRenderer.render(service.radarDoc(1L, 0L, 1L)))
                .at("/sheets/1/tables/0/rows");
        JsonNode signals = rows.get(0).get("支持訊號");
        assertThat(signals.isArray()).isTrue();
        assertThat(signals).hasSize(2);
        assertThat(signals.get(0).asText()).isEqualTo("均線多頭排列");

        // fixture 的 market 沒有 reasons 以外的陣列欄之一 → 缺欄位時 Excel 空字串格、JSON null
        Sheet s = GoldenWorkbooks.read(service.exportForOwner(1L, 0L, 1L)).getSheet("個股決策");
        assertThat(s.getRow(1).getCell(29).getCellType()).isEqualTo(CellType.STRING);
        assertThat(s.getRow(1).getCell(29).getStringCellValue()).isEmpty();
        JsonNode stockRows = mapper.readTree(jsonRenderer.render(service.radarDoc(1L, 0L, 1L)))
                .at("/sheets/2/tables/0/rows");
        assertThat(stockRows.get(0).get("逆勢條件").isNull()).isTrue();
    }

    @Test
    @DisplayName("txt() 欄位維持空字串（Requirement 55 具名例外一）：Excel 空字串格、JSON 也是空字串")
    void txt欄位的具名例外() throws Exception {
        when(store.range(1L, 0L, 1L)).thenReturn(
                new TradingRadarSnapshotStore.SnapshotRange(List.of(snapshotNode()), 2, 1));

        // fixture 的 market 沒有 quarterlyConfirmation → txt() 回 ""
        Sheet m = GoldenWorkbooks.read(service.exportForOwner(1L, 0L, 1L)).getSheet("大盤總覽");
        assertThat(m.getRow(1).getCell(14).getCellType()).isEqualTo(CellType.STRING);
        assertThat(m.getRow(1).getCell(14).getStringCellValue()).isEmpty();

        JsonNode rows = mapper.readTree(jsonRenderer.render(service.radarDoc(1L, 0L, 1L)))
                .at("/sheets/1/tables/0/rows");
        assertThat(rows.get(0).get("季線確認").asText())
                .as("具名例外一：改成 null 會讓 Excel 由空字串格變 BLANK 格").isEmpty();
    }

    // ===== fixture（與 golden 產生器逐字相同）=====

    /**
     * 帶 {@code reasons}／{@code risks} 陣列欄的獨立 fixture。
     * <b>不與 golden 比對</b>——golden 的 fixture 刻意沒有這兩欄（驗 {@code list()} 缺欄位時的行為），
     * 在這裡另建一份才驗得到「有陣列時的兩種呈現」。
     */
    private static com.fasterxml.jackson.databind.JsonNode snapshotNodeWithArrays() {
        ObjectNode root = (ObjectNode) snapshotNode();
        ObjectNode m = (ObjectNode) root.path("market");
        m.putArray("reasons").add("均線多頭排列").add("量增");
        m.putArray("risks").add("KD 偏高");
        return root;
    }

    private static com.fasterxml.jackson.databind.JsonNode snapshotNode() {
        ObjectMapper M = new ObjectMapper();
        ObjectNode root = M.createObjectNode();
        root.put("generatedAt", "2026-07-31T13:30:00");
        root.put("ruleVersion", "TW_RULES_V6");
        root.put("skippedNonTwStocks", 1);
        ObjectNode m = root.putObject("market");
        m.put("regime", "BULL"); m.put("regimeLabel", "多頭");
        m.put("score", 72.5); m.put("stale", false);
        m.put("dataComplete", true); m.put("intraday", true);
        m.put("liveUpdatedAt", "2026-07-31T13:29:00");
        m.put("asOfDate", "2026-07-30"); m.put("latestPoint", 23050.0);
        m.put("changePct", 0.42);
        m.put("ma20", 22800.0); m.put("ma60", 22500.0); m.put("ma240", 21000.0);
        m.put("quarterlyConfirmed", true); m.put("annualConfirmed", false);
        m.put("k", 68.1); m.put("d", 61.2);
        m.putArray("supportSignals").add("均線多頭排列").add("量增");
        m.putArray("riskWarnings").add("KD 偏高");
        var stocks = root.putArray("stocks");
        ObjectNode s1 = stocks.addObject();
        s1.put("code", "2330"); s1.put("name", "台積電"); s1.put("market", "TW");
        s1.put("assetClass", "STOCK"); s1.put("held", true); s1.put("adjusted", false);
        s1.put("action", "HOLD"); s1.put("actionLabel", "續抱"); s1.put("score", 61.0);
        s1.put("price", 1105.0); s1.put("changePct", 0.45);
        s1.put("dataComplete", true);
        s1.putArray("supportSignals").add("季線之上");
        s1.putArray("riskWarnings");
        return root;
    }
}
