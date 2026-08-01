package com.steven.assets.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.export.ExportDoc;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;

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
    // 雙格式匯出（Requirement 55 / Task 271）：三分頁改建 ExportDoc，xlsx 由 renderer 產出
    private final com.steven.assets.service.export.ExcelDocRenderer excelDocRenderer;

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
        return excelDocRenderer.render(radarDoc(range, from, to));
    }

    /**
     * 交易雷達的 {@link ExportDoc}（Requirement 55 / Task 271）。
     *
     * <p>排程端<b>只呼叫一次</b>取得 doc，再 render 成 xlsx 與 JSON——本匯出點吃 Redis 快照，
     * 呼叫兩次等於查兩次，兩份檔可能對不起來。
     */
    public ExportDoc radarDoc(long ownerId, long fromEpoch, long toEpoch) {
        return radarDoc(store.range(ownerId, fromEpoch, toEpoch), isoLocal(fromEpoch), isoLocal(toEpoch));
    }

    /**
     * 背景排程用：以**顯式 ownerId** 產檔（Requirement 48 追加 / Task 231）。
     * 背景無 request context，取不到 request-scoped 的 CurrentUserContext，故 owner 必須由呼叫端傳入。
     * 與手動匯出共用同一支 {@link #build}，確保兩種途徑內容一致。
     */
    public byte[] exportForOwner(long ownerId, long fromEpoch, long toEpoch) throws IOException {
        return excelDocRenderer.render(radarDoc(ownerId, fromEpoch, toEpoch));
    }

    /** 共用產檔：三分頁 doc。手動匯出與背景排程共用同一支，確保兩種途徑內容一致。 */
    private ExportDoc radarDoc(TradingRadarSnapshotStore.SnapshotRange range,
                               String fromLabel, String toLabel) {
        return new ExportDoc("交易雷達", List.of(
                indexSheet(range, fromLabel, toLabel),
                marketSheet(range.snapshots()),
                stockSheet(range.snapshots())));
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

    private ExportDoc.Sheet indexSheet(TradingRadarSnapshotStore.SnapshotRange range, String from, String to) {
        // r0：條件樣式的提示列——缺漏時走 WARN，否則走 SECTION_12（本服務的 section 是 12pt，不是 13pt）
        String text = range.snapshots().isEmpty()
                ? "指定區間 " + from + "～" + to + " 查無交易雷達快照（索引預期 " + range.indexCount() + " 筆）"
                : "查得 " + range.snapshots().size() + " 筆／索引預期 " + range.indexCount()
                        + " 筆／缺漏 " + range.missingCount() + " 筆（已逾期或被 LRU 逐出）";
        ExportDoc.Line note = new ExportDoc.Line(text,
                range.missingCount() > 0 ? ExportDoc.LineStyle.WARN : ExportDoc.LineStyle.SECTION_12);

        List<String> headers = List.of("快照時間", "規則版本", "大盤 regime", "大盤中文",
                "大盤分數", "大盤 stale", "個股檔數", "略過非台股檔數");
        List<List<Object>> rows = new ArrayList<>();
        for (JsonNode s : range.snapshots()) {
            JsonNode m = s.path("market");
            JsonNode stocks = s.path("stocks");
            rows.add(Arrays.asList(
                    txt(s, "generatedAt"), txt(s, "ruleVersion"),
                    txt(m, "regime"), txt(m, "regimeLabel"),
                    num(m, "score"), boolVal(m, "stale"),
                    stocks.isArray() ? stocks.size() : 0, num(s, "skippedNonTwStocks")));
        }
        return new ExportDoc.Sheet("快照索引",
                List.of(note, new ExportDoc.Table(null, null, headers, true, false, false,
                        List.of(ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.NUM2, ExportDoc.Format.BOOL_ZH, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT), rows)),
                headers.size());
    }

    private ExportDoc.Sheet marketSheet(List<JsonNode> snapshots) {
        // intraday / liveUpdatedAt 為 Task 228（TW_RULES_V6，大盤盤中即時判斷）新增的欄位：
        // intraday=true 代表該次 regime 由 Redis 即時大盤點位計算而非已入庫完成日 K；asOfDate 語意不變仍為完成日 K。
        // 本分頁沒有 section 標題列，第 0 列就是表頭列——不得新增任何列。
        List<String> headers = List.of("快照時間", "regime", "中文", "分數", "資料完整", "stale", "盤中即時",
                "即時更新時間", "完成日K", "最新點位", "漲跌%",
                "MA20", "MA60", "MA240", "季線確認", "年線確認", "K", "D", "支持訊號", "風險提醒");
        List<List<Object>> rows = new ArrayList<>();
        for (JsonNode s : snapshots) {
            JsonNode m = s.path("market");
            rows.add(Arrays.asList(
                    txt(s, "generatedAt"), txt(m, "regime"), txt(m, "regimeLabel"), num(m, "score"),
                    boolVal(m, "dataComplete"), boolVal(m, "stale"), boolVal(m, "intraday"),
                    txt(m, "liveUpdatedAt"), txt(m, "asOfDate"),
                    num(m, "price"), num(m, "changePercent"),
                    num(m, "monthlyMa"), num(m, "quarterlyMa"), num(m, "annualMa"),
                    txt(m, "quarterlyConfirmation"), txt(m, "annualConfirmation"),
                    num(m, "kValue"), num(m, "dValue"),
                    listVal(m, "reasons"), listVal(m, "risks")));
        }
        return new ExportDoc.Sheet("大盤總覽",
                List.of(new ExportDoc.Table(null, null, headers, true, false, false, List.of(ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.NUM2, ExportDoc.Format.BOOL_ZH, ExportDoc.Format.BOOL_ZH, ExportDoc.Format.BOOL_ZH, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.NUM2, ExportDoc.Format.NUM2, ExportDoc.Format.NUM2, ExportDoc.Format.NUM2, ExportDoc.Format.NUM2, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.NUM2, ExportDoc.Format.NUM2, ExportDoc.Format.LIST_LINES, ExportDoc.Format.LIST_LINES), rows)),
                headers.size());
    }

    private ExportDoc.Sheet stockSheet(List<JsonNode> snapshots) {
        // 同上：沒有 section 標題列，第 0 列即表頭列。
        List<String> headers = List.of("快照時間", "代碼", "名稱", "市場", "資產類別", "持有", "還原權息",
                "動作", "動作中文", "分數", "逆勢狀態", "逆勢中文", "現價", "漲跌%", "行情更新", "完成日K",
                "MA20", "MA60", "MA240", "月線確認", "季線確認", "年線確認", "K", "D", "匯率分位",
                "底層幣別", "資料完整", "支持訊號", "風險提醒", "逆勢條件", "逆勢風險");
        List<List<Object>> rows = new ArrayList<>();
        for (JsonNode s : snapshots) {
            String gen = txt(s, "generatedAt");
            JsonNode stocks = s.path("stocks");
            if (!stocks.isArray()) continue;
            for (JsonNode d : stocks) {
                rows.add(Arrays.asList(
                        gen, txt(d, "stockCode"), txt(d, "stockName"), txt(d, "market"),
                        txt(d, "assetClass"), boolVal(d, "held"), boolVal(d, "distributionAdjusted"),
                        txt(d, "action"), txt(d, "actionLabel"), num(d, "score"),
                        txt(d, "counterTrendState"), txt(d, "counterTrendLabel"),
                        num(d, "price"), num(d, "changePercent"),
                        txt(d, "priceUpdatedAt"), txt(d, "asOfDate"),
                        num(d, "monthlyMa"), num(d, "quarterlyMa"), num(d, "annualMa"),
                        txt(d, "monthlyConfirmation"), txt(d, "quarterlyConfirmation"),
                        txt(d, "annualConfirmation"), num(d, "kValue"), num(d, "dValue"),
                        num(d, "fxPercentile"), txt(d, "underlyingCurrency"), boolVal(d, "dataComplete"),
                        listVal(d, "reasons"), listVal(d, "risks"),
                        listVal(d, "counterTrendReasons"), listVal(d, "counterTrendRisks")));
            }
        }
        return new ExportDoc.Sheet("個股決策",
                List.of(new ExportDoc.Table(null, null, headers, true, false, false, List.of(ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.BOOL_ZH, ExportDoc.Format.BOOL_ZH, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.NUM2, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.NUM2, ExportDoc.Format.NUM2, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.NUM2, ExportDoc.Format.NUM2, ExportDoc.Format.NUM2, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.NUM2, ExportDoc.Format.NUM2, ExportDoc.Format.NUM2, ExportDoc.Format.TEXT, ExportDoc.Format.BOOL_ZH, ExportDoc.Format.LIST_LINES, ExportDoc.Format.LIST_LINES, ExportDoc.Format.LIST_LINES, ExportDoc.Format.LIST_LINES), rows)),
                headers.size());
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

    /**
     * boolean 欄的<b>語意值</b>（Requirement 55 / Task 271）：缺值回 {@code null}。
     * Excel 的「是」／「否」呈現交給 {@code Format.BOOL_ZH}、JSON 則輸出 boolean——
     * 既有 {@link #bool} 把兩者壓成同一個字串，照用會讓 JSON 拿到 "是" 而違反本需求的驗收條件。
     */
    private static Boolean boolVal(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return (v.isMissingNode() || v.isNull()) ? null : v.asBoolean();
    }

    /**
     * 陣列欄的<b>語意值</b>：非陣列回 {@code null}。
     * Excel 的 {@code \n} 串接交給 {@code Format.LIST_LINES}、JSON 則輸出字串陣列。
     */
    private static List<String> listVal(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (!v.isArray()) return null;
        List<String> out = new ArrayList<>();
        v.forEach(e -> out.add(e.asText()));
        return out;
    }




}
