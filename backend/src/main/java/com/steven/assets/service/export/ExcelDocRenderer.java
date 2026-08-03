package com.steven.assets.service.export;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 把 {@link ExportDoc} render 成 {@code .xlsx}（Requirement 55 / Task 269）。
 *
 * <p><b>逐 block 依序寫，不插入任何 doc 沒指定的列</b>——空行一律由 {@link ExportDoc.Blank} 表達。
 * 這一點是刻意的：初版曾用「meta 非空就空一列」之類的通則，實測在「當前即時資產」分頁會多插一列、
 * 使其後所有列位移。
 */
@Component
public class ExcelDocRenderer {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * 產一整份 xlsx。
     *
     * <p>本類別<b>刻意不持有任何可變狀態</b>：它是 singleton bean，會被排程執行緒與 HTTP 執行緒同時呼叫，
     * 拿 field 快取 {@code Styles} 會是資料競爭。{@code CellStyle} 本來就綁定單一 Workbook、不可跨 workbook
     * 重用，故一律隨 workbook 建立。
     */
    public byte[] render(ExportDoc doc) throws IOException {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = new Styles(wb);
            for (ExportDoc.Sheet sheet : doc.sheets()) {
                writeSheet(wb, st, sheet);
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    /**
     * 把單一 sheet 寫進呼叫端已開好的 workbook。
     *
     * <p>供混合活頁簿使用：{@code ExcelExportService.buildWorkbook()}（手動「完整匯出」）的活頁簿裡，
     * 「已實現損益」分頁與排程匯出的那一份是同一支 writer，必須共用同一個 doc 來源，
     * 否則同一張分頁會分裂成兩份實作。<b>Styles 由本類別內部依該 workbook 建立，呼叫端不傳</b>
     * （刻意不做跨呼叫快取，見 {@link #render} 的 thread-safety 說明；代價是同一 workbook 多次呼叫
     * 會各建一組 8 個 {@code CellStyle}，遠低於 XSSF 的 64k 上限）。
     */
    public void writeSheet(Workbook wb, ExportDoc.Sheet sheet) {
        writeSheet(wb, new Styles(wb), sheet);
    }

    private void writeSheet(Workbook wb, Styles st, ExportDoc.Sheet sheet) {
        Sheet s = wb.createSheet(sheet.name());
        int r = 0;

        for (ExportDoc.Block block : sheet.blocks()) {
            if (block instanceof ExportDoc.Line line) {
                Row row = s.createRow(r++);
                cell(row, 0, line.text(), st.of(line.style()));

            } else if (block instanceof ExportDoc.KvRow kvRow) {
                Row row = s.createRow(r++);
                List<ExportDoc.Kv> cells = kvRow.cells();
                for (int i = 0; i < cells.size(); i++) {
                    ExportDoc.Kv kv = cells.get(i);
                    cell(row, 2 * i, kv.key(), st.head);
                    writeValue(row, 2 * i + 1, kv.value(), kv.format(), st, false);
                }

            } else if (block instanceof ExportDoc.Table table) {
                if (table.name() != null) {
                    Row row = s.createRow(r++);
                    cell(row, 0, table.name(), st.of(table.nameStyle()));
                }
                if (table.showHeader()) {
                    Row row = s.createRow(r++);
                    List<String> headers = table.headers();
                    for (int i = 0; i < headers.size(); i++) cell(row, i, headers.get(i), st.head);
                }
                List<ExportDoc.Format> formats = table.columnFormats();
                for (List<Object> dataRow : table.rows()) {
                    Row row = s.createRow(r++);
                    for (int i = 0; i < dataRow.size(); i++) {
                        Object v = dataRow.get(i);
                        if (table.labelFirstColumn() && i == 0) {
                            cell(row, i, v, st.head);
                        } else {
                            writeValue(row, i, v, formats.get(i), st, table.omitNullCells());
                        }
                    }
                }

            } else if (block instanceof ExportDoc.Blank) {
                // 只遞增列索引、刻意不 createRow：既有四處空行都是裸 r++，POI 不會為它們寫出 <row>。
                // 改成 createRow 會讓讀回時 sheet.getRow(索引) 由 null 變非 null，是可觀測的版面變動。
                r++;
            }
        }

        for (int i = 0; i < sheet.autoSizeColumns(); i++) s.autoSizeColumn(i);
    }

    /**
     * 依 Format 寫值。
     *
     * @param omitNull true 時值為 null 就<b>連 createCell 都不做</b>（既有 {@code if (x != null) cell(...)} 的分頁）
     */
    private void writeValue(Row row, int col, Object value, ExportDoc.Format format, Styles st, boolean omitNull) {
        if (value == null && omitNull) return;

        // BOOL_ZH／LIST_LINES 的 null 一律寫空字串格，不落入通用的 null→BLANK 規則：
        // 既有 TradingRadarExportService 的 bool()／list() 在缺值時回的就是 ""。
        if (value == null
                && (format == ExportDoc.Format.BOOL_ZH || format == ExportDoc.Format.LIST_LINES)) {
            row.createCell(col).setCellValue("");
            return;
        }

        cell(row, col, toCellValue(value, format), styleFor(format, st));
    }

    /** 把值轉成 POI 寫得下的型別。日期一律轉字串（避免 Excel 依開啟端時區重新詮釋 date cell 而偏移一天）。 */
    private static Object toCellValue(Object value, ExportDoc.Format format) {
        if (value == null) return null;
        if (value instanceof LocalDate d) return ISO.format(d);
        if (value instanceof LocalDateTime dt) return TS_FMT.format(dt);
        if (format == ExportDoc.Format.BOOL_ZH && value instanceof Boolean b) return b ? "是" : "否";
        if (format == ExportDoc.Format.LIST_LINES && value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object e : list) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(e == null ? "" : e.toString());
            }
            return sb.toString();
        }
        return value;
    }

    /**
     * TEXT／DATE／TIMESTAMP／BOOL_ZH／LIST_LINES 一律不套 CellStyle（既有這些格都是 {@code cell(row, i, v, null)}）；
     * 只有 MONEY／NUM2／NUM4／NUM6 才套。
     */
    private static CellStyle styleFor(ExportDoc.Format format, Styles st) {
        return switch (format) {
            case MONEY -> st.money;
            case NUM2 -> st.num2;
            case NUM4 -> st.num4;
            case NUM6 -> st.num6;
            case NUM0 -> st.num0;
            default -> null;
        };
    }

    /**
     * 與既有 {@code ExcelExportService.cell()} 逐行等價：<b>先 createCell，值為 null 就直接 return</b>
     * ——產生的是<b>存在的 BLANK 格且無樣式</b>（既有在 return 就離開，setCellStyle 那行根本沒跑到）。
     * 不是「null 就不建這一格」：那會讓 {@code getLastCellNum()} 與既有不同。
     */
    private static void cell(Row row, int col, Object value, CellStyle style) {
        Cell c = row.createCell(col);
        if (value == null) return;
        if (value instanceof BigDecimal bd) c.setCellValue(bd.doubleValue());
        else if (value instanceof Number n) c.setCellValue(n.doubleValue());
        else c.setCellValue(value.toString());
        if (style != null) c.setCellStyle(style);
    }

    /**
     * 九種樣式。<b>不是「兩支既有服務的聯集」</b>——{@code ExcelExportService.Styles.section} 是粗體 13pt、
     * {@code TradingRadarExportService.Styles.section} 是粗體 12pt，取聯集會靜默改掉其中一支的字級。
     *
     * <p>{@code money} 與 {@code num2} 的格式字串同為 {@code #,##0.00}，但<b>必須是兩個獨立的 CellStyle 實例</b>
     * （Task 200 為漲跌／漲跌幅／月季年線另開的獨立 style），合併會讓日後改 money 波及那幾欄。
     *
     * <p><b>與 {@code ExcelExportService.Styles} 的 head／money／num2／num4／num6 必須逐字相同</b>
     * ——那一份因 {@code writeCurrentSummarySheet}／{@code writeSnapshotSheet}／{@code summaryRow} 仍在使用
     * 而保留，任一方改動須同步。
     */
    static final class Styles {
        final CellStyle head;
        final CellStyle section13;
        final CellStyle section12;
        final CellStyle warn;
        final CellStyle money;
        final CellStyle num2;
        final CellStyle num4;
        final CellStyle num6;
        final CellStyle num0;

        Styles(Workbook wb) {
            DataFormat fmt = wb.createDataFormat();

            Font headFont = wb.createFont();
            headFont.setBold(true);
            head = wb.createCellStyle();
            head.setFont(headFont);
            head.setAlignment(HorizontalAlignment.CENTER);

            Font sec13Font = wb.createFont();
            sec13Font.setBold(true);
            sec13Font.setFontHeightInPoints((short) 13);
            section13 = wb.createCellStyle();
            section13.setFont(sec13Font);

            Font sec12Font = wb.createFont();
            sec12Font.setBold(true);
            sec12Font.setFontHeightInPoints((short) 12);
            section12 = wb.createCellStyle();
            section12.setFont(sec12Font);

            Font warnFont = wb.createFont();
            warnFont.setBold(true);
            warnFont.setColor(IndexedColors.RED.getIndex());
            warn = wb.createCellStyle();
            warn.setFont(warnFont);

            money = wb.createCellStyle();
            money.setDataFormat(fmt.getFormat("#,##0.00"));

            num4 = wb.createCellStyle();
            num4.setDataFormat(fmt.getFormat("#,##0.0000"));

            num2 = wb.createCellStyle();
            num2.setDataFormat(fmt.getFormat("#,##0.00"));

            num6 = wb.createCellStyle();
            num6.setDataFormat(fmt.getFormat("#,##0.000000"));

            num0 = wb.createCellStyle();
            num0.setDataFormat(fmt.getFormat("#,##0"));
        }

        CellStyle of(ExportDoc.LineStyle style) {
            if (style == null) return null;
            return switch (style) {
                case PLAIN -> null;
                case HEAD -> head;
                case SECTION_13 -> section13;
                case SECTION_12 -> section12;
                case WARN -> warn;
            };
        }
    }
}
