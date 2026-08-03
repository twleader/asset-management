package com.steven.assets.service.export;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 匯出文件的中介模型（Requirement 55 / Task 269）。
 *
 * <p>十個自動匯出點的每一份檔，都先落成一個 {@code ExportDoc}，再由 {@link ExcelDocRenderer} 與
 * {@link JsonDocRenderer} 各自 render 成 {@code .xlsx} 與 {@code .json}。**一次查詢 → 一份 doc → 兩種格式**
 * 是「兩份檔必須內容一致」的結構性保證；讓兩種格式各自查一次資料，會讓吃即時價的匯出點產生對不起來的兩份檔。
 *
 * <p><b>模型是「一張 sheet ＝ 一串 block」，不是固定的 note／meta／tables 三欄位。</b>
 * 後者表達不出既有版面，實測有四處還原不了：
 * <ul>
 *   <li>「當前即時資產」的 {@code 基準快照日期／美元匯率／即時總資產} 是<b>同一列六格</b>（label,value 交錯）
 *       —— 拆成三個 {@link Kv} 會變三列、做成 3 欄 {@link Table} 會變兩列 → 需要 {@link KvRow}</li>
 *   <li>「即時彙總」四列<b>沒有表頭列</b>、且第 0 欄標籤走 head 樣式 → 需要 {@code showHeader}／{@code labelFirstColumn}</li>
 *   <li>早退分支輸出<b>三列</b>（標題／匯出時間／「尚無資產快照」），單一 note 名額裝不下 → 需要多個 {@link Line}</li>
 *   <li>「匯出時間」列與下一列之間<b>沒有空行</b>，「meta 非空就空一列」的通則會多插一列 → 空行改由 {@link Blank} 明確指定</li>
 * </ul>
 *
 * <p><b>值一律以原始型別放入</b>（{@code BigDecimal}／{@code LocalDate}／{@code LocalDateTime}／{@code String}／
 * {@code Boolean}／{@code List<String>}／{@code null}），不在建構期轉成字串——轉了之後 JSON 就永遠拿不回
 * 型別與原精度。{@link Format} 與 {@link LineStyle} 只影響 Excel，{@link JsonDocRenderer} 一律忽略。
 */
public record ExportDoc(String title, List<Sheet> sheets) {

    public ExportDoc {
        sheets = sheets == null ? List.of() : List.copyOf(sheets);
    }

    /**
     * 一張工作表。
     *
     * @param autoSizeColumns 結束時要 autoSize 的欄數。<b>既有各分頁不同、且不可由 headers 推導</b>
     *                        （例如「當前即時資產」主路徑 21 欄、早退分支 6 欄且完全沒有 table）。
     */
    public record Sheet(String name, List<Block> blocks, int autoSizeColumns) {
        public Sheet {
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
        }
    }

    public sealed interface Block permits Line, KvRow, Table, Blank {}

    /** 單格文字列（標題列、提示列）：第 0 格寫 {@code text}，樣式依 {@code style}。 */
    public record Line(String text, LineStyle style) implements Block {}

    /**
     * 同一列的 label／value 交錯：第 {@code 2i} 格寫第 i 個 {@link Kv} 的 key（head 樣式）、
     * 第 {@code 2i+1} 格寫 value（依該 Kv 的 {@link Format}）。
     */
    public record KvRow(List<Kv> cells) implements Block {
        public KvRow {
            cells = cells == null ? List.of() : List.copyOf(cells);
        }
    }

    /**
     * 表格。
     *
     * @param name             非 null 時前置一列 section 標題（樣式為 {@code nameStyle}）
     * @param showHeader       false 時不寫表頭列（JSON 仍以 headers 當 key）——「即時彙總」那種無表頭的區塊
     * @param labelFirstColumn true 時資料列第 0 欄走 head 樣式（{@code summaryRow} 的排版）
     * @param omitNullCells    true 時資料列中值為 null 的格<b>完全不建</b>（對應既有 {@code if (x != null) cell(...)}
     *                         的分頁：油價金價／匯率／指數）；false 時建立 BLANK 格（對應既有
     *                         {@code cell(row, i, null, style)} 的分頁）。<b>兩者讀回時可分辨</b>
     *                         （{@code getCell(i)} 為 null vs BLANK、{@code getLastCellNum()} 不同），不可混用。
     */
    public record Table(String name, LineStyle nameStyle,
                        List<String> headers, boolean showHeader, boolean labelFirstColumn,
                        boolean omitNullCells,
                        List<Format> columnFormats, List<List<Object>> rows) implements Block {

        public Table {
            headers = headers == null ? List.of() : List.copyOf(headers);

            // 重複欄名必須擋下：JSON renderer 把 headers 與 row zip 成 map，重複 key 後者會覆蓋前者、
            // 靜默吃掉一欄，而 Excel 那份仍有兩欄——兩份檔就不一致了。
            Set<String> dup = new HashSet<>();
            for (String h : headers) {
                if (!dup.add(h)) {
                    throw new IllegalArgumentException("ExportDoc.Table 的表頭有重複欄名：" + h);
                }
            }

            if (columnFormats == null) {
                List<Format> all = new ArrayList<>(headers.size());
                for (int i = 0; i < headers.size(); i++) all.add(Format.TEXT);
                columnFormats = Collections.unmodifiableList(all);
            } else {
                if (columnFormats.size() != headers.size()) {
                    throw new IllegalArgumentException("ExportDoc.Table 的 columnFormats 長度 "
                            + columnFormats.size() + " 與 headers 長度 " + headers.size() + " 不符");
                }
                columnFormats = List.copyOf(columnFormats);
            }

            List<List<Object>> copied = new ArrayList<>(rows == null ? 0 : rows.size());
            if (rows != null) {
                for (int i = 0; i < rows.size(); i++) {
                    List<Object> row = rows.get(i);
                    if (row == null || row.size() != headers.size()) {
                        throw new IllegalArgumentException("ExportDoc.Table 第 " + i + " 列有 "
                                + (row == null ? 0 : row.size()) + " 欄，與 headers 的 " + headers.size() + " 欄不符");
                    }
                    // 刻意不用 List.copyOf：它不接受 null 元素，而資料列裡 null 是合法值
                    //（「沒有值就是 null」是本需求明文要求的行為）。
                    copied.add(Collections.unmodifiableList(new ArrayList<>(row)));
                }
            }
            rows = Collections.unmodifiableList(copied);
        }
    }

    /**
     * 空行：<b>只遞增列索引、不建立 Row</b>。既有四處空行都是裸 {@code r++}，POI 不會為它們寫出
     * {@code <row>} 元素；改成 {@code createRow} 會讓讀回時 {@code sheet.getRow(索引)} 由 null 變成非 null。
     */
    public record Blank() implements Block {}

    /** 表頭區的一個鍵值。 */
    public record Kv(String key, Object value, Format format) {}

    /**
     * 文字列／表格標題列的樣式。
     *
     * <p>{@code SECTION_13} 與 {@code SECTION_12} <b>必須並存、不能合併</b>：
     * {@code ExcelExportService.Styles.section} 是粗體 13pt、{@code TradingRadarExportService.Styles.section}
     * 是粗體 12pt，合併會靜默改掉交易雷達「快照索引」分頁的標題列字級。
     * {@code PLAIN} ＝不套任何 CellStyle。
     */
    public enum LineStyle { PLAIN, HEAD, SECTION_13, SECTION_12, WARN }

    /**
     * 儲存格格式。<b>只影響 Excel，{@link JsonDocRenderer} 一律忽略</b>——這是「顯示格式不得滲進 JSON」
     * 在型別層級的保證。{@code BOOL_ZH}（Excel 寫「是」／「否」）與 {@code LIST_LINES}（Excel 以 {@code \n} 串接）
     * 尤其如此：JSON 該拿到 boolean 與字串陣列。
     */
    public enum Format { TEXT, MONEY, NUM2, NUM4, NUM6, NUM0, DATE, TIMESTAMP, BOOL_ZH, LIST_LINES }
}
