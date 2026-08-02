package com.steven.assets.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.export.ExportDoc;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
        // Task 280：週線MA5 插在 MA20 之前、14 個擴充指標插在 D 之後（20 → 35 欄）。
        // headers／formats／rows 三者長度與順序必須一致——ExportDoc.Table 只在 runtime 才擲長度不符。
        List<String> headers = new ArrayList<>(List.of("快照時間", "regime", "中文", "分數", "資料完整", "stale", "盤中即時",
                "即時更新時間", "完成日K", "最新點位", "漲跌%",
                "週線MA5",
                "MA20", "MA60", "MA240", "季線確認", "年線確認", "K", "D"));
        headers.addAll(EXT_HEADERS);
        headers.addAll(List.of("支持訊號", "風險提醒"));

        List<List<Object>> rows = new ArrayList<>();
        for (JsonNode s : snapshots) {
            JsonNode m = s.path("market");
            List<Object> row = new ArrayList<>(Arrays.asList(
                    txt(s, "generatedAt"), txt(m, "regime"), txt(m, "regimeLabel"), num(m, "score"),
                    boolVal(m, "dataComplete"), boolVal(m, "stale"), boolVal(m, "intraday"),
                    txt(m, "liveUpdatedAt"), txt(m, "asOfDate"),
                    num(m, "price"), num(m, "changePercent"),
                    num(m, "weeklyMa"),
                    num(m, "monthlyMa"), num(m, "quarterlyMa"), num(m, "annualMa"),
                    txt(m, "quarterlyConfirmation"), txt(m, "annualConfirmation"),
                    num(m, "kValue"), num(m, "dValue")));
            row.addAll(extCells(m));
            row.addAll(Arrays.asList(listVal(m, "reasons"), listVal(m, "risks")));
            rows.add(row);
        }

        // 逐行對齊上面的 headers（35 欄）。Task 264 的插欄事故就是這一串靜默保留成舊版，故不再寫成單行。
        List<ExportDoc.Format> formats = new ArrayList<>(List.of(
                ExportDoc.Format.TEXT,      // 0  快照時間
                ExportDoc.Format.TEXT,      // 1  regime
                ExportDoc.Format.TEXT,      // 2  中文
                ExportDoc.Format.NUM2,      // 3  分數
                ExportDoc.Format.BOOL_ZH,   // 4  資料完整
                ExportDoc.Format.BOOL_ZH,   // 5  stale
                ExportDoc.Format.BOOL_ZH,   // 6  盤中即時
                ExportDoc.Format.TEXT,      // 7  即時更新時間
                ExportDoc.Format.TEXT,      // 8  完成日K
                ExportDoc.Format.NUM2,      // 9  最新點位
                ExportDoc.Format.NUM2,      // 10 漲跌%
                ExportDoc.Format.NUM2,      // 11 週線MA5      ← Task 280
                ExportDoc.Format.NUM2,      // 12 MA20
                ExportDoc.Format.NUM2,      // 13 MA60
                ExportDoc.Format.NUM2,      // 14 MA240
                ExportDoc.Format.TEXT,      // 15 季線確認
                ExportDoc.Format.TEXT,      // 16 年線確認
                ExportDoc.Format.NUM2,      // 17 K
                ExportDoc.Format.NUM2       // 18 D
        ));
        for (int i = 0; i < EXT_HEADERS.size(); i++) formats.add(ExportDoc.Format.NUM2); // 19–32 Task 280 擴充指標
        formats.add(ExportDoc.Format.LIST_LINES);   // 33 支持訊號
        formats.add(ExportDoc.Format.LIST_LINES);   // 34 風險提醒

        return new ExportDoc.Sheet("大盤總覽",
                List.of(new ExportDoc.Table(null, null, headers, true, false, false, formats, rows)),
                headers.size());
    }

    private ExportDoc.Sheet stockSheet(List<JsonNode> snapshots) {
        // 同上：沒有 section 標題列，第 0 列即表頭列。
        // Task 280：週線MA5 插在 MA20 之前、14 個擴充指標插在 D 之後（35 → 50 欄）。
        List<String> headers = new ArrayList<>(List.of("快照時間", "代碼", "名稱", "市場", "資產類別", "持有", "還原權息",
                "動作", "動作中文", "分數", "逆勢狀態", "逆勢中文", "現價", "漲跌%", "行情更新", "完成日K",
                "週線MA5",
                "MA20", "MA60", "MA240", "月線確認", "季線確認", "年線確認", "K", "D"));
        headers.addAll(EXT_HEADERS);
        headers.addAll(List.of("匯率分位", "底層幣別", "資料完整",
                // Task 264：二維決策的時機維度與其兩個輸入；ETF 折溢價（非 ETF 留白）
                "時機", "季線乖離%", "52週位置", "折溢價%",
                "支持訊號", "風險提醒", "逆勢條件", "逆勢風險"));

        List<List<Object>> rows = new ArrayList<>();
        for (JsonNode s : snapshots) {
            String gen = txt(s, "generatedAt");
            JsonNode stocks = s.path("stocks");
            if (!stocks.isArray()) continue;
            for (JsonNode d : stocks) {
                List<Object> row = new ArrayList<>(Arrays.asList(
                        gen, txt(d, "stockCode"), txt(d, "stockName"), txt(d, "market"),
                        txt(d, "assetClass"), boolVal(d, "held"), boolVal(d, "distributionAdjusted"),
                        txt(d, "action"), txt(d, "actionLabel"), num(d, "score"),
                        txt(d, "counterTrendState"), txt(d, "counterTrendLabel"),
                        num(d, "price"), num(d, "changePercent"),
                        txt(d, "priceUpdatedAt"), txt(d, "asOfDate"),
                        num(d, "weeklyMa"),
                        num(d, "monthlyMa"), num(d, "quarterlyMa"), num(d, "annualMa"),
                        txt(d, "monthlyConfirmation"), txt(d, "quarterlyConfirmation"),
                        txt(d, "annualConfirmation"), num(d, "kValue"), num(d, "dValue")));
                row.addAll(extCells(d));
                row.addAll(Arrays.asList(
                        num(d, "fxPercentile"), txt(d, "underlyingCurrency"), boolVal(d, "dataComplete"),
                        // Task 264 的四欄（index 42–45）；缺欄位時 txt()→""、num()→null，與 main 的 cell() 一致
                        txt(d, "timingLabel"), num(d, "ma60BiasPercent"),
                        num(d, "week52Position"), num(d, "etfPremiumPct"),
                        listVal(d, "reasons"), listVal(d, "risks"),
                        listVal(d, "counterTrendReasons"), listVal(d, "counterTrendRisks")));
                rows.add(row);
            }
        }
        // 逐列對齊上面的 headers（50 欄）。**headers／此清單／rows 三者長度與順序必須一致**——
        // Task 264 插欄時這一串落在 git 衝突標記之外、被三方合併靜默保留成舊的 31 欄版，
        // `ExportDoc.Table` 的 compact constructor 才在 runtime 擲長度不符。拆成多行就是為了讓下次看得見。
        List<ExportDoc.Format> formats = new ArrayList<>(List.of(
                ExportDoc.Format.TEXT,      // 0  快照時間
                ExportDoc.Format.TEXT,      // 1  代碼
                ExportDoc.Format.TEXT,      // 2  名稱
                ExportDoc.Format.TEXT,      // 3  市場
                ExportDoc.Format.TEXT,      // 4  資產類別
                ExportDoc.Format.BOOL_ZH,   // 5  持有
                ExportDoc.Format.BOOL_ZH,   // 6  還原權息
                ExportDoc.Format.TEXT,      // 7  動作
                ExportDoc.Format.TEXT,      // 8  動作中文
                ExportDoc.Format.NUM2,      // 9  分數
                ExportDoc.Format.TEXT,      // 10 逆勢狀態
                ExportDoc.Format.TEXT,      // 11 逆勢中文
                ExportDoc.Format.NUM2,      // 12 現價
                ExportDoc.Format.NUM2,      // 13 漲跌%
                ExportDoc.Format.TEXT,      // 14 行情更新
                ExportDoc.Format.TEXT,      // 15 完成日K
                ExportDoc.Format.NUM2,      // 16 週線MA5      ← Task 280
                ExportDoc.Format.NUM2,      // 17 MA20
                ExportDoc.Format.NUM2,      // 18 MA60
                ExportDoc.Format.NUM2,      // 19 MA240
                ExportDoc.Format.TEXT,      // 20 月線確認
                ExportDoc.Format.TEXT,      // 21 季線確認
                ExportDoc.Format.TEXT,      // 22 年線確認
                ExportDoc.Format.NUM2,      // 23 K
                ExportDoc.Format.NUM2       // 24 D
        ));
        for (int i = 0; i < EXT_HEADERS.size(); i++) formats.add(ExportDoc.Format.NUM2); // 25–38 Task 280 擴充指標
        formats.addAll(List.of(
                ExportDoc.Format.NUM2,      // 39 匯率分位
                ExportDoc.Format.TEXT,      // 40 底層幣別
                ExportDoc.Format.BOOL_ZH,   // 41 資料完整
                ExportDoc.Format.TEXT,      // 42 時機          ┐ Task 264
                ExportDoc.Format.NUM2,      // 43 季線乖離%      │
                ExportDoc.Format.NUM2,      // 44 52週位置      │
                ExportDoc.Format.NUM2,      // 45 折溢價%       ┘
                ExportDoc.Format.LIST_LINES,// 46 支持訊號
                ExportDoc.Format.LIST_LINES,// 47 風險提醒
                ExportDoc.Format.LIST_LINES,// 48 逆勢條件
                ExportDoc.Format.LIST_LINES // 49 逆勢風險
        ));
        return new ExportDoc.Sheet("個股決策",
                List.of(new ExportDoc.Table(null, null, headers, true, false, false, formats, rows)),
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
     * 巢狀子節點的數值欄（Task 280）：父節點缺漏或為 null 時回 {@code null}。
     *
     * <p>本功能上線前寫入的 Redis 快照沒有 {@code extendedIndicators}；{@code JsonNode.path()}
     * 對 MissingNode 與 NullNode 都回 MissingNode，故舊快照自然得到 {@code null}
     * → Excel 空白格、JSON {@code null}。<b>不得補 0、"-" 或空字串。</b></p>
     */
    private static BigDecimal num(JsonNode parent, String child, String field) {
        return num(parent.path(child), field);
    }

    /** Task 280 新增的 14 個擴充指標欄，順序即匯出欄序。 */
    private static final List<String> EXT_KEYS = List.of(
            "j9", "k3d2", "rsv", "ema12", "ema26", "dif", "macd", "osc",
            "rsi5", "rsi10", "bias10", "bias20", "b10b20", "wr9");

    private static final List<String> EXT_HEADERS = List.of(
            "J9", "K3D2", "RSV", "EMA12", "EMA26", "DIF", "MACD", "OSC",
            "RSI5", "RSI10", "BIAS10", "BIAS20", "BIAS10-BIAS20", "W%R9");

    /** 依 {@link #EXT_KEYS} 順序取出 14 個值；缺欄位為 null。 */
    private static List<Object> extCells(JsonNode n) {
        List<Object> out = new ArrayList<>(EXT_KEYS.size());
        for (String k : EXT_KEYS) out.add(num(n, "extendedIndicators", k));
        return out;
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
