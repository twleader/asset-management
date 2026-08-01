package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把「當日公開資訊」的 payload 額外產出一份 Excel（Requirement 55 / Task 272）。
 *
 * <p><b>刻意不共用 backend 的 {@code ExportDoc}／renderer。</b> 三個 Maven 專案
 * （{@code backend}／{@code bff}／{@code external-materials-service}）無父 pom、不共用程式碼，
 * Requirement 50 已就「不為兩處數十行程式碼建共用 module」定案，故 ext 端自帶這一份極小的轉換。
 *
 * <p><b>既有的 {@code public_info_*.json} 是 SRPP 退休規劃專案的輸入契約</b>：本類別只讀
 * {@link NewsPoller} 已組好的同一份 payload，<b>絕不回頭改動 JSON 的形狀</b>。
 */
@Component
public class PublicInfoXlsxWriter {

    /** metadata 區的欄位；與 {@code NewsPoller.exportPublicInfoJson} 組 payload 的順序一致。 */
    private static final List<String> META_KEYS =
            List.of("generatedAt", "trigger", "tradingDayCutoff", "count");

    private final ObjectMapper objectMapper;

    public PublicInfoXlsxWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 產出 xlsx bytes。單一工作表「公開資訊」＝ metadata 鍵值列 ＋ 空行 ＋ items 表。
     *
     * <p><b>表頭由 items 動態推導</b>（所有元素 key 的聯集、以第一個元素的順序為基準）——
     * 硬編一份欄位清單的話，{@code NewsRow} 日後加欄位時 Excel 會靜默少一欄，而 JSON 有、Excel 沒有。
     *
     * <p>{@code items} 為空時仍產出含 metadata 的合法檔。
     */
    public byte[] build(Map<String, Object> payload) throws IOException {
        // 走 ObjectMapper 轉一次：items 是 List<NewsRow>（record，且 tags 標了 @JsonIgnore），
        // 直接反射取值會與 JSON 那一份的欄位集合不一致——要的正是「與 JSON 同一組欄位」。
        @SuppressWarnings("unchecked")
        Map<String, Object> normalized = objectMapper.convertValue(payload, Map.class);

        Object rawItems = normalized.get("items");
        List<Map<String, Object>> items = new ArrayList<>();
        if (rawItems instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    m.forEach((k, v) -> row.put(String.valueOf(k), v));
                    items.add(row);
                }
            }
        }

        List<String> headers = new ArrayList<>();
        for (Map<String, Object> row : items) {
            for (String k : row.keySet()) {
                if (!headers.contains(k)) headers.add(k);
            }
        }

        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Font headFont = wb.createFont();
            headFont.setBold(true);
            CellStyle head = wb.createCellStyle();
            head.setFont(headFont);
            head.setAlignment(HorizontalAlignment.CENTER);

            Sheet sheet = wb.createSheet("公開資訊");
            int r = 0;
            for (String key : META_KEYS) {
                Row row = sheet.createRow(r++);
                cell(row, 0, key, head);
                cell(row, 1, normalized.get(key), null);
            }
            r++;   // 空行

            Row h = sheet.createRow(r++);
            for (int i = 0; i < headers.size(); i++) cell(h, i, headers.get(i), head);

            for (Map<String, Object> item : items) {
                Row row = sheet.createRow(r++);
                for (int i = 0; i < headers.size(); i++) cell(row, i, item.get(headers.get(i)), null);
            }

            for (int i = 0; i < Math.max(headers.size(), 2); i++) sheet.autoSizeColumn(i);
            wb.write(out);
            return out.toByteArray();
        }
    }

    /** 比照 backend 的既有慣例：先 createCell，值為 null 就直接 return（產生存在的 BLANK 格）。 */
    private static void cell(Row row, int col, Object value, CellStyle style) {
        Cell c = row.createCell(col);
        if (value == null) return;
        if (value instanceof Number n) c.setCellValue(n.doubleValue());
        else if (value instanceof Boolean b) c.setCellValue(b);
        else c.setCellValue(String.valueOf(value));
        if (style != null) c.setCellStyle(style);
    }
}
