package com.steven.assets.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.security.CurrentUserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.StringJoiner;

/**
 * 交易雷達結果快照的 Excel 區間匯出（Requirement 48）。
 *
 * <p>讀 {@link TradingRadarSnapshotStore} 的區間快照 → POI 三分頁（快照索引／大盤總覽／個股決策）→ byte[]。
 * 所有日期時間欄以 ISO 文字寫入，避免開啟端依時區偏移一天；部分快照被逐出時於「快照索引」彙總列揭露缺漏。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradingRadarExportService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final TradingRadarSnapshotStore store;
    private final CurrentUserContext currentUserContext;

    /** HTTP 手動匯出：owner 取自 request-scoped 的 CurrentUserContext。 */
    public byte[] export(String from, String to) throws IOException {
        long fromEpoch = parseEpoch(from);
        long toEpoch = parseEpoch(to);
        if (fromEpoch > toEpoch) {
            throw new IllegalArgumentException("起始時間不得晚於結束時間");
        }
        Long ownerId = currentUserContext.getEffectiveUserId();
        // 保留 null owner 分支：exportForOwner 收基本型別 long，直接委派會在 Long→long 拆箱時 NPE → 500，
        // 違反 Requirement 48「零快照仍回含表頭合法檔、不得回 5xx」。
        TradingRadarSnapshotStore.SnapshotRange range = (ownerId == null)
                ? new TradingRadarSnapshotStore.SnapshotRange(List.of(), 0, 0)
                : store.range(ownerId, fromEpoch, toEpoch);
        return build(range, from, to);
    }

    /**
     * 背景排程用：以**顯式 ownerId** 產檔（Requirement 48 追加 / Task 231）。
     * 背景無 request context，取不到 request-scoped 的 CurrentUserContext，故 owner 必須由呼叫端傳入。
     * 與手動匯出共用同一支 {@link #build}，確保兩種途徑內容一致。
     */
    public byte[] exportForOwner(long ownerId, long fromEpoch, long toEpoch) throws IOException {
        return build(store.range(ownerId, fromEpoch, toEpoch), isoLocal(fromEpoch), isoLocal(toEpoch));
    }

    /** 共用產檔：三分頁 Excel。 */
    private byte[] build(TradingRadarSnapshotStore.SnapshotRange range, String fromLabel, String toLabel)
            throws IOException {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Styles st = new Styles(wb);
            writeIndexSheet(wb, st, range, fromLabel, toLabel);
            writeMarketSheet(wb, st, range.snapshots());
            writeStockSheet(wb, st, range.snapshots());
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static String isoLocal(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(TAIPEI).toLocalDateTime().toString();
    }

    private long parseEpoch(String isoLocal) {
        try {
            return LocalDateTime.parse(isoLocal).atZone(TAIPEI).toInstant().toEpochMilli();
        } catch (Exception e) {
            throw new IllegalArgumentException("時間格式錯誤（需 ISO local datetime，如 2026-07-20T00:00:00）：" + isoLocal);
        }
    }

    private void writeIndexSheet(Workbook wb, Styles st, TradingRadarSnapshotStore.SnapshotRange range,
                                 String from, String to) {
        Sheet sheet = wb.createSheet("快照索引");
        int r = 0;
        Row summary = sheet.createRow(r++);
        String text = range.snapshots().isEmpty()
                ? "指定區間 " + from + "～" + to + " 查無交易雷達快照（索引預期 " + range.indexCount() + " 筆）"
                : "查得 " + range.snapshots().size() + " 筆／索引預期 " + range.indexCount()
                        + " 筆／缺漏 " + range.missingCount() + " 筆（已逾期或被 LRU 逐出）";
        cell(summary, 0, text, range.missingCount() > 0 ? st.warn : st.section);

        String[] headers = {"快照時間", "規則版本", "大盤 regime", "大盤中文", "大盤分數", "大盤 stale", "個股檔數", "略過非台股檔數"};
        Row h = sheet.createRow(r++);
        for (int i = 0; i < headers.length; i++) cell(h, i, headers[i], st.head);

        for (JsonNode s : range.snapshots()) {
            JsonNode m = s.path("market");
            JsonNode stocks = s.path("stocks");
            Row row = sheet.createRow(r++);
            cell(row, 0, txt(s, "generatedAt"), null);
            cell(row, 1, txt(s, "ruleVersion"), null);
            cell(row, 2, txt(m, "regime"), null);
            cell(row, 3, txt(m, "regimeLabel"), null);
            cell(row, 4, num(m, "score"), st.num2);
            cell(row, 5, bool(m, "stale"), null);
            cell(row, 6, stocks.isArray() ? stocks.size() : 0, null);
            cell(row, 7, num(s, "skippedNonTwStocks"), null);
        }
        autosize(sheet, headers.length);
    }

    private void writeMarketSheet(Workbook wb, Styles st, List<JsonNode> snapshots) {
        Sheet sheet = wb.createSheet("大盤總覽");
        // intraday / liveUpdatedAt 為 Task 228（TW_RULES_V6，大盤盤中即時判斷）新增的欄位：
        // intraday=true 代表該次 regime 由 Redis 即時大盤點位計算而非已入庫完成日 K；asOfDate 語意不變仍為完成日 K。
        String[] headers = {"快照時間", "regime", "中文", "分數", "資料完整", "stale", "盤中即時", "即時更新時間",
                "完成日K", "最新點位", "漲跌%",
                "MA20", "MA60", "MA240", "季線確認", "年線確認", "K", "D", "支持訊號", "風險提醒"};
        int r = 0;
        Row h = sheet.createRow(r++);
        for (int i = 0; i < headers.length; i++) cell(h, i, headers[i], st.head);
        for (JsonNode s : snapshots) {
            JsonNode m = s.path("market");
            Row row = sheet.createRow(r++);
            cell(row, 0, txt(s, "generatedAt"), null);
            cell(row, 1, txt(m, "regime"), null);
            cell(row, 2, txt(m, "regimeLabel"), null);
            cell(row, 3, num(m, "score"), st.num2);
            cell(row, 4, bool(m, "dataComplete"), null);
            cell(row, 5, bool(m, "stale"), null);
            cell(row, 6, bool(m, "intraday"), null);
            cell(row, 7, txt(m, "liveUpdatedAt"), null);
            cell(row, 8, txt(m, "asOfDate"), null);
            cell(row, 9, num(m, "price"), st.num2);
            cell(row, 10, num(m, "changePercent"), st.num2);
            cell(row, 11, num(m, "monthlyMa"), st.num2);
            cell(row, 12, num(m, "quarterlyMa"), st.num2);
            cell(row, 13, num(m, "annualMa"), st.num2);
            cell(row, 14, txt(m, "quarterlyConfirmation"), null);
            cell(row, 15, txt(m, "annualConfirmation"), null);
            cell(row, 16, num(m, "kValue"), st.num2);
            cell(row, 17, num(m, "dValue"), st.num2);
            cell(row, 18, list(m, "reasons"), null);
            cell(row, 19, list(m, "risks"), null);
        }
        autosize(sheet, headers.length);
    }

    private void writeStockSheet(Workbook wb, Styles st, List<JsonNode> snapshots) {
        Sheet sheet = wb.createSheet("個股決策");
        String[] headers = {"快照時間", "代碼", "名稱", "市場", "資產類別", "持有", "還原權息", "動作", "動作中文", "分數",
                "逆勢狀態", "逆勢中文", "現價", "漲跌%", "行情更新", "完成日K", "MA20", "MA60", "MA240",
                "月線確認", "季線確認", "年線確認", "K", "D", "匯率分位", "底層幣別", "資料完整",
                "支持訊號", "風險提醒", "逆勢條件", "逆勢風險"};
        int r = 0;
        Row h = sheet.createRow(r++);
        for (int i = 0; i < headers.length; i++) cell(h, i, headers[i], st.head);
        for (JsonNode s : snapshots) {
            String gen = txt(s, "generatedAt");
            JsonNode stocks = s.path("stocks");
            if (!stocks.isArray()) continue;
            for (JsonNode d : stocks) {
                Row row = sheet.createRow(r++);
                cell(row, 0, gen, null);
                cell(row, 1, txt(d, "stockCode"), null);
                cell(row, 2, txt(d, "stockName"), null);
                cell(row, 3, txt(d, "market"), null);
                cell(row, 4, txt(d, "assetClass"), null);
                cell(row, 5, bool(d, "held"), null);
                cell(row, 6, bool(d, "distributionAdjusted"), null);
                cell(row, 7, txt(d, "action"), null);
                cell(row, 8, txt(d, "actionLabel"), null);
                cell(row, 9, num(d, "score"), st.num2);
                cell(row, 10, txt(d, "counterTrendState"), null);
                cell(row, 11, txt(d, "counterTrendLabel"), null);
                cell(row, 12, num(d, "price"), st.num2);
                cell(row, 13, num(d, "changePercent"), st.num2);
                cell(row, 14, txt(d, "priceUpdatedAt"), null);
                cell(row, 15, txt(d, "asOfDate"), null);
                cell(row, 16, num(d, "monthlyMa"), st.num2);
                cell(row, 17, num(d, "quarterlyMa"), st.num2);
                cell(row, 18, num(d, "annualMa"), st.num2);
                cell(row, 19, txt(d, "monthlyConfirmation"), null);
                cell(row, 20, txt(d, "quarterlyConfirmation"), null);
                cell(row, 21, txt(d, "annualConfirmation"), null);
                cell(row, 22, num(d, "kValue"), st.num2);
                cell(row, 23, num(d, "dValue"), st.num2);
                cell(row, 24, num(d, "fxPercentile"), st.num2);
                cell(row, 25, txt(d, "underlyingCurrency"), null);
                cell(row, 26, bool(d, "dataComplete"), null);
                cell(row, 27, list(d, "reasons"), null);
                cell(row, 28, list(d, "risks"), null);
                cell(row, 29, list(d, "counterTrendReasons"), null);
                cell(row, 30, list(d, "counterTrendRisks"), null);
            }
        }
        autosize(sheet, headers.length);
    }

    // ── JsonNode 取值 helper（容忍缺欄位）──────────────────────────────
    private static String txt(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return (v.isMissingNode() || v.isNull()) ? "" : v.asText();
    }

    private static BigDecimal num(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isNumber() ? v.decimalValue() : null;
    }

    private static String bool(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return (v.isMissingNode() || v.isNull()) ? "" : (v.asBoolean() ? "是" : "否");
    }

    private static String list(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (!v.isArray()) return "";
        StringJoiner sj = new StringJoiner("\n");
        v.forEach(e -> sj.add(e.asText()));
        return sj.toString();
    }

    // ── POI helper（比照 ExcelExportService 慣例）──────────────────────
    private void cell(Row row, int col, Object value, CellStyle style) {
        Cell c = row.createCell(col);
        if (value == null) return;
        if (value instanceof BigDecimal bd) c.setCellValue(bd.doubleValue());
        else if (value instanceof Number nb) c.setCellValue(nb.doubleValue());
        else c.setCellValue(value.toString());
        if (style != null) c.setCellStyle(style);
    }

    private void autosize(Sheet sheet, int cols) {
        for (int i = 0; i < cols; i++) sheet.autoSizeColumn(i);
    }

    private static class Styles {
        final CellStyle head;
        final CellStyle section;
        final CellStyle warn;
        final CellStyle num2;

        Styles(Workbook wb) {
            Font headFont = wb.createFont();
            headFont.setBold(true);
            head = wb.createCellStyle();
            head.setFont(headFont);
            head.setAlignment(HorizontalAlignment.CENTER);

            Font secFont = wb.createFont();
            secFont.setBold(true);
            secFont.setFontHeightInPoints((short) 12);
            section = wb.createCellStyle();
            section.setFont(secFont);

            Font warnFont = wb.createFont();
            warnFont.setBold(true);
            warnFont.setColor(IndexedColors.RED.getIndex());
            warn = wb.createCellStyle();
            warn.setFont(warnFont);

            num2 = wb.createCellStyle();
            num2.setDataFormat(wb.createDataFormat().getFormat("#,##0.00"));
        }
    }
}
