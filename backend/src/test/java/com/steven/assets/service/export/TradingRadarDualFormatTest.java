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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.when;

/**
 * 交易雷達四分頁改走 {@link ExportDoc} 後的雙格式與 V11 欄位驗證。
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
        assertSameMapped(GoldenWorkbooks.golden("radar"), actual, "快照索引", c -> c);
        assertThat(actual.getSheet("大盤總覽")).isNotNull();
        assertThat(actual.getSheet("個股決策")).isNotNull();
        assertThat(actual.getSheet("台美公開資訊")).isNotNull();
    }

    @Test
    @DisplayName("零快照時逐列逐格與 origin/main 相同：三分頁都在、各含表頭")
    void 零回歸_零快照() throws Exception {
        when(store.range(2L, 0L, 1L)).thenReturn(
                new TradingRadarSnapshotStore.SnapshotRange(List.of(), 0, 0));
        Workbook actual = GoldenWorkbooks.read(service.exportForOwner(2L, 0L, 1L));
        assertSameMapped(GoldenWorkbooks.golden("radar_empty"), actual, "快照索引", c -> c);
        assertThat(actual.getSheet("大盤總覽").getRow(0)).isNotNull();
        assertThat(actual.getSheet("個股決策").getRow(0)).isNotNull();
        assertThat(actual.getSheet("台美公開資訊").getRow(0)).isNotNull();
        assertThat(actual.getNumberOfSheets()).isEqualTo(4);
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
            Workbook wb = GoldenWorkbooks.read(new ExcelDocRenderer().render(
                    service.manualDoc("2026-07-30T00:00:00", "2026-07-31T00:00:00").doc()));
            assertThat(wb.getNumberOfSheets()).isEqualTo(4);
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
        assertThat(m.getRow(1).getCell(42).getStringCellValue()).isEqualTo("均線多頭排列\n量增");

        JsonNode rows = mapper.readTree(jsonRenderer.render(service.radarDoc(1L, 0L, 1L)))
                .at("/sheets/1/tables/0/rows");
        JsonNode signals = rows.get(0).get("支持訊號");
        assertThat(signals.isArray()).isTrue();
        assertThat(signals).hasSize(2);
        assertThat(signals.get(0).asText()).isEqualTo("均線多頭排列");

        // fixture 的 market 沒有 reasons 以外的陣列欄之一 → 缺欄位時 Excel 空字串格、JSON null
        // index 87＝基本面／產業欄補上 PE 值後的「逆勢條件」。
        Sheet s = GoldenWorkbooks.read(service.exportForOwner(1L, 0L, 1L)).getSheet("個股決策");
        assertThat(s.getRow(0).getCell(87).getStringCellValue())
                .as("鎖住欄序：這個 index 一旦被插欄推移，下面兩條斷言會驗到別的欄位").isEqualTo("逆勢條件");
        assertThat(s.getRow(1).getCell(87).getCellType()).isEqualTo(CellType.STRING);
        assertThat(s.getRow(1).getCell(87).getStringCellValue()).isEmpty();
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
        assertThat(m.getRow(1).getCell(16).getCellType()).isEqualTo(CellType.STRING);
        assertThat(m.getRow(1).getCell(16).getStringCellValue()).isEmpty();

        JsonNode rows = mapper.readTree(jsonRenderer.render(service.radarDoc(1L, 0L, 1L)))
                .at("/sheets/1/tables/0/rows");
        assertThat(rows.get(0).get("季線確認").asText())
                .as("具名例外一：改成 null 會讓 Excel 由空字串格變 BLANK 格").isEmpty();
    }

    // ===== Task 281：週線 MA5 ＋ 走勢圖指標選單的 14 個值 =====

    private static final List<String> MARKET_HEADERS_V11 = List.of(
            "快照時間", "regime", "中文", "分數", "資料完整", "stale", "盤中即時",
            "即時更新時間", "完成日K", "最新點位", "漲跌%", "行情狀態",
            "週線MA5", "MA20", "MA60", "MA240", "季線確認", "年線確認", "K", "D",
            "J9", "K3D2", "RSV", "EMA12", "EMA26", "DIF", "MACD", "OSC",
            "RSI5", "RSI10", "BIAS10", "BIAS20", "BIAS10-BIAS20", "W%R9",
            "大盤量比", "大盤成交金額比", "量能完成日", "NASDAQ漲跌%", "SOX漲跌%",
            "美股科技綜合%", "美股科技完成日", "美股科技可用", "支持訊號", "風險提醒");

    private static final List<String> STOCK_HEADERS_V11 = List.of(
            "快照時間", "代碼", "名稱", "市場", "資產類別", "持有", "還原權息",
            "短期動作", "短期動作中文", "短期分數", "中期動作", "中期動作中文", "中期分數",
            "短中期分歧", "獲利了結確認", "逆勢狀態", "逆勢中文", "現價", "漲跌%", "行情狀態", "行情更新", "完成日K",
            "週線MA5", "MA20", "MA60", "MA240", "月線確認", "季線確認", "年線確認", "K", "D",
            "J9", "K3D2", "RSV", "EMA12", "EMA26", "DIF", "MACD", "OSC",
            "RSI5", "RSI10", "BIAS10", "BIAS20", "BIAS10-BIAS20", "W%R9",
            "個股量比", "匯率分位", "匯率完成日", "底層幣別", "資料完整",
            "時機", "季線乖離%", "52週位置", "折溢價%",
            "基本面適用", "基本面覆蓋(0-4)",
            "EPS TTM年增%", "EPS來源", "EPS來源網址", "EPS資料時點",
            "近似ROE%", "ROE來源", "ROE來源網址", "ROE資料時點",
            "近3月營收年增%", "營收來源", "營收來源網址", "營收資料時點",
            "PE值", "PE自身分位", "PE可信虧損", "估值來源", "估值來源網址", "估值資料時點",
            "產業", "產業營收年增%", "產業公司數", "產業資料年月", "產業來源", "產業來源網址", "產業資料時點",
            "public_info_*個股證據", "public_info_*產業證據",
            "短期支持訊號", "短期風險提醒", "中期支持訊號", "中期風險提醒", "逆勢條件", "逆勢風險",
            "短期證據信心", "中期證據信心", "短期下檔風險", "中期下檔風險",
            "短期風險覆蓋", "中期風險覆蓋", "中期候選動作", "短期候選動作",
            "證據閘門原因", "下一配息日", "配息證據狀態", "配息已知時間",
            "PB值", "殖利率%", "PB自身分位", "殖利率自身分位", "估值Composite", "估值覆蓋",
            "EPS趨勢", "ROE近似fallback",
            "資產分類來源", "工具類型", "工具類型來源", "工具類型完整",
            "股票風格", "股票風格來源", "股票風格完整",
            "債券期別", "債券期別來源", "債券期別完整",
            "報價幣別", "報價幣別來源", "報價幣別完整",
            "輪廓底層幣別", "底層幣別來源", "幣別資料完整",
            "輪廓完整", "輪廓缺漏",
            "波動60日標準差比", "波動資料時點", "波動來源",
            "接受價格時點", "接受價格來源", "接受價格品質", "即時價採用",
            "折溢價時點", "折溢價來源", "折溢價stale",
            "利率證據狀態", "利率證據來源", "利率缺漏原因",
            "利率批次ID", "利率批次完整", "利率Tenor", "利率值%", "利率曲線日",
            "利率Provider", "利率可得時間", "利率可得基礎", "利率抓取時間",
            "利率落後日數", "利率時效說明", "利率來源Manifest", "資產分類完整", "底層幣別完整",
            "PE適用狀態", "PE Provider", "PE來源網址", "PE可得時間", "PE資料日期", "PE缺漏原因",
            "PB適用狀態", "PB Provider", "PB來源網址", "PB可得時間", "PB資料日期", "PB缺漏原因",
            "殖利率適用狀態", "殖利率 Provider", "殖利率來源網址", "殖利率可得時間", "殖利率資料日期", "殖利率缺漏原因");

    private static List<String> headerRow(Sheet sheet) {
        List<String> out = new ArrayList<>();
        Row r = sheet.getRow(0);
        for (int i = 0; i < r.getLastCellNum(); i++) out.add(r.getCell(i).getStringCellValue());
        return out;
    }

    /**
     * 匯出端讀快照 JSON 的 key 是<b>字串</b>，與 DTO 之間沒有任何編譯期綁定——
     * 有人改了 {@code TradingRadarDto.ExtendedIndicators} 的元件名，匯出會靜默變成 14 個空白格
     * 而所有測試照樣綠。這條把兩者釘在一起。
     */
    @Test
    @DisplayName("Task 281：匯出的 14 個擴充指標欄名必須逐字等於 DTO 的 record 元件名（含順序）")
    void 匯出欄名與DTO元件名綁定() {
        List<String> dtoComponents = new ArrayList<>();
        for (java.lang.reflect.RecordComponent c
                : com.steven.assets.dto.TradingRadarDto.ExtendedIndicators.class.getRecordComponents()) {
            dtoComponents.add(c.getName());
        }
        assertThat(dtoComponents).containsExactly(
                "j9", "k3d2", "rsv", "ema12", "ema26", "dif", "macd", "osc",
                "rsi5", "rsi10", "bias10", "bias20", "b10b20", "wr9");
        // 表頭與 key 一一對應（順序即欄序）
        assertThat(MARKET_HEADERS_V11.subList(20, 34)).containsExactlyElementsOf(EXT_HEADERS_EXPECTED);
        assertThat(STOCK_HEADERS_V11.subList(31, 45)).containsExactlyElementsOf(EXT_HEADERS_EXPECTED);
        assertThat(EXT_HEADERS_EXPECTED).hasSameSizeAs(dtoComponents);
    }

    private static final List<String> EXT_HEADERS_EXPECTED = List.of(
            "J9", "K3D2", "RSV", "EMA12", "EMA26", "DIF", "MACD", "OSC",
            "RSI5", "RSI10", "BIAS10", "BIAS20", "BIAS10-BIAS20", "W%R9");

    private static final List<String> DETAIL_HEADERS_EXPECTED = List.of(
            "PB值", "殖利率%", "PB自身分位", "殖利率自身分位", "估值Composite", "估值覆蓋",
            "EPS趨勢", "ROE近似fallback",
            "資產分類來源", "工具類型", "工具類型來源", "工具類型完整",
            "股票風格", "股票風格來源", "股票風格完整",
            "債券期別", "債券期別來源", "債券期別完整",
            "報價幣別", "報價幣別來源", "報價幣別完整",
            "輪廓底層幣別", "底層幣別來源", "幣別資料完整",
            "輪廓完整", "輪廓缺漏",
            "波動60日標準差比", "波動資料時點", "波動來源",
            "接受價格時點", "接受價格來源", "接受價格品質", "即時價採用",
            "折溢價時點", "折溢價來源", "折溢價stale",
            "利率證據狀態", "利率證據來源", "利率缺漏原因",
            "利率批次ID", "利率批次完整", "利率Tenor", "利率值%", "利率曲線日",
            "利率Provider", "利率可得時間", "利率可得基礎", "利率抓取時間",
            "利率落後日數", "利率時效說明", "利率來源Manifest", "資產分類完整", "底層幣別完整");

    private static final List<String> VALUATION_COMPONENT_HEADERS_EXPECTED = List.of(
            "PE適用狀態", "PE Provider", "PE來源網址", "PE可得時間", "PE資料日期", "PE缺漏原因",
            "PB適用狀態", "PB Provider", "PB來源網址", "PB可得時間", "PB資料日期", "PB缺漏原因",
            "殖利率適用狀態", "殖利率 Provider", "殖利率來源網址", "殖利率可得時間", "殖利率資料日期", "殖利率缺漏原因");

    @Test
    @DisplayName("兩張分頁的表頭逐字等於預期的 V11 欄位清單")
    void 表頭逐字與欄數() throws Exception {
        when(store.range(1L, 0L, 1L)).thenReturn(
                new TradingRadarSnapshotStore.SnapshotRange(List.of(snapshotNode()), 1, 0));
        Workbook wb = GoldenWorkbooks.read(service.exportForOwner(1L, 0L, 1L));

        assertThat(headerRow(wb.getSheet("大盤總覽")))
                .as("大盤總覽 44 欄").containsExactlyElementsOf(MARKET_HEADERS_V11);
        assertThat(headerRow(wb.getSheet("個股決策")))
                .as("個股決策 172 欄").containsExactlyElementsOf(STOCK_HEADERS_V11);
        int valuationStart = STOCK_HEADERS_V11.size() - VALUATION_COMPONENT_HEADERS_EXPECTED.size();
        int detailStart = valuationStart - DETAIL_HEADERS_EXPECTED.size();
        int evidenceStart = detailStart - 12;
        assertThat(STOCK_HEADERS_V11.subList(evidenceStart, detailStart))
                .containsExactly(
                        "短期證據信心", "中期證據信心", "短期下檔風險", "中期下檔風險",
                        "短期風險覆蓋", "中期風險覆蓋", "中期候選動作", "短期候選動作",
                        "證據閘門原因", "下一配息日", "配息證據狀態", "配息已知時間");
        assertThat(STOCK_HEADERS_V11.subList(detailStart, valuationStart))
                .containsExactlyElementsOf(DETAIL_HEADERS_EXPECTED);
        assertThat(STOCK_HEADERS_V11.subList(valuationStart, STOCK_HEADERS_V11.size()))
                .containsExactlyElementsOf(VALUATION_COMPONENT_HEADERS_EXPECTED);
        assertThat(new HashSet<>(STOCK_HEADERS_V11)).hasSameSizeAs(STOCK_HEADERS_V11);
        assertThat(wb.getSheet("快照索引").getRow(1).getLastCellNum())
                .as("快照索引一欄都不動").isEqualTo((short) 8);
    }

    @Test
    @DisplayName("Task 315：18 個逐分量欄位在同一 ExportDoc 中保持 header／format／row 對齊並雙格式同值")
    void 估值逐分量雙格式與欄數鎖步() throws Exception {
        when(store.range(15L, 0L, 1L)).thenReturn(
                new TradingRadarSnapshotStore.SnapshotRange(
                        List.of(snapshotNodeWithValuationProvenance()), 1, 0));

        ExportDoc doc = service.radarDoc(15L, 0L, 1L);
        ExportDoc.Sheet stockDoc = doc.sheets().stream()
                .filter(sheet -> "個股決策".equals(sheet.name())).findFirst().orElseThrow();
        ExportDoc.Table table = stockDoc.blocks().stream()
                .filter(ExportDoc.Table.class::isInstance).map(ExportDoc.Table.class::cast)
                .findFirst().orElseThrow();
        assertThat(table.headers()).hasSize(172);
        assertThat(table.columnFormats()).hasSameSizeAs(table.headers());
        assertThat(table.rows()).allSatisfy(row -> assertThat(row).hasSameSizeAs(table.headers()));
        assertThat(table.headers().subList(table.headers().size() - 18, table.headers().size()))
                .containsExactlyElementsOf(VALUATION_COMPONENT_HEADERS_EXPECTED);
        assertThat(new HashSet<>(table.headers())).hasSameSizeAs(table.headers());
        assertThat(table.columnFormats().subList(table.columnFormats().size() - 18,
                table.columnFormats().size())).containsExactly(
                        ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.LIST_LINES,
                        ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT,
                        ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.LIST_LINES,
                        ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT,
                        ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.LIST_LINES,
                        ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT);

        JsonNode jsonRow = mapper.readTree(jsonRenderer.render(doc))
                .at("/sheets/2/tables/0/rows").get(0);
        assertThat(jsonRow.get("PE適用狀態").asText()).isEqualTo("AVAILABLE");
        assertThat(jsonRow.get("PE Provider").asText()).isEqualTo("TWSE");
        assertThat(jsonRow.get("PE來源網址")).containsExactly(
                mapper.getNodeFactory().textNode("https://example.test/pe-1"),
                mapper.getNodeFactory().textNode("https://example.test/pe-2"));
        assertThat(jsonRow.get("PB適用狀態").asText()).isEqualTo("STALE");
        assertThat(jsonRow.get("PB Provider").asText()).isEqualTo("WANTGOO");
        assertThat(jsonRow.get("PB缺漏原因").asText()).isEqualTo("PB observation 已過期");
        assertThat(jsonRow.get("殖利率適用狀態").asText()).isEqualTo("MISSING");
        assertThat(jsonRow.get("殖利率 Provider").asText()).isEqualTo("FINMIND");
        assertThat(jsonRow.get("殖利率缺漏原因").asText()).isEqualTo("殖利率歷史不足 250 筆");

        Workbook workbook = GoldenWorkbooks.read(new ExcelDocRenderer().render(doc));
        Sheet sheet = workbook.getSheet("個股決策");
        assertThat(sheet.getRow(0).getLastCellNum()).isEqualTo((short) 172);
        assertThat(sheet.getRow(1).getLastCellNum()).isEqualTo((short) 172);
        assertThat(sheet.getRow(1).getCell(STOCK_HEADERS_V11.indexOf("PE來源網址"))
                .getStringCellValue()).isEqualTo("https://example.test/pe-1\nhttps://example.test/pe-2");
        assertThat(sheet.getRow(1).getCell(STOCK_HEADERS_V11.indexOf("PB資料日期"))
                .getStringCellValue()).isEqualTo("2026-08-06");
    }

    @Test
    @DisplayName("Task 315：VALUATION 整組不適用仍在 JSON／Excel 輸出三個具名狀態")
    void 估值整組不適用仍輸出三個具名狀態() throws Exception {
        when(store.range(16L, 0L, 1L)).thenReturn(
                new TradingRadarSnapshotStore.SnapshotRange(
                        List.of(snapshotNodeWithNotApplicableValuation()), 1, 0));

        ExportDoc doc = service.radarDoc(16L, 0L, 1L);
        JsonNode jsonRow = mapper.readTree(jsonRenderer.render(doc))
                .at("/sheets/2/tables/0/rows").get(0);
        assertThat(jsonRow.get("PE適用狀態").asText()).isEqualTo("NOT_APPLICABLE");
        assertThat(jsonRow.get("PB適用狀態").asText()).isEqualTo("NOT_APPLICABLE");
        assertThat(jsonRow.get("殖利率適用狀態").asText()).isEqualTo("NOT_APPLICABLE");
        assertThat(jsonRow.get("PE Provider").isNull()).isTrue();
        assertThat(jsonRow.get("PB Provider").isNull()).isTrue();
        assertThat(jsonRow.get("殖利率 Provider").isNull()).isTrue();

        Sheet sheet = GoldenWorkbooks.read(new ExcelDocRenderer().render(doc)).getSheet("個股決策");
        Row row = sheet.getRow(1);
        assertThat(row.getCell(STOCK_HEADERS_V11.indexOf("PE適用狀態")).getStringCellValue())
                .isEqualTo("NOT_APPLICABLE");
        assertThat(row.getCell(STOCK_HEADERS_V11.indexOf("PB適用狀態")).getStringCellValue())
                .isEqualTo("NOT_APPLICABLE");
        assertThat(row.getCell(STOCK_HEADERS_V11.indexOf("殖利率適用狀態")).getStringCellValue())
                .isEqualTo("NOT_APPLICABLE");
    }

    @Test
    @DisplayName("PE 值與完整 Treasury RateContext 同步輸出到 Excel／JSON")
    void pe與TreasuryContext雙格式() throws Exception {
        when(store.range(1L, 0L, 1L)).thenReturn(
                new TradingRadarSnapshotStore.SnapshotRange(List.of(snapshotNodeWithTreasury()), 1, 0));

        Sheet sheet = GoldenWorkbooks.read(service.exportForOwner(1L, 0L, 1L)).getSheet("個股決策");
        Row row = sheet.getRow(1);
        assertThat(row.getCell(STOCK_HEADERS_V11.indexOf("PE值")).getNumericCellValue()).isEqualTo(21.75);
        assertThat(row.getCell(STOCK_HEADERS_V11.indexOf("利率批次ID")).getNumericCellValue()).isEqualTo(42.0);
        assertThat(row.getCell(STOCK_HEADERS_V11.indexOf("利率批次完整")).getStringCellValue()).isEqualTo("是");
        assertThat(row.getCell(STOCK_HEADERS_V11.indexOf("利率值%")).getNumericCellValue()).isEqualTo(4.1234);
        assertThat(row.getCell(STOCK_HEADERS_V11.indexOf("利率來源Manifest")).getStringCellValue())
                .isEqualTo("M3 ｜ https://treasury.example/m3\nY10 ｜ https://treasury.example/y10");

        JsonNode jsonRow = mapper.readTree(jsonRenderer.render(service.radarDoc(1L, 0L, 1L)))
                .at("/sheets/2/tables/0/rows").get(0);
        assertThat(jsonRow.get("PE值").decimalValue()).isEqualByComparingTo("21.75");
        assertThat(jsonRow.get("利率批次完整").asBoolean()).isTrue();
        assertThat(jsonRow.get("利率Tenor").asText()).isEqualTo("Y10");
        assertThat(jsonRow.get("利率值%").decimalValue()).isEqualByComparingTo("4.1234");
        assertThat(jsonRow.get("利率可得基礎").asText()).isEqualTo("CONSERVATIVE_NEXT_MIDNIGHT_ET");
        assertThat(jsonRow.get("利率來源Manifest")).containsExactly(
                mapper.getNodeFactory().textNode("M3 ｜ https://treasury.example/m3"),
                mapper.getNodeFactory().textNode("Y10 ｜ https://treasury.example/y10"));
    }

    @Test
    @DisplayName("Task 281：新增 15 欄的值取自快照、格式為 NUM2；JSON 為 number")
    void 新增欄的值與格式() throws Exception {
        when(store.range(1L, 0L, 1L)).thenReturn(
                new TradingRadarSnapshotStore.SnapshotRange(List.of(snapshotNode()), 1, 0));
        Workbook wb = GoldenWorkbooks.read(service.exportForOwner(1L, 0L, 1L));

        Sheet m = wb.getSheet("大盤總覽");
        // index 11 行情狀態、12 週線MA5、20 J9、33 W%R9
        assertThat(m.getRow(1).getCell(11).getStringCellValue()).isEqualTo("VERIFIED_CLOSE");
        assertThat(m.getRow(1).getCell(12).getNumericCellValue()).isEqualTo(22950.0);
        assertThat(m.getRow(1).getCell(20).getNumericCellValue()).isEqualTo(100.25);
        assertThat(m.getRow(1).getCell(33).getNumericCellValue()).isEqualTo(113.25);
        // ⚠️ NUM2 的 dataFormat 是 #,##0.00（builtin numFmtId=4），不是 "0.00"；
        // 對不上時要改的是測試，不是 ExcelDocRenderer——那會打壞全部 9 份 golden。
        assertThat(m.getRow(1).getCell(20).getCellStyle().getDataFormatString()).isEqualTo("#,##0.00");
        assertThat(m.getRow(1).getCell(12).getCellStyle().getDataFormatString()).isEqualTo("#,##0.00");

        Sheet s = wb.getSheet("個股決策");
        // index 19 行情狀態、22 週線MA5、31 J9、44 W%R9
        assertThat(s.getRow(1).getCell(19).getStringCellValue()).isEqualTo("VERIFIED_CLOSE");
        assertThat(s.getRow(1).getCell(22).getNumericCellValue()).isEqualTo(1102.50);
        assertThat(s.getRow(1).getCell(31).getNumericCellValue()).isEqualTo(200.25);
        assertThat(s.getRow(1).getCell(44).getNumericCellValue()).isEqualTo(213.25);
        assertThat(s.getRow(1).getCell(22).getCellStyle().getDataFormatString()).isEqualTo("#,##0.00");
        assertThat(s.getRow(1).getCell(31).getCellStyle().getDataFormatString()).isEqualTo("#,##0.00");

        JsonNode json = mapper.readTree(jsonRenderer.render(service.radarDoc(1L, 0L, 1L)));
        JsonNode mr = json.at("/sheets/1/tables/0/rows").get(0);
        assertThat(mr.get("週線MA5").isNumber()).isTrue();
        assertThat(mr.get("週線MA5").decimalValue()).isEqualByComparingTo("22950.0");
        assertThat(mr.get("J9").decimalValue()).isEqualByComparingTo("100.25");
        assertThat(mr.get("W%R9").decimalValue()).isEqualByComparingTo("113.25");
        JsonNode sr = json.at("/sheets/2/tables/0/rows").get(0);
        assertThat(sr.get("週線MA5").isNumber()).isTrue();
        assertThat(sr.get("週線MA5").decimalValue()).isEqualByComparingTo("1102.50");
        assertThat(sr.get("BIAS10-BIAS20").decimalValue()).isEqualByComparingTo("212.25");
    }

    @Test
    @DisplayName("Task 281：舊快照沒有 extendedIndicators／weeklyMa 時，新增 15 欄為空白格與 JSON null，不擲例外")
    void 舊快照相容() throws Exception {
        ObjectNode legacy = (ObjectNode) snapshotNode();
        ((ObjectNode) legacy.path("market")).remove(List.of("weeklyMa", "extendedIndicators"));
        ObjectNode legacyStockNode = (ObjectNode) legacy.path("stocks").get(0);
        legacyStockNode.remove(List.of("weeklyMa", "extendedIndicators"));
        legacyStockNode.putObject("fundamental")
                .put("valuationProvider", "GENERIC_MUST_NOT_BE_USED")
                .put("valuationAsOf", "2026-08-07");
        when(store.range(9L, 0L, 1L)).thenReturn(
                new TradingRadarSnapshotStore.SnapshotRange(List.of(legacy), 1, 0));

        assertThatCode(() -> service.exportForOwner(9L, 0L, 1L)).doesNotThrowAnyException();
        Workbook wb = GoldenWorkbooks.read(service.exportForOwner(9L, 0L, 1L));
        for (int c : new int[]{12, 20, 26, 33}) {
            assertThat(wb.getSheet("大盤總覽").getRow(1).getCell(c).getCellType())
                    .as("大盤 index %d 缺值應為 BLANK 而非 0", c).isEqualTo(CellType.BLANK);
        }
        for (int c : new int[]{23, 31, 37, 44}) {
            assertThat(wb.getSheet("個股決策").getRow(1).getCell(c).getCellType())
                    .as("個股 index %d 缺值應為 BLANK 而非 0", c).isEqualTo(CellType.BLANK);
        }
        JsonNode json = mapper.readTree(jsonRenderer.render(service.radarDoc(9L, 0L, 1L)));
        assertThat(json.at("/sheets/1/tables/0/rows").get(0).get("週線MA5").isNull()).isTrue();
        assertThat(json.at("/sheets/1/tables/0/rows").get(0).get("J9").isNull()).isTrue();
        assertThat(json.at("/sheets/2/tables/0/rows").get(0).get("週線MA5").isNull()).isTrue();

        // t309 per-field profile flags are absent from pre-t309 snapshots.  The
        // appended Excel columns must stay blank and the JSON projection null,
        // never defaulting an unknown historical profile to complete.
        Row legacyStock = wb.getSheet("個股決策").getRow(1);
        assertThat(legacyStock.getCell(STOCK_HEADERS_V11.indexOf("資產分類完整")).getCellType())
                .isEqualTo(CellType.STRING);
        assertThat(legacyStock.getCell(STOCK_HEADERS_V11.indexOf("資產分類完整")).getStringCellValue())
                .isEmpty();
        assertThat(legacyStock.getCell(STOCK_HEADERS_V11.indexOf("底層幣別完整")).getCellType())
                .isEqualTo(CellType.STRING);
        assertThat(legacyStock.getCell(STOCK_HEADERS_V11.indexOf("底層幣別完整")).getStringCellValue())
                .isEmpty();
        JsonNode legacyStockRow = json.at("/sheets/2/tables/0/rows").get(0);
        assertThat(legacyStockRow.get("資產分類完整").isNull()).isTrue();
        assertThat(legacyStockRow.get("底層幣別完整").isNull()).isTrue();
        assertThat(VALUATION_COMPONENT_HEADERS_EXPECTED)
                .allSatisfy(header -> assertThat(legacyStockRow.get(header).isNull())
                        .as("舊快照 %s 應為 null，不得以 generic provenance 代填", header).isTrue());
        assertThat(legacyStock.getCell(STOCK_HEADERS_V11.indexOf("PE Provider")).getCellType())
                .isEqualTo(CellType.BLANK);
        assertThat(legacyStock.getCell(STOCK_HEADERS_V11.indexOf("PE來源網址")).getStringCellValue())
                .isEmpty();
    }

    @Test
    @DisplayName("Task 281：插欄前後既有欄逐格未變（對 radar_pre_t281 做欄索引映射比對）")
    void 插欄前後既有欄逐格未變() throws Exception {
        when(store.range(1L, 0L, 1L)).thenReturn(
                new TradingRadarSnapshotStore.SnapshotRange(List.of(snapshotNode()), 2, 1));
        Workbook actual = GoldenWorkbooks.read(service.exportForOwner(1L, 0L, 1L));
        Workbook pre = GoldenWorkbooks.golden("radar_pre_t281");

        assertSameMapped(pre, actual, "快照索引", c -> c);
        assertThat(actual.getSheet("大盤總覽")).isNotNull();
        assertThat(actual.getSheet("個股決策")).isNotNull();

        // 零快照那份只有表頭（快照索引另有一列提示列），比表頭即可
        when(store.range(2L, 0L, 1L)).thenReturn(
                new TradingRadarSnapshotStore.SnapshotRange(List.of(), 0, 0));
        Workbook actualEmpty = GoldenWorkbooks.read(service.exportForOwner(2L, 0L, 1L));
        Workbook preEmpty = GoldenWorkbooks.golden("radar_empty_pre_t281");
        assertSameMapped(preEmpty, actualEmpty, "快照索引", c -> c);
        assertThat(actualEmpty.getSheet("大盤總覽")).isNotNull();
        assertThat(actualEmpty.getSheet("個股決策")).isNotNull();
    }

    /**
     * 以欄索引映射逐格比對值、型別、dataFormat、粗體、字級。
     * <b>只重產 golden 不做這條比對是不夠的</b>——那樣「新增了欄」與「順手把既有欄改壞」無法分辨。
     */
    private static void assertSameMapped(Workbook expected, Workbook actual, String sheetName,
                                         java.util.function.IntUnaryOperator map) {
        Sheet e = expected.getSheet(sheetName);
        Sheet a = actual.getSheet(sheetName);
        assertThat(a).as("%s 分頁必須存在", sheetName).isNotNull();
        assertThat(a.getLastRowNum()).as("%s 列數", sheetName).isEqualTo(e.getLastRowNum());
        for (int ri = 0; ri <= e.getLastRowNum(); ri++) {
            Row er = e.getRow(ri);
            if (er == null) continue;
            Row ar = a.getRow(ri);
            assertThat(ar).as("%s 第 %d 列", sheetName, ri).isNotNull();
            for (int ci = 0; ci < er.getLastCellNum(); ci++) {
                var ec = er.getCell(ci);
                if (ec == null) continue;
                int target = map.applyAsInt(ci);
                var ac = ar.getCell(target);
                String where = sheetName + " 舊(" + ri + "," + ci + ") → 新(" + ri + "," + target + ")";
                assertThat(ac).as("%s 該格應存在", where).isNotNull();
                assertThat(ac.getCellType()).as("%s 型別", where).isEqualTo(ec.getCellType());
                if (ec.getCellType() == CellType.STRING) {
                    assertThat(ac.getStringCellValue()).as("%s 值", where).isEqualTo(ec.getStringCellValue());
                } else if (ec.getCellType() == CellType.NUMERIC) {
                    assertThat(ac.getNumericCellValue()).as("%s 值", where).isEqualTo(ec.getNumericCellValue());
                }
                assertThat(ac.getCellStyle().getDataFormatString())
                        .as("%s dataFormat", where).isEqualTo(ec.getCellStyle().getDataFormatString());
                var ef = expected.getFontAt(ec.getCellStyle().getFontIndex());
                var af = actual.getFontAt(ac.getCellStyle().getFontIndex());
                assertThat(af.getBold()).as("%s 粗體", where).isEqualTo(ef.getBold());
                assertThat(af.getFontHeightInPoints()).as("%s 字級", where).isEqualTo(ef.getFontHeightInPoints());
            }
        }
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

    private static JsonNode snapshotNodeWithTreasury() {
        ObjectNode root = (ObjectNode) snapshotNode();
        ObjectNode stock = (ObjectNode) root.path("stocks").get(0);
        stock.putObject("fundamental").put("peValue", 21.75);
        ObjectNode context = stock.putObject("evidence").putObject("treasuryRateContext");
        context.put("batchId", 42L);
        context.put("complete", true);
        context.put("tenor", "Y10");
        context.put("value", 4.1234);
        context.put("curveDate", "2026-07-30");
        context.put("provider", "US_TREASURY");
        context.put("availableAt", "2026-07-31T04:00:00Z");
        context.put("availabilityBasis", "CONSERVATIVE_NEXT_MIDNIGHT_ET");
        context.put("fetchedAt", "2026-07-31T07:00:00Z");
        context.put("lagDays", 1L);
        context.put("staleReason", "CURVE_LAG_1D");
        ObjectNode manifest = context.putObject("sourceManifest");
        manifest.put("Y10", "https://treasury.example/y10");
        manifest.put("M3", "https://treasury.example/m3");
        return root;
    }

    private static JsonNode snapshotNodeWithValuationProvenance() {
        ObjectNode root = (ObjectNode) snapshotNode();
        ObjectNode stock = (ObjectNode) root.path("stocks").get(0);
        ObjectNode fundamental = stock.putObject("fundamental");
        // Generic fields are deliberately contradictory: t315 columns must never read them.
        fundamental.put("valuationProvider", "GENERIC_MUST_NOT_BE_USED");
        fundamental.put("valuationAsOf", "1999-01-01");
        fundamental.putArray("valuationSourceUrls").add("https://generic.invalid");
        valuationEvidenceNode(fundamental.putObject("peEvidence"), 18.5, 22,
                "TWSE", "2026-08-08T01:00:00Z", "2026-08-07",
                List.of("https://example.test/pe-1", "https://example.test/pe-2"), false);
        valuationEvidenceNode(fundamental.putObject("pbEvidence"), 4.2, 80,
                "WANTGOO", "2026-08-08T02:00:00Z", "2026-08-06",
                List.of("https://example.test/pb"), false);
        valuationEvidenceNode(fundamental.putObject("dividendYieldEvidence"), 2.1, 35,
                "FINMIND", "2026-08-08T03:00:00Z", "2026-08-05",
                List.of("https://example.test/yield"), false);

        ObjectNode valuation = stock.putObject("evidence").putObject("evidenceGroups")
                .putObject("VALUATION");
        var components = valuation.putArray("components");
        components.addObject().put("name", "pe").put("applicability", "AVAILABLE")
                .putNull("missingReason");
        components.addObject().put("name", "pb").put("applicability", "STALE")
                .put("missingReason", "PB observation 已過期");
        components.addObject().put("name", "dividend_yield").put("applicability", "MISSING")
                .put("missingReason", "殖利率歷史不足 250 筆");
        return root;
    }

    private static JsonNode snapshotNodeWithNotApplicableValuation() {
        ObjectNode root = (ObjectNode) snapshotNode();
        ObjectNode stock = (ObjectNode) root.path("stocks").get(0);
        ObjectNode fundamental = stock.putObject("fundamental");
        fundamental.put("applicable", false);
        fundamental.putNull("peEvidence");
        fundamental.putNull("pbEvidence");
        fundamental.putNull("dividendYieldEvidence");
        ObjectNode valuation = stock.putObject("evidence").putObject("evidenceGroups")
                .putObject("VALUATION");
        var components = valuation.putArray("components");
        components.addObject().put("name", "pe").put("applicability", "NOT_APPLICABLE")
                .putNull("missingReason");
        components.addObject().put("name", "pb").put("applicability", "NOT_APPLICABLE")
                .putNull("missingReason");
        components.addObject().put("name", "dividend_yield").put("applicability", "NOT_APPLICABLE")
                .putNull("missingReason");
        return root;
    }

    private static void valuationEvidenceNode(
            ObjectNode node, double value, double percentile, String provider,
            String availableAt, String asOf, List<String> urls, boolean loss) {
        node.put("value", value);
        node.put("percentile", percentile);
        node.put("provider", provider);
        var sourceUrls = node.putArray("sourceUrls");
        urls.forEach(sourceUrls::add);
        node.put("availableAt", availableAt);
        node.put("asOf", asOf);
        node.put("loss", loss);
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
        m.put("quoteStatus", "VERIFIED_CLOSE");
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
        s1.put("quoteStatus", "VERIFIED_CLOSE");
        s1.put("dataComplete", true);
        // Task 264 的四欄：前三個給值（驗 TEXT／NUM2），etfPremiumPct 刻意不給
        // ——2330 不是 ETF，「非 ETF 留白」正是該欄的真實語意，同時驗得到 num() 缺欄位的行為。
        s1.put("timingLabel", "回檔買點");
        s1.put("ma60BiasPercent", 12.75);
        s1.put("week52Position", 88.50);
        s1.putArray("supportSignals").add("季線之上");
        s1.putArray("riskWarnings");
        // Task 281：週線 MA5 ＋ 走勢圖指標選單的 14 個值。大盤與個股都給，
        // 兩者的欄名與匯出端讀的 key **刻意同名**（不同於本 fixture 其餘欄位），才驗得到值真的被寫出來。
        m.put("weeklyMa", 22950.0);
        m.set("extendedIndicators", extNode(M, 1));
        s1.put("weeklyMa", 1102.50);
        s1.set("extendedIndicators", extNode(M, 2));
        return root;
    }

    /** Task 281：14 個擴充指標的 fixture 值；{@code seed} 讓大盤與個股互不相同，避免抓錯節點也剛好通過。 */
    private static ObjectNode extNode(ObjectMapper M, int seed) {
        ObjectNode e = M.createObjectNode();
        String[] keys = {"j9", "k3d2", "rsv", "ema12", "ema26", "dif", "macd", "osc",
                "rsi5", "rsi10", "bias10", "bias20", "b10b20", "wr9"};
        for (int i = 0; i < keys.length; i++) e.put(keys[i], seed * 100 + i + 0.25);
        return e;
    }
}
