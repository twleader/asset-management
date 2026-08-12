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
 * <p>讀 {@link TradingRadarSnapshotStore} 的區間快照 → 四分頁（快照索引／大盤總覽／個股決策／台美公開資訊）→ byte[]。
 * 所有日期時間欄以 ISO 文字寫入，避免開啟端依時區偏移一天；部分快照被逐出時於「快照索引」彙總列揭露缺漏。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradingRadarExportService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final TradingRadarSnapshotStore store;
    private final CurrentUserContext currentUserContext;
    // 雙格式匯出：四分頁共用同一份 ExportDoc，xlsx／JSON 不得各自組裝而漂移。
    private final com.steven.assets.service.export.ExcelDocRenderer excelDocRenderer;

    /**
     * 頁首手動匯出的 doc（Task 283）：**只查一次 Redis**，供呼叫端 render 成下載用 xlsx 與落檔用的兩份。
     *
     * @param snapshotCount 查得的快照筆數。<b>取 {@code range.snapshots().size()}，不是 {@code indexCount()}</b>
     *                      ——索引還在但 value 全被 TTL／LRU 逐出時 {@code indexCount > 0} 而 snapshots 為空，
     *                      取錯會讓落檔端用「只有表頭的檔」覆寫掉當日排程產出的好檔。
     */
    public record ManualDoc(ExportDoc doc, int snapshotCount) {}

    /**
     * HTTP 手動匯出的 doc：owner 取自 request-scoped 的 CurrentUserContext（可能為 null）。
     *
     * <p>格式錯誤與 {@code from > to} 在此轉成 {@link IllegalArgumentException}（→ 400）；
     * 呼叫端要用 {@code to} 的日期組檔名時<b>必須排在本方法之後</b>，自行提前 parse 會擲
     * {@code DateTimeParseException} → 500。</p>
     */
    public ManualDoc manualDoc(String from, String to) {
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
        return new ManualDoc(radarDoc(range, from, to), range.snapshots().size());
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
     * 與手動匯出共用同一支 doc builder，確保兩種途徑內容一致。
     */
    public byte[] exportForOwner(long ownerId, long fromEpoch, long toEpoch) throws IOException {
        return excelDocRenderer.render(radarDoc(ownerId, fromEpoch, toEpoch));
    }

    /** 共用產檔：四分頁 doc。手動匯出與背景排程共用同一支。 */
    private ExportDoc radarDoc(TradingRadarSnapshotStore.SnapshotRange range,
                               String fromLabel, String toLabel) {
        return new ExportDoc("交易雷達", List.of(
                indexSheet(range, fromLabel, toLabel),
                marketSheet(range.snapshots()),
                stockSheet(range.snapshots()),
                publicInformationSheet(range.snapshots())));
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

        List<String> headers = List.of("快照時間", "規則版本", "動作政策版本", "大盤 regime", "大盤中文",
                "大盤分數", "大盤 stale", "個股檔數", "略過非台股非美股檔數");
        List<List<Object>> rows = new ArrayList<>();
        for (JsonNode s : range.snapshots()) {
            JsonNode m = s.path("market");
            JsonNode stocks = s.path("stocks");
            rows.add(Arrays.asList(
                    txt(s, "generatedAt"), txt(s, "ruleVersion"),
                    nullableText(s, "actionPolicyVersion"),
                    txt(m, "regime"), txt(m, "regimeLabel"),
                    num(m, "score"), boolVal(m, "stale"),
                    stocks.isArray() ? stocks.size() : 0, num(s, "skippedNonTwStocks")));
        }
        return new ExportDoc.Sheet("快照索引",
                List.of(note, new ExportDoc.Table(null, null, headers, true, false, false,
                        List.of(ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT,
                                ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.NUM2,
                                ExportDoc.Format.BOOL_ZH, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT), rows)),
                headers.size());
    }

    private ExportDoc.Sheet marketSheet(List<JsonNode> snapshots) {
        // intraday / liveUpdatedAt 為 Task 228（TW_RULES_V6，大盤盤中即時判斷）新增的欄位：
        // intraday=true 代表該次 regime 由 Redis 即時大盤點位計算而非已入庫完成日 K；asOfDate 語意不變仍為完成日 K。
        // 本分頁沒有 section 標題列，第 0 列就是表頭列——不得新增任何列。
        // V11：週線 MA5、完整擴充指標、大盤量能與前一美股科技共同完成日一併匯出。
        // headers／formats／rows 三者長度與順序必須一致——ExportDoc.Table 只在 runtime 才擲長度不符。
        List<String> headers = new ArrayList<>(List.of("快照時間", "regime", "中文", "分數", "資料完整", "stale", "盤中即時",
                "即時更新時間", "完成日K", "最新點位", "漲跌%", "行情狀態",
                "週線MA5",
                "MA20", "MA60", "MA240", "季線確認", "年線確認", "K", "D"));
        headers.addAll(EXT_HEADERS);
        headers.addAll(List.of("大盤量比", "大盤成交金額比", "量能完成日",
                "NASDAQ漲跌%", "SOX漲跌%", "美股科技綜合%", "美股科技完成日", "美股科技可用",
                "支持訊號", "風險提醒"));

        List<List<Object>> rows = new ArrayList<>();
        for (JsonNode s : snapshots) {
            JsonNode m = s.path("market");
            List<Object> row = new ArrayList<>(Arrays.asList(
                    txt(s, "generatedAt"), txt(m, "regime"), txt(m, "regimeLabel"), num(m, "score"),
                    boolVal(m, "dataComplete"), boolVal(m, "stale"), boolVal(m, "intraday"),
                    txt(m, "liveUpdatedAt"), txt(m, "asOfDate"),
                    num(m, "price"), num(m, "changePercent"), txt(m, "quoteStatus"),
                    num(m, "weeklyMa"),
                    num(m, "monthlyMa"), num(m, "quarterlyMa"), num(m, "annualMa"),
                    txt(m, "quarterlyConfirmation"), txt(m, "annualConfirmation"),
                    num(m, "kValue"), num(m, "dValue")));
            row.addAll(extCells(m));
            row.addAll(Arrays.asList(
                    num(m, "marketVolumeRatio"), num(m, "marketTurnoverRatio"), txt(m, "marketVolumeAsOfDate"),
                    num(m, "nasdaqChangePercent"), num(m, "soxChangePercent"),
                    num(m, "usTechCompositePercent"), txt(m, "usTechAsOfDate"), boolVal(m, "usTechAvailable"),
                    listVal(m, "reasons"), listVal(m, "risks")));
            rows.add(row);
        }

        // 逐行對齊上面的 headers（36 欄）。Task 264 的插欄事故就是這一串靜默保留成舊版，故不再寫成單行。
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
                ExportDoc.Format.TEXT,      // 11 行情狀態
                ExportDoc.Format.NUM2,      // 12 週線MA5      ← Task 281
                ExportDoc.Format.NUM2,      // 13 MA20
                ExportDoc.Format.NUM2,      // 14 MA60
                ExportDoc.Format.NUM2,      // 15 MA240
                ExportDoc.Format.TEXT,      // 16 季線確認
                ExportDoc.Format.TEXT,      // 17 年線確認
                ExportDoc.Format.NUM2,      // 18 K
                ExportDoc.Format.NUM2       // 19 D
        ));
        for (int i = 0; i < EXT_HEADERS.size(); i++) formats.add(ExportDoc.Format.NUM2);
        formats.addAll(List.of(
                ExportDoc.Format.NUM2, ExportDoc.Format.NUM2, ExportDoc.Format.TEXT,
                ExportDoc.Format.NUM2, ExportDoc.Format.NUM2, ExportDoc.Format.NUM2,
                ExportDoc.Format.TEXT, ExportDoc.Format.BOOL_ZH));
        formats.add(ExportDoc.Format.LIST_LINES);
        formats.add(ExportDoc.Format.LIST_LINES);

        return new ExportDoc.Sheet("大盤總覽",
                List.of(new ExportDoc.Table(null, null, headers, true, false, false, formats, rows)),
                headers.size());
    }

    private ExportDoc.Sheet stockSheet(List<JsonNode> snapshots) {
        // 同上：沒有 section 標題列，第 0 列即表頭列。
        // V11 同時保留短／中期欄位，並追加逐因子可稽核的基本面／產業來源。
        List<String> headers = new ArrayList<>(List.of("快照時間", "代碼", "名稱", "市場", "資產類別", "持有", "還原權息",
                "短期動作", "短期動作中文", "短期分數",
                "中期動作", "中期動作中文", "中期分數", "短中期分歧", "獲利了結確認",
                "逆勢狀態", "逆勢中文", "現價", "漲跌%", "行情狀態", "行情更新", "完成日K",
                "週線MA5",
                "MA20", "MA60", "MA240", "月線確認", "季線確認", "年線確認", "K", "D"));
        headers.addAll(EXT_HEADERS);
        headers.addAll(List.of("個股量比", "匯率分位", "匯率完成日", "底層幣別", "資料完整",
                "時機", "季線乖離%", "52週位置", "折溢價%"));
        headers.addAll(FUNDAMENTAL_HEADERS);
        headers.addAll(List.of(
                "短期支持訊號", "短期風險提醒", "中期支持訊號", "中期風險提醒", "逆勢條件", "逆勢風險"));
        headers.addAll(EVIDENCE_HEADERS);
        headers.addAll(DETAIL_EVIDENCE_HEADERS);
        headers.addAll(VALUATION_COMPONENT_HEADERS);
        // Task 320：即時折溢價欄一律附加在<b>整張表的真正最末</b>。
        // 「折溢價%」（索引 53、值取自 dated 的 etfPremiumPct）看起來像末欄，其實其後尚有 118 欄；
        // 插在它後面會把「折溢價時點／折溢價來源／折溢價stale」等全部往後推兩格，
        // 而既有 golden 逐格比對與下游取值皆以欄索引定位（同 Task 285／286 的理由）。
        headers.addAll(LIVE_PREMIUM_HEADERS);

        List<List<Object>> rows = new ArrayList<>();
        for (JsonNode s : snapshots) {
            String gen = txt(s, "generatedAt");
            JsonNode stocks = s.path("stocks");
            if (!stocks.isArray()) continue;
            for (JsonNode d : stocks) {
                List<Object> row = new ArrayList<>(Arrays.asList(
                        gen, txt(d, "stockCode"), txt(d, "stockName"), txt(d, "market"),
                        txt(d, "assetClass"), boolVal(d, "held"), boolVal(d, "distributionAdjusted"),
                        txt(d, "shortAction"), txt(d, "shortActionLabel"), num(d, "shortScore"),
                        txt(d, "action"), txt(d, "actionLabel"), num(d, "score"),
                        boolVal(d, "horizonConflict"), boolVal(d, "profitTakingConfirmed"),
                        txt(d, "counterTrendState"), txt(d, "counterTrendLabel"),
                        num(d, "price"), num(d, "changePercent"), txt(d, "quoteStatus"),
                        txt(d, "priceUpdatedAt"), txt(d, "asOfDate"),
                        num(d, "weeklyMa"),
                        num(d, "monthlyMa"), num(d, "quarterlyMa"), num(d, "annualMa"),
                        txt(d, "monthlyConfirmation"), txt(d, "quarterlyConfirmation"),
                        txt(d, "annualConfirmation"), num(d, "kValue"), num(d, "dValue")));
                row.addAll(extCells(d));
                row.addAll(Arrays.asList(
                        num(d, "volumeRatio"), num(d, "fxPercentile"), txt(d, "fxAsOfDate"),
                        txt(d, "underlyingCurrency"), boolVal(d, "dataComplete"),
                        txt(d, "timingLabel"), num(d, "ma60BiasPercent"),
                        num(d, "week52Position"), num(d, "etfPremiumPct")));
                row.addAll(fundamentalCells(d.path("fundamental")));
                row.addAll(Arrays.asList(
                        listVal(d, "shortReasons"), listVal(d, "shortRisks"),
                        listVal(d, "reasons"), listVal(d, "risks"),
                        listVal(d, "counterTrendReasons"), listVal(d, "counterTrendRisks")));
                JsonNode evidence = d.path("evidence");
                row.addAll(Arrays.asList(
                        num(d, "shortEvidenceConfidence"), num(d, "mediumEvidenceConfidence"),
                        num(d, "shortDownsideRisk"), num(d, "mediumDownsideRisk"),
                        num(d, "shortRiskCoverage"), num(d, "mediumRiskCoverage"),
                        txt(d, "candidateAction"), txt(d, "shortCandidateAction"),
                        evidenceDisclosureLines(d, evidence), txt(evidence, "nextDistributionDate"),
                        txt(evidence, "nextDistributionStatus"), txt(evidence, "nextDistributionKnownAt")));
                JsonNode fundamental = d.path("fundamental");
                JsonNode profile = evidence.path("assetProfile");
                JsonNode bondRate = evidenceComponent(evidence, "ASSET_SPECIFIC", "bond_rate");
                JsonNode treasuryRateContext = evidence.path("treasuryRateContext");
                row.addAll(detailEvidenceCells(fundamental, evidence, profile, bondRate, treasuryRateContext));
                row.addAll(valuationComponentCells(fundamental, evidence));
                // Task 320：與 headers／formats 同位置（皆為尾端附加）。舊快照缺這兩個 key 時
                // num()／nullableText() 都回 null → Excel BLANK 格、JSON null，不以 0 或 "" 充數（320.7）。
                row.addAll(Arrays.asList(
                        num(d, "etfPremiumLivePct"), nullableText(d, "etfPremiumLiveNavAsOf")));
                rows.add(row);
            }
        }
        // headers／formats／rows 三者長度與順序必須一致；ExportDoc 會在建構時 fail fast。
        List<ExportDoc.Format> formats = new ArrayList<>(List.of(
                ExportDoc.Format.TEXT,      // 0  快照時間
                ExportDoc.Format.TEXT,      // 1  代碼
                ExportDoc.Format.TEXT,      // 2  名稱
                ExportDoc.Format.TEXT,      // 3  市場
                ExportDoc.Format.TEXT,      // 4  資產類別
                ExportDoc.Format.BOOL_ZH,   // 5  持有
                ExportDoc.Format.BOOL_ZH,   // 6  還原權息
                ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.NUM2, // 短期
                ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.NUM2, // 中期
                ExportDoc.Format.BOOL_ZH, ExportDoc.Format.BOOL_ZH,
                ExportDoc.Format.TEXT, ExportDoc.Format.TEXT,
                ExportDoc.Format.NUM2, ExportDoc.Format.NUM2, ExportDoc.Format.TEXT,
                ExportDoc.Format.TEXT, ExportDoc.Format.TEXT,
                ExportDoc.Format.NUM2, ExportDoc.Format.NUM2, ExportDoc.Format.NUM2, ExportDoc.Format.NUM2,
                ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT,
                ExportDoc.Format.NUM2, ExportDoc.Format.NUM2
        ));
        for (int i = 0; i < EXT_HEADERS.size(); i++) formats.add(ExportDoc.Format.NUM2);
        formats.addAll(List.of(
                ExportDoc.Format.NUM2, ExportDoc.Format.NUM2, ExportDoc.Format.TEXT,
                ExportDoc.Format.TEXT, ExportDoc.Format.BOOL_ZH,
                ExportDoc.Format.TEXT, ExportDoc.Format.NUM2, ExportDoc.Format.NUM2, ExportDoc.Format.NUM2
        ));
        formats.addAll(FUNDAMENTAL_FORMATS);
        formats.addAll(List.of(
                ExportDoc.Format.LIST_LINES, ExportDoc.Format.LIST_LINES,
                ExportDoc.Format.LIST_LINES, ExportDoc.Format.LIST_LINES,
                ExportDoc.Format.LIST_LINES, ExportDoc.Format.LIST_LINES
        ));
        formats.addAll(EVIDENCE_FORMATS);
        formats.addAll(DETAIL_EVIDENCE_FORMATS);
        formats.addAll(VALUATION_COMPONENT_FORMATS);
        formats.addAll(LIVE_PREMIUM_FORMATS); // Task 320：與 headers／rows 同位置（尾端）
        return new ExportDoc.Sheet("個股決策",
                List.of(new ExportDoc.Table(null, null, headers, true, false, false, formats, rows)),
                headers.size());
    }

    private ExportDoc.Sheet publicInformationSheet(List<JsonNode> snapshots) {
        List<String> headers = List.of("快照時間", "地區", "發布時間", "來源", "標題", "摘要", "網址");
        List<List<Object>> rows = new ArrayList<>();
        for (JsonNode snapshot : snapshots) {
            JsonNode items = snapshot.path("publicInformation");
            if (!items.isArray()) continue;
            for (JsonNode item : items) {
                rows.add(List.of(
                        txt(snapshot, "generatedAt"), txt(item, "region"), txt(item, "publishedAt"),
                        txt(item, "source"), txt(item, "title"), txt(item, "summary"), txt(item, "url")));
            }
        }
        List<ExportDoc.Format> formats = List.of(
                ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT,
                ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT);
        return new ExportDoc.Sheet("台美公開資訊",
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
     * 巢狀子節點的數值欄（Task 281）：父節點缺漏或為 null 時回 {@code null}。
     *
     * <p>本功能上線前寫入的 Redis 快照沒有 {@code extendedIndicators}；{@code JsonNode.path()}
     * 對 MissingNode 與 NullNode 都回 MissingNode，故舊快照自然得到 {@code null}
     * → Excel 空白格、JSON {@code null}。<b>不得補 0、"-" 或空字串。</b></p>
     */
    private static BigDecimal num(JsonNode parent, String child, String field) {
        return num(parent.path(child), field);
    }

    /** Task 281 新增的 14 個擴充指標欄，順序即匯出欄序。 */
    private static final List<String> EXT_KEYS = List.of(
            "j9", "k3d2", "rsv", "ema12", "ema26", "dif", "macd", "osc",
            "rsi5", "rsi10", "bias10", "bias20", "b10b20", "wr9");

    private static final List<String> EXT_HEADERS = List.of(
            "J9", "K3D2", "RSV", "EMA12", "EMA26", "DIF", "MACD", "OSC",
            "RSI5", "RSI10", "BIAS10", "BIAS20", "BIAS10-BIAS20", "W%R9");

    /** V11 個股基本面／產業欄；每個衍生因子都單獨保留 provider、URL 與 as-of。 */
    private static final List<String> FUNDAMENTAL_HEADERS = List.of(
            "基本面適用", "基本面覆蓋(0-4)",
            "EPS TTM年增%", "EPS來源", "EPS來源網址", "EPS資料時點",
            "近似ROE%", "ROE來源", "ROE來源網址", "ROE資料時點",
            "近3月營收年增%", "營收來源", "營收來源網址", "營收資料時點",
            "PE值", "PE自身分位", "PE可信虧損", "估值來源", "估值來源網址", "估值資料時點",
            "產業", "產業營收年增%", "產業公司數", "產業資料年月", "產業來源", "產業來源網址", "產業資料時點",
            "public_info_*個股證據", "public_info_*產業證據");

    private static final List<ExportDoc.Format> FUNDAMENTAL_FORMATS = List.of(
            ExportDoc.Format.BOOL_ZH, ExportDoc.Format.NUM2,
            ExportDoc.Format.NUM2, ExportDoc.Format.TEXT, ExportDoc.Format.LIST_LINES, ExportDoc.Format.TEXT,
            ExportDoc.Format.NUM2, ExportDoc.Format.TEXT, ExportDoc.Format.LIST_LINES, ExportDoc.Format.TEXT,
            ExportDoc.Format.NUM2, ExportDoc.Format.TEXT, ExportDoc.Format.LIST_LINES, ExportDoc.Format.TEXT,
            ExportDoc.Format.NUM2, ExportDoc.Format.NUM2, ExportDoc.Format.BOOL_ZH, ExportDoc.Format.TEXT, ExportDoc.Format.LIST_LINES, ExportDoc.Format.TEXT,
            ExportDoc.Format.TEXT, ExportDoc.Format.NUM2, ExportDoc.Format.NUM2, ExportDoc.Format.TEXT,
            ExportDoc.Format.TEXT, ExportDoc.Format.LIST_LINES, ExportDoc.Format.TEXT,
            ExportDoc.Format.LIST_LINES, ExportDoc.Format.LIST_LINES);

    /** V13 evidence/confidence columns appended after legacy decision columns. */
    private static final List<String> EVIDENCE_HEADERS = List.of(
            "短期證據信心", "中期證據信心", "短期下檔風險", "中期下檔風險",
            "短期風險覆蓋", "中期風險覆蓋", "中期候選動作", "短期候選動作",
            "證據閘門原因", "下一配息日", "配息證據狀態", "配息已知時間");

    private static final List<ExportDoc.Format> EVIDENCE_FORMATS = List.of(
            ExportDoc.Format.NUM2, ExportDoc.Format.NUM2, ExportDoc.Format.NUM2, ExportDoc.Format.NUM2,
            ExportDoc.Format.NUM4, ExportDoc.Format.NUM4, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT,
            ExportDoc.Format.LIST_LINES, ExportDoc.Format.DATE, ExportDoc.Format.TEXT, ExportDoc.Format.TIMESTAMP);

    /**
     * V13 原始證據欄位：只攤平後端已解析的值，不在匯出端重算 composite、門檻或
     * profile。這些欄位刻意放在既有欄位之後，讓舊版欄位索引與既有檔案相容。
     */
    private static final List<String> DETAIL_EVIDENCE_HEADERS = List.of(
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
            "利率落後日數", "利率時效說明", "利率來源Manifest",
            // t309 per-field profile completeness is appended so old snapshot column indexes stay stable.
            "資產分類完整", "底層幣別完整");

    private static final List<ExportDoc.Format> DETAIL_EVIDENCE_FORMATS = List.of(
            ExportDoc.Format.NUM2, ExportDoc.Format.NUM2, ExportDoc.Format.NUM2, ExportDoc.Format.NUM2,
            ExportDoc.Format.NUM4, ExportDoc.Format.NUM2, ExportDoc.Format.TEXT, ExportDoc.Format.BOOL_ZH,
            ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.BOOL_ZH,
            ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.BOOL_ZH,
            ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.BOOL_ZH,
            ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.BOOL_ZH,
            ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.BOOL_ZH,
            ExportDoc.Format.BOOL_ZH, ExportDoc.Format.LIST_LINES,
            ExportDoc.Format.NUM4, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT,
            ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.BOOL_ZH,
            ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.BOOL_ZH,
            ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT,
            ExportDoc.Format.NUM0, ExportDoc.Format.BOOL_ZH, ExportDoc.Format.TEXT,
            ExportDoc.Format.NUM4, ExportDoc.Format.DATE, ExportDoc.Format.TEXT,
            ExportDoc.Format.TIMESTAMP, ExportDoc.Format.TEXT, ExportDoc.Format.TIMESTAMP,
            ExportDoc.Format.NUM0, ExportDoc.Format.TEXT, ExportDoc.Format.LIST_LINES,
            ExportDoc.Format.BOOL_ZH, ExportDoc.Format.BOOL_ZH);

    /**
     * t315：三個估值 component 的完整 provenance 固定追加在個股決策表尾端。
     * 欄名含 component prefix，因此 JSON object key 與 Excel header 一對一。
     */
    private static final List<String> VALUATION_COMPONENT_HEADERS = List.of(
            "PE適用狀態", "PE Provider", "PE來源網址", "PE可得時間", "PE資料日期", "PE缺漏原因",
            "PB適用狀態", "PB Provider", "PB來源網址", "PB可得時間", "PB資料日期", "PB缺漏原因",
            "殖利率適用狀態", "殖利率 Provider", "殖利率來源網址", "殖利率可得時間", "殖利率資料日期", "殖利率缺漏原因");

    private static final List<ExportDoc.Format> VALUATION_COMPONENT_FORMATS = List.of(
            ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.LIST_LINES,
            ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT,
            ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.LIST_LINES,
            ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT,
            ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.LIST_LINES,
            ExportDoc.Format.TEXT, ExportDoc.Format.TEXT, ExportDoc.Format.TEXT);

    /**
     * Task 320 的即時折溢價欄，附加在「個股決策」表的<b>真正最末</b>。
     *
     * <p>與索引 53 的「折溢價%」是兩個不同語意的欄，不可互相取代：後者取自 dated 的
     * {@code etfPremiumPct}（只認已完成交易日、進 buyGate、美股恆為空），本組取自
     * {@code etfPremiumLivePct}（與該列現價同一 tick 的即時值）。
     */
    private static final List<String> LIVE_PREMIUM_HEADERS = List.of("即時折溢價%", "即時淨值時間");

    private static final List<ExportDoc.Format> LIVE_PREMIUM_FORMATS =
            List.of(ExportDoc.Format.NUM2, ExportDoc.Format.TEXT);

    /** 依 {@link #EXT_KEYS} 順序取出 14 個值；缺欄位為 null。 */
    private static List<Object> extCells(JsonNode n) {
        List<Object> out = new ArrayList<>(EXT_KEYS.size());
        for (String k : EXT_KEYS) out.add(num(n, "extendedIndicators", k));
        return out;
    }

    private static List<Object> fundamentalCells(JsonNode f) {
        return Arrays.asList(
                boolVal(f, "applicable"), num(f, "coverage"),
                num(f, "epsYoyPct"), txt(f, "epsProvider"), listVal(f, "epsSourceUrls"), txt(f, "epsAsOf"),
                num(f, "approximateRoePct"), txt(f, "roeProvider"), listVal(f, "roeSourceUrls"), txt(f, "roeAsOf"),
                num(f, "revenueYoy3mPct"), txt(f, "revenueProvider"), listVal(f, "revenueSourceUrls"), txt(f, "revenueAsOf"),
                num(f, "peValue"), num(f, "pePercentile"), boolVal(f, "peLossFlag"), txt(f, "valuationProvider"),
                listVal(f, "valuationSourceUrls"), txt(f, "valuationAsOf"),
                txt(f, "industryName"), num(f, "industryRevenueYoyPct"), num(f, "industryCompanyCount"),
                txt(f, "industryPeriod"), txt(f, "industryProvider"), listVal(f, "industrySourceUrls"),
                txt(f, "industryAsOf"), evidenceVal(f, "companyPublicInformation"),
                evidenceVal(f, "industryPublicInformation"));
    }

    private static List<Object> detailEvidenceCells(
            JsonNode f, JsonNode evidence, JsonNode profile, JsonNode bondRate,
            JsonNode treasuryRateContext) {
        return Arrays.asList(
                num(f, "pbValue"), num(f, "dividendYieldPct"), num(f, "pbPercentile"),
                num(f, "dividendYieldPercentile"), num(f, "valuationContribution"),
                num(f, "valuationCoverage"), txt(f, "epsTrendType"), boolVal(f, "roeApproximationFallback"),
                txt(profile, "assetClassSource"), txt(profile, "instrumentKind"),
                txt(profile, "instrumentKindSource"), boolVal(profile, "instrumentKindComplete"),
                txt(profile, "stockStyle"), txt(profile, "stockStyleSource"),
                boolVal(profile, "stockStyleComplete"), txt(profile, "bondTerm"),
                txt(profile, "bondTermSource"), boolVal(profile, "bondTermComplete"),
                txt(profile, "quoteCurrency"), txt(profile, "quoteCurrencySource"),
                boolVal(profile, "quoteCurrencyComplete"), txt(profile, "underlyingCurrency"),
                txt(profile, "underlyingCurrencySource"), boolVal(profile, "currencyDataComplete"),
                boolVal(profile, "profileComplete"), listVal(profile, "missingReasons"),
                num(evidence, "returnStdDev60Ratio"), txt(evidence, "returnStdDev60AsOfDate"),
                txt(evidence, "returnStdDev60Source"), txt(evidence, "acceptedPriceAsOfDate"),
                txt(evidence, "acceptedPriceSource"), txt(evidence, "acceptedPriceQuality"),
                boolVal(evidence, "livePriceAccepted"), txt(evidence, "premiumAsOfDate"),
                txt(evidence, "premiumSource"), boolVal(evidence, "premiumStale"),
                txt(bondRate, "applicability"), txt(bondRate, "provider"),
                txt(bondRate, "missingReason"),
                num(treasuryRateContext, "batchId"), boolVal(treasuryRateContext, "complete"),
                txt(treasuryRateContext, "tenor"), num(treasuryRateContext, "value"),
                txt(treasuryRateContext, "curveDate"), txt(treasuryRateContext, "provider"),
                txt(treasuryRateContext, "availableAt"), txt(treasuryRateContext, "availabilityBasis"),
                txt(treasuryRateContext, "fetchedAt"), num(treasuryRateContext, "lagDays"),
                txt(treasuryRateContext, "staleReason"),
                mapVal(treasuryRateContext, "sourceManifest"),
                // These two fields are deliberately appended: pre-t309 snapshots have no
                // assetProfile node, and boolVal() must keep their Excel cells blank/JSON null.
                boolVal(profile, "assetClassComplete"), boolVal(profile, "underlyingCurrencyComplete"));
    }

    /**
     * 逐 component 合併後端已解析的兩個 projection：status/reason 只讀
     * VALUATION evidence group，provider/URL/availableAt/asOf 只讀各自
     * {@code *Evidence}。舊快照沒有這些節點時保留 null，不用 generic valuation 欄位代填。
     */
    private static List<Object> valuationComponentCells(JsonNode fundamental, JsonNode evidence) {
        List<Object> cells = new ArrayList<>(VALUATION_COMPONENT_HEADERS.size());
        appendValuationComponentCells(cells, fundamental.path("peEvidence"),
                evidenceComponent(evidence, "VALUATION", "pe"));
        appendValuationComponentCells(cells, fundamental.path("pbEvidence"),
                evidenceComponent(evidence, "VALUATION", "pb"));
        appendValuationComponentCells(cells, fundamental.path("dividendYieldEvidence"),
                evidenceComponent(evidence, "VALUATION", "dividend_yield"));
        return cells;
    }

    private static void appendValuationComponentCells(
            List<Object> cells, JsonNode componentEvidence, JsonNode applicability) {
        cells.add(nullableText(applicability, "applicability"));
        cells.add(nullableText(componentEvidence, "provider"));
        cells.add(listVal(componentEvidence, "sourceUrls"));
        cells.add(nullableText(componentEvidence, "availableAt"));
        cells.add(nullableText(componentEvidence, "asOf"));
        cells.add(nullableText(applicability, "missingReason"));
    }

    /**
     * Keep the existing Excel/JSON column contract while making every resolved
     * evidence group and typed market feature auditable.  The cells are an
     * ordered disclosure list (Excel newline / JSON array); no score or gate is
     * recomputed by the export layer.  Old snapshots without these nodes return
     * the original action-gate list unchanged.
     */
    private static List<String> evidenceDisclosureLines(JsonNode decision, JsonNode evidence) {
        List<String> lines = listVal(decision, "actionGateReasons");
        if (lines == null) lines = new ArrayList<>();
        else lines = new ArrayList<>(lines);
        JsonNode groups = evidence.path("evidenceGroups");
        if (groups.isObject()) {
            List<String> names = new ArrayList<>();
            groups.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            for (String name : names) {
                JsonNode group = groups.path(name);
                lines.add("EVIDENCE_GROUP " + name
                        + " shortCoverage=" + textOrDash(group, "shortCoverage")
                        + " mediumCoverage=" + textOrDash(group, "mediumCoverage")
                        + " shortFresh=" + textOrDash(group, "shortFresh")
                        + " mediumFresh=" + textOrDash(group, "mediumFresh")
                        + " participates=" + textOrDash(group, "participates"));
                JsonNode components = group.path("components");
                if (!components.isArray()) continue;
                for (JsonNode component : components) {
                    lines.add("EVIDENCE_COMPONENT " + name + "/" + textOrDash(component, "name")
                            + " status=" + textOrDash(component, "applicability")
                            + " provider=" + textOrDash(component, "provider")
                            + " asOf=" + textOrDash(component, "asOfDate")
                            + " reason=" + textOrDash(component, "missingReason"));
                }
            }
        }
        JsonNode features = evidence.path("marketFeatures");
        if (features.isObject()) {
            List<String> names = new ArrayList<>();
            features.fieldNames().forEachRemaining(names::add);
            names.sort(String::compareTo);
            for (String name : names) {
                JsonNode feature = features.path(name);
                lines.add("MARKET_FEATURE " + name
                        + " status=" + textOrDash(feature, "status")
                        + " value=" + textOrDash(feature, "value")
                        + " provider=" + textOrDash(feature, "provider")
                        + " asOf=" + textOrDash(feature, "asOfDate")
                        + " availableAt=" + textOrDash(feature, "availableAt")
                        + " basis=" + textOrDash(feature, "availabilityBasis")
                        + " reason=" + textOrDash(feature, "missingReason")
                        + " source=" + textOrDash(feature, "sourceUrl"));
            }
        }
        if (!evidence.path("nextDistributionStatus").isMissingNode()) {
            lines.add("DIVIDEND_EVENT status=" + textOrDash(evidence, "nextDistributionStatus")
                    + " provider=" + textOrDash(evidence, "nextDistributionProvider")
                    + " sourceUrls=" + textOrDash(evidence, "nextDistributionSourceUrls")
                    + " knownAt=" + textOrDash(evidence, "nextDistributionKnownAt")
                    + " within5=" + textOrDash(evidence, "distributionsWithinFiveSessions")
                    + " within20=" + textOrDash(evidence, "distributionsWithinTwentySessions")
                    + " reason=" + textOrDash(evidence, "nextDistributionMissingReason"));
        }
        return lines.isEmpty() ? null : List.copyOf(lines);
    }

    private static String textOrDash(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "—" : value.asText();
    }

    /** New t315 columns keep missing legacy text as JSON null / an empty Excel cell. */
    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static JsonNode evidenceComponent(JsonNode evidence, String group, String name) {
        JsonNode components = evidence.path("evidenceGroups").path(group).path("components");
        if (!components.isArray()) return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
        for (JsonNode component : components) {
            if (name.equals(component.path("name").asText(null))) return component;
        }
        return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    /** 公開資訊保留原文與網址，不從標題產生任何數值或情緒欄。 */
    private static List<String> evidenceVal(JsonNode parent, String field) {
        JsonNode items = parent.path(field);
        if (!items.isArray()) return null;
        List<String> out = new ArrayList<>();
        for (JsonNode item : items) {
            out.add(String.join(" ｜ ", txt(item, "publishedAt"), txt(item, "source"),
                    txt(item, "title"), txt(item, "url")));
        }
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

    /** Map 型 provenance 以排序後的「key ｜ value」清單輸出，JSON 保留陣列、Excel 換行顯示。 */
    private static List<String> mapVal(JsonNode n, String field) {
        JsonNode v = n.path(field);
        if (!v.isObject()) return null;
        List<String> keys = new ArrayList<>();
        v.fieldNames().forEachRemaining(keys::add);
        keys.sort(String::compareTo);
        List<String> out = new ArrayList<>(keys.size());
        for (String key : keys) out.add(key + " ｜ " + v.path(key).asText());
        return out;
    }




}
