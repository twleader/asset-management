package com.steven.assets.service.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * {@link ExportDoc}／{@link ExcelDocRenderer}／{@link JsonDocRenderer} 的單元測試（Requirement 55 / Task 269）。
 *
 * <p>重點在「兩種既有 null 行為可分辨」「空行不建列」「顯示格式不滲進 JSON」三件事——
 * 這三件都是設計評審實測出來、只有逐格斷言才抓得到的。
 */
class ExportDocRendererTest {

    private final ExcelDocRenderer excel = new ExcelDocRenderer();
    private final ObjectMapper mapper = new ObjectMapper();
    private final JsonDocRenderer json = new JsonDocRenderer(mapper);

    private static ExportDoc.Table table(List<String> headers, List<ExportDoc.Format> formats,
                                         List<List<Object>> rows) {
        return new ExportDoc.Table(null, null, headers, true, false, false, formats, rows);
    }

    private static ExportDoc doc(ExportDoc.Block... blocks) {
        return new ExportDoc("測試", List.of(new ExportDoc.Sheet("分頁", List.of(blocks), 4)));
    }

    private Sheet readBack(ExportDoc d) throws Exception {
        Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(excel.render(d)));
        return wb.getSheetAt(0);
    }

    private JsonNode readJson(ExportDoc d) throws Exception {
        return mapper.readTree(json.render(d));
    }

    /** POI 5.x 移除了 CellStyle.getFont(Workbook)，改由 workbook 依 fontIndex 取。 */
    private static org.apache.poi.ss.usermodel.Font fontOf(Workbook wb, Sheet s, int rowIdx) {
        return wb.getFontAt(s.getRow(rowIdx).getCell(0).getCellStyle().getFontIndex());
    }

    @Nested
    @DisplayName("模型建構期驗證")
    class Construction {

        @Test
        @DisplayName("列長與 headers 不符即擲例外，訊息含列索引與兩個長度")
        void 列長不符即擲例外() {
            assertThatThrownBy(() -> table(List.of("A", "B"), null, List.of(List.of("只有一欄"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("第 0 列")
                    .hasMessageContaining("1")
                    .hasMessageContaining("2");
        }

        @Test
        @DisplayName("headers 有重複欄名即擲例外（JSON zip 成 map 會靜默吃掉一欄）")
        void 重複欄名即擲例外() {
            assertThatThrownBy(() -> table(List.of("金額", "金額"), null, List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("金額");
        }

        @Test
        @DisplayName("columnFormats 長度與 headers 不符即擲例外")
        void 格式數不符即擲例外() {
            assertThatThrownBy(() -> table(List.of("A", "B"), List.of(ExportDoc.Format.TEXT), List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("columnFormats");
        }

        @Test
        @DisplayName("資料列含 null 值仍建構成功（用 List.copyOf 的錯誤實作會在此 NPE）")
        void 資料列含null仍可建構() {
            assertThatCode(() -> table(List.of("A", "B"), null, List.of(Arrays.asList("x", null))))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("JSON 型別規則")
    class JsonTypes {

        @Test
        @DisplayName("BigDecimal 輸出為 JSON number 且保留原精度")
        void 數值保留原精度() throws Exception {
            ExportDoc d = doc(table(List.of("匯率"), List.of(ExportDoc.Format.NUM4),
                    List.of(List.of(new BigDecimal("32.1054")))));
            String text = new String(json.render(d), StandardCharsets.UTF_8);
            // 字串比對輸出的 JSON 文字：只斷言 decimalValue 相等的話，對 "32.1054" 字串也會過
            assertThat(text).contains("\"匯率\" : 32.1054");
            assertThat(readJson(d).at("/sheets/0/tables/0/rows/0/匯率").isNumber()).isTrue();
        }

        @Test
        @DisplayName("null 輸出為 JSON null，key 仍存在（不得以 0 或空字串充數）")
        void 缺值為null() throws Exception {
            ExportDoc d = doc(table(List.of("金額"), List.of(ExportDoc.Format.MONEY),
                    List.of(Arrays.asList((Object) null))));
            JsonNode row = readJson(d).at("/sheets/0/tables/0/rows/0");
            assertThat(row.has("金額")).isTrue();
            assertThat(row.get("金額").isNull()).isTrue();
        }

        @Test
        @DisplayName("BOOL_ZH 在 JSON 是 boolean、在 Excel 是「是」／「否」")
        void 布林兩種呈現() throws Exception {
            ExportDoc d = doc(table(List.of("完整"), List.of(ExportDoc.Format.BOOL_ZH),
                    List.of(List.of(Boolean.TRUE))));
            assertThat(readJson(d).at("/sheets/0/tables/0/rows/0/完整").isBoolean()).isTrue();
            assertThat(readJson(d).at("/sheets/0/tables/0/rows/0/完整").asBoolean()).isTrue();
            assertThat(readBack(d).getRow(1).getCell(0).getStringCellValue()).isEqualTo("是");
        }

        @Test
        @DisplayName("LIST_LINES 在 JSON 是陣列、在 Excel 是換行串接字串")
        void 陣列兩種呈現() throws Exception {
            ExportDoc d = doc(table(List.of("訊號"), List.of(ExportDoc.Format.LIST_LINES),
                    List.of(List.of(List.of("多頭", "量增")))));
            JsonNode v = readJson(d).at("/sheets/0/tables/0/rows/0/訊號");
            assertThat(v.isArray()).isTrue();
            assertThat(v).hasSize(2);
            assertThat(readBack(d).getRow(1).getCell(0).getStringCellValue()).isEqualTo("多頭\n量增");
        }

        @Test
        @DisplayName("LocalDate 輸出 yyyy-MM-dd，LocalDateTime 輸出 ISO local（不加時區位移）")
        void 日期時間格式() throws Exception {
            ExportDoc d = doc(new ExportDoc.KvRow(List.of(
                    new ExportDoc.Kv("日期", LocalDate.of(2026, 8, 1), ExportDoc.Format.DATE),
                    new ExportDoc.Kv("時間", LocalDateTime.of(2026, 8, 1, 8, 0, 0), ExportDoc.Format.TIMESTAMP))));
            assertThat(readJson(d).at("/sheets/0/meta/日期").asText()).isEqualTo("2026-08-01");
            assertThat(readJson(d).at("/sheets/0/meta/時間").asText()).isEqualTo("2026-08-01T08:00:00");
        }

        @Test
        @DisplayName("中文欄名與值未被 escape 成 \\uXXXX")
        void 中文不被escape() throws Exception {
            ExportDoc d = doc(table(List.of("銀行"), null, List.of(List.of("國泰世華"))));
            String text = new String(json.render(d), StandardCharsets.UTF_8);
            assertThat(text).contains("國泰世華").doesNotContain("\\u");
        }

        @Test
        @DisplayName("rows 是物件陣列且 key 順序等於 headers；showHeader=false 仍以 headers 當 key")
        void 物件陣列且保序() throws Exception {
            ExportDoc.Table t = new ExportDoc.Table("即時彙總", ExportDoc.LineStyle.SECTION_13,
                    List.of("項目", "金額"), false, true, false,
                    List.of(ExportDoc.Format.TEXT, ExportDoc.Format.MONEY),
                    List.of(List.of("存款總計", new BigDecimal("100"))));
            JsonNode row = readJson(doc(t)).at("/sheets/0/tables/0/rows/0");
            assertThat(row.fieldNames()).toIterable().containsExactly("項目", "金額");
        }
    }

    @Nested
    @DisplayName("Excel 逐格與樣式")
    class ExcelCells {

        @Test
        @DisplayName("Blank 只遞增列索引、不建立 Row（既有空行是裸 r++）")
        void 空行不建列() throws Exception {
            ExportDoc d = doc(new ExportDoc.Line("標題", ExportDoc.LineStyle.SECTION_13),
                    new ExportDoc.Blank(),
                    new ExportDoc.Line("其後", ExportDoc.LineStyle.PLAIN));
            Sheet s = readBack(d);
            assertThat(s.getRow(0).getCell(0).getStringCellValue()).isEqualTo("標題");
            assertThat(s.getRow(1)).as("空行那一列不得存在").isNull();
            assertThat(s.getRow(2).getCell(0).getStringCellValue()).isEqualTo("其後");
        }

        @Test
        @DisplayName("omitNullCells=false → 存在的 BLANK 格且無樣式（與既有 cell() 等價）")
        void null建blank格且無樣式() throws Exception {
            ExportDoc.Table t = new ExportDoc.Table(null, null, List.of("A", "B"), true, false, false,
                    List.of(ExportDoc.Format.TEXT, ExportDoc.Format.NUM4), List.of(Arrays.asList("x", null)));
            Row row = readBack(doc(t)).getRow(1);
            assertThat(row.getCell(1)).isNotNull();
            assertThat(row.getCell(1).getCellType()).isEqualTo(CellType.BLANK);
            assertThat(row.getCell(1).getCellStyle().getIndex())
                    .as("既有 cell() 在 value==null 就 return，style 那行沒跑到")
                    .isEqualTo((short) 0);
            assertThat(row.getLastCellNum()).isEqualTo((short) 2);
        }

        @Test
        @DisplayName("omitNullCells=true → 該格完全不建（既有 if (x != null) cell(...) 的分頁）")
        void null完全不建格() throws Exception {
            ExportDoc.Table t = new ExportDoc.Table(null, null, List.of("A", "B"), true, false, true,
                    List.of(ExportDoc.Format.TEXT, ExportDoc.Format.NUM4), List.of(Arrays.asList("x", null)));
            Row row = readBack(doc(t)).getRow(1);
            assertThat(row.getCell(1)).as("omitNullCells=true 時該格不存在").isNull();
            assertThat(row.getLastCellNum()).isEqualTo((short) 1);
        }

        @Test
        @DisplayName("兩種 null 行為在 JSON 側一律輸出 null（omitNullCells 不影響 JSON）")
        void 兩種null在json都是null() throws Exception {
            for (boolean omit : new boolean[]{true, false}) {
                ExportDoc.Table t = new ExportDoc.Table(null, null, List.of("A"), true, false, omit,
                        List.of(ExportDoc.Format.NUM4), List.of(Arrays.asList((Object) null)));
                assertThat(readJson(doc(t)).at("/sheets/0/tables/0/rows/0/A").isNull()).isTrue();
            }
        }

        @Test
        @DisplayName("BOOL_ZH／LIST_LINES 的 null 是空字串格，不是 BLANK")
        void 布林與陣列的null是空字串格() throws Exception {
            ExportDoc.Table t = new ExportDoc.Table(null, null, List.of("B", "L"), true, false, false,
                    List.of(ExportDoc.Format.BOOL_ZH, ExportDoc.Format.LIST_LINES),
                    List.of(Arrays.asList(null, null)));
            Row row = readBack(doc(t)).getRow(1);
            assertThat(row.getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(row.getCell(0).getStringCellValue()).isEmpty();
            assertThat(row.getCell(1).getStringCellValue()).isEmpty();
        }

        @Test
        @DisplayName("SECTION_13／SECTION_12／WARN／PLAIN 的樣式各自正確")
        void 樣式對應() throws Exception {
            ExportDoc d = doc(new ExportDoc.Line("十三", ExportDoc.LineStyle.SECTION_13),
                    new ExportDoc.Line("十二", ExportDoc.LineStyle.SECTION_12),
                    new ExportDoc.Line("警示", ExportDoc.LineStyle.WARN),
                    new ExportDoc.Line("素", ExportDoc.LineStyle.PLAIN));
            Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(excel.render(d)));
            Sheet s = wb.getSheetAt(0);
            assertThat(fontOf(wb, s, 0).getFontHeightInPoints()).isEqualTo((short) 13);
            assertThat(fontOf(wb, s, 1).getFontHeightInPoints()).isEqualTo((short) 12);
            assertThat(fontOf(wb, s, 2).getColor())
                    .isEqualTo(org.apache.poi.ss.usermodel.IndexedColors.RED.getIndex());
            assertThat(s.getRow(3).getCell(0).getCellStyle().getIndex()).as("PLAIN 不套任何 style").isEqualTo((short) 0);
        }

        @Test
        @DisplayName("TEXT／DATE／TIMESTAMP 的格不套 CellStyle，MONEY 才套")
        void 只有數值格套樣式() throws Exception {
            ExportDoc.Table t = new ExportDoc.Table(null, null, List.of("文", "日", "時", "錢"), true, false, false,
                    List.of(ExportDoc.Format.TEXT, ExportDoc.Format.DATE,
                            ExportDoc.Format.TIMESTAMP, ExportDoc.Format.MONEY),
                    List.of(List.of("a", LocalDate.of(2026, 8, 1),
                            LocalDateTime.of(2026, 8, 1, 8, 0), new BigDecimal("1"))));
            Row row = readBack(doc(t)).getRow(1);
            assertThat(row.getCell(0).getCellStyle().getIndex()).isEqualTo((short) 0);
            assertThat(row.getCell(1).getCellStyle().getIndex()).isEqualTo((short) 0);
            assertThat(row.getCell(2).getCellStyle().getIndex()).isEqualTo((short) 0);
            assertThat(row.getCell(3).getCellStyle().getIndex()).isNotEqualTo((short) 0);
        }

        @Test
        @DisplayName("日期寫成文字 cell，不是 Excel date cell（避免開啟端時區偏移一天）")
        void 日期為文字格() throws Exception {
            ExportDoc.Table t = new ExportDoc.Table(null, null, List.of("日"), true, false, false,
                    List.of(ExportDoc.Format.DATE), List.of(List.of(LocalDate.of(2026, 8, 1))));
            Row row = readBack(doc(t)).getRow(1);
            assertThat(row.getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo("2026-08-01");
        }

        @Test
        @DisplayName("KvRow 寫成同一列的 label／value 交錯，label 走 head 樣式")
        void 同列多鍵值() throws Exception {
            ExportDoc d = doc(new ExportDoc.KvRow(List.of(
                    new ExportDoc.Kv("基準快照日期", LocalDate.of(2026, 8, 1), ExportDoc.Format.DATE),
                    new ExportDoc.Kv("美元匯率", new BigDecimal("32.1054"), ExportDoc.Format.NUM4),
                    new ExportDoc.Kv("即時總資產", new BigDecimal("123.45"), ExportDoc.Format.MONEY))));
            Row row = readBack(d).getRow(0);
            assertThat(row.getLastCellNum()).as("三個 Kv 佔同一列六格").isEqualTo((short) 6);
            assertThat(row.getCell(0).getStringCellValue()).isEqualTo("基準快照日期");
            assertThat(row.getCell(2).getStringCellValue()).isEqualTo("美元匯率");
            assertThat(row.getCell(4).getStringCellValue()).isEqualTo("即時總資產");
            assertThat(row.getCell(3).getNumericCellValue()).isEqualTo(32.1054);
            // 三個 Kv 併進同一個 meta 物件
            assertThat(readJson(d).at("/sheets/0/meta").size()).isEqualTo(3);
        }

        @Test
        @DisplayName("showHeader=false 不寫表頭列；labelFirstColumn=true 時第 0 欄走 head 樣式")
        void 無表頭且標籤欄套head() throws Exception {
            ExportDoc.Table t = new ExportDoc.Table("即時彙總", ExportDoc.LineStyle.SECTION_13,
                    List.of("項目", "金額"), false, true, false,
                    List.of(ExportDoc.Format.TEXT, ExportDoc.Format.MONEY),
                    List.of(List.of("存款總計", new BigDecimal("100"))));
            Sheet s = readBack(doc(t));
            assertThat(s.getRow(0).getCell(0).getStringCellValue()).isEqualTo("即時彙總");
            // 第 1 列直接是資料列，不是表頭列
            assertThat(s.getRow(1).getCell(0).getStringCellValue()).isEqualTo("存款總計");
            assertThat(s.getRow(1).getCell(0).getCellStyle().getIndex()).isNotEqualTo((short) 0);
            assertThat(s.getRow(1).getCell(1).getNumericCellValue()).isEqualTo(100.0);
            assertThat(s.getRow(2)).as("只有兩列").isNull();
        }

        @Test
        @DisplayName("無 tables 的 sheet 仍能指定 autoSize 欄數（早退分支）")
        void 無表格仍可指定autoSize() throws Exception {
            ExportDoc d = new ExportDoc("測試", List.of(new ExportDoc.Sheet("分頁",
                    List.of(new ExportDoc.Line("當前即時資產", ExportDoc.LineStyle.SECTION_13),
                            new ExportDoc.Line("尚無資產快照", ExportDoc.LineStyle.PLAIN)), 6)));
            Sheet s = readBack(d);
            assertThat(s.getRow(0).getCell(0).getStringCellValue()).isEqualTo("當前即時資產");
            assertThat(s.getRow(1).getCell(0).getStringCellValue()).isEqualTo("尚無資產快照");
            assertThat(s.getRow(1).getCell(0).getCellStyle().getIndex()).as("PLAIN 無樣式").isEqualTo((short) 0);
            assertThat(readJson(d).at("/sheets/0/tables")).isEmpty();
            assertThat(readJson(d).at("/sheets/0/lines")).hasSize(2);
        }

        @Test
        @DisplayName("writeSheet 可把 doc 產的分頁嵌進呼叫端已開好的混合活頁簿")
        void 可嵌入混合活頁簿() throws Exception {
            try (Workbook wb = new XSSFWorkbook()) {
                wb.createSheet("既有分頁");
                excel.writeSheet(wb, new ExportDoc.Sheet("已實現損益",
                        List.of(table(List.of("代號"), null, List.of(List.of("2330")))), 1));
                assertThat(wb.getNumberOfSheets()).isEqualTo(2);
                assertThat(wb.getSheetAt(1).getSheetName()).isEqualTo("已實現損益");
                assertThat(wb.getSheetAt(1).getRow(1).getCell(0).getStringCellValue()).isEqualTo("2330");
            }
        }
    }
}
