package com.steven.assets.service.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把 {@link ExportDoc} render 成語意化 JSON（Requirement 55 / Task 269）。
 *
 * <p>走同一串 block 分桶：{@link ExportDoc.Line} → {@code lines[]}、{@link ExportDoc.KvRow} 的每個
 * {@link ExportDoc.Kv} 併進 {@code meta{}}、{@link ExportDoc.Table} → {@code tables[]}、
 * {@link ExportDoc.Blank} 忽略。
 *
 * <p><b>{@code rows} 一律是物件陣列</b>（headers 與該列 zip 成 {@code LinkedHashMap}，保序），
 * 不輸出依位置解析的陣列——那等於要求下游自己去數第幾格，欄位一改就靜默錯位。
 *
 * <p><b>本類別一律不讀取 {@link ExportDoc.Format} 與 {@link ExportDoc.LineStyle} 的任何值</b>，
 * 也不看 {@code omitNullCells}——這是「顯示格式不得滲進 JSON」在型別層級的保證。
 */
@Component
public class JsonDocRenderer {

    private static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;
    /** ISO local，刻意不加時區位移——LocalDateTime 本來就沒有時區資訊，加位移等於憑空捏造。 */
    private static final DateTimeFormatter ISO_LOCAL_DT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;
    /**
     * {@code generatedAt} 專用。<b>不可用 {@code ZonedDateTime.toString()}</b>——它是 Java 特有的
     * ISO-8601 擴充，會多輸出 {@code [Asia/Taipei]} 這個方括號後綴，且小數秒到奈秒（9 位）。
     * 兩者都不是標準 ISO-8601／RFC 3339：實測 Python 的 {@code datetime.fromisoformat()}
     * 對後綴與 9 位小數秒都直接擲 {@code ValueError}，而本檔的下游正是 SRPP 退休規劃專案。
     */
    private static final DateTimeFormatter ISO_OFFSET_DT = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final ObjectMapper objectMapper;

    public JsonDocRenderer(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public byte[] render(ExportDoc doc) throws IOException {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("title", doc.title());
        // 本身帶時區的值，故輸出含 +08:00 的位移（與 LocalDateTime 欄位不同）；
        // 截到毫秒是為了讓小數秒維持 3 位——標準解析器（含 Python fromisoformat）只接受 3 或 6 位。
        root.put("generatedAt",
                ZonedDateTime.now(TW_ZONE).truncatedTo(ChronoUnit.MILLIS).format(ISO_OFFSET_DT));

        List<Object> sheets = new ArrayList<>();
        for (ExportDoc.Sheet sheet : doc.sheets()) {
            sheets.add(toSheet(sheet));
        }
        root.put("sheets", sheets);

        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(root);
    }

    private Map<String, Object> toSheet(ExportDoc.Sheet sheet) {
        List<String> lines = new ArrayList<>();
        Map<String, Object> meta = new LinkedHashMap<>();
        List<Object> tables = new ArrayList<>();

        for (ExportDoc.Block block : sheet.blocks()) {
            if (block instanceof ExportDoc.Line line) {
                lines.add(line.text());
            } else if (block instanceof ExportDoc.KvRow kvRow) {
                for (ExportDoc.Kv kv : kvRow.cells()) {
                    meta.put(kv.key(), toJsonValue(kv.value()));
                }
            } else if (block instanceof ExportDoc.Table table) {
                tables.add(toTable(table));
            }
            // Blank：JSON 沒有「空行」這個概念，忽略
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", sheet.name());
        out.put("lines", lines);
        out.put("meta", meta);
        out.put("tables", tables);
        return out;
    }

    private Map<String, Object> toTable(ExportDoc.Table table) {
        List<String> headers = table.headers();
        List<Object> rows = new ArrayList<>();
        for (List<Object> dataRow : table.rows()) {
            // showHeader=false 的 Table，JSON 仍以 headers 當 key
            Map<String, Object> obj = new LinkedHashMap<>();
            for (int i = 0; i < headers.size(); i++) {
                obj.put(headers.get(i), toJsonValue(dataRow.get(i)));
            }
            rows.add(obj);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", table.name());
        out.put("rows", rows);
        return out;
    }

    /**
     * 型別規則（本類別的核心價值）：
     * <ul>
     *   <li>{@code BigDecimal} → 原樣交給 Jackson 寫成 JSON number，<b>保留原精度</b>
     *       （不經任何 setScale／String.format／千分位）</li>
     *   <li>{@code LocalDate} → {@code yyyy-MM-dd}；{@code LocalDateTime} → {@code yyyy-MM-ddTHH:mm:ss}（不加位移）</li>
     *   <li>{@code Boolean} → JSON boolean（<b>不是</b>「是」／「否」，那是 Excel 的呈現）</li>
     *   <li>{@code List} → JSON 陣列（<b>不是</b> {@code \n} 串接後的單一字串）</li>
     *   <li>{@code null} → JSON null（不得以 {@code 0}／{@code "-"}／{@code ""} 充數）</li>
     * </ul>
     */
    private static Object toJsonValue(Object value) {
        if (value == null) return null;
        if (value instanceof LocalDate d) return ISO_DATE.format(d);
        if (value instanceof LocalDateTime dt) return ISO_LOCAL_DT.format(dt);
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object e : list) out.add(toJsonValue(e));
            return out;
        }
        return value;
    }
}
