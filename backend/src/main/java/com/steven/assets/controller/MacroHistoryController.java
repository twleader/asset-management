package com.steven.assets.controller;

import com.steven.assets.model.JapanGdpPerCapitaHistory;
import com.steven.assets.model.KoreaGdpPerCapitaHistory;
import com.steven.assets.model.TaiwanGdpPerCapitaHistory;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.model.UsIndexDailyHistory;
import com.steven.assets.service.MacroHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 股市分析頁（Requirement 18）資料來源：台/日/韓人均 GDP、台股大盤日線、海外指數日線、指數當日分時。
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MacroHistoryController {

    /** 海外指數合法代碼（當日分時 refresh 守門）；單一清單由 {@link MacroHistoryService#OVERSEAS_INDEX_CODES} 提供（與自動回補排程共用）。 */
    private static final Set<String> US_INDEX_CODES = Set.copyOf(MacroHistoryService.OVERSEAS_INDEX_CODES);

    /**
     * 日線回補 refresh 守門集合：純價格海外指數 ∪ 含息報酬指數（SP500TR）。
     * 讓績效比較頁（Requirement 33）的 SP500TR 也能經 /us-daily-index/refresh 觸發 Yahoo ^SP500TR 回補。
     */
    private static final Set<String> US_INDEX_REFRESH_CODES = java.util.stream.Stream
            .concat(MacroHistoryService.OVERSEAS_INDEX_CODES.stream(),
                    MacroHistoryService.TOTAL_RETURN_US_INDEX_CODES.stream())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    private final MacroHistoryService macroHistoryService;
    /** 指數日線 Excel 匯出（Requirement 43 / Task 209）；與排程匯出走同一支產檔方法，確保兩途徑內容一致。 */
    private final com.steven.assets.service.ExcelExportService excelExportService;

    @GetMapping("/taiwan-gdp")
    public List<TaiwanGdpPerCapitaHistory> getTaiwanGdp(
            @RequestParam(required = false) Integer since) {
        return macroHistoryService.getTaiwanGdp(since);
    }

    @PostMapping("/taiwan-gdp/refresh-from-imf")
    public Map<String, Object> refreshGdpFromImf() throws Exception {
        return macroHistoryService.refreshGdpFromImf();
    }

    @GetMapping("/japan-gdp")
    public List<JapanGdpPerCapitaHistory> getJapanGdp(
            @RequestParam(required = false) Integer since) {
        return macroHistoryService.getJapanGdp(since);
    }

    @PostMapping("/japan-gdp/refresh-from-imf")
    public Map<String, Object> refreshJapanGdpFromImf() throws Exception {
        return macroHistoryService.refreshJapanGdpFromImf();
    }

    @GetMapping("/korea-gdp")
    public List<KoreaGdpPerCapitaHistory> getKoreaGdp(
            @RequestParam(required = false) Integer since) {
        return macroHistoryService.getKoreaGdp(since);
    }

    @PostMapping("/korea-gdp/refresh-from-imf")
    public Map<String, Object> refreshKoreaGdpFromImf() throws Exception {
        return macroHistoryService.refreshKoreaGdpFromImf();
    }

    @GetMapping("/twse-daily-index")
    public List<TwseIndexDailyHistory> getTwseDaily(
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        return macroHistoryService.getTwseDaily(from, to);
    }

    @PostMapping("/twse-daily-index/refresh")
    public Map<String, Object> refreshTwseDaily(
            @RequestParam(defaultValue = "10") int years) {
        return macroHistoryService.refreshTwseDaily(years);
    }

    /** 海外指數每日 OHLC（Requirement 18：日線圖市場切換）。code ∈ {DJI,SPX,IXIC,SOX,FTSE,DAX,KOSPI,N225}。 */
    @GetMapping("/us-daily-index")
    public List<UsIndexDailyHistory> getUsDaily(
            @RequestParam String code,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        return macroHistoryService.getUsDaily(code, from, to);
    }

    @PostMapping("/us-daily-index/refresh")
    public Map<String, Object> refreshUsDaily(@RequestParam String code) {
        if (!US_INDEX_REFRESH_CODES.contains(code)) {
            return Map.of("error", "未知指數代碼: " + code, "upserted", 0);
        }
        return macroHistoryService.refreshUsIndexDaily(code);
    }

    /**
     * TWSE 發行量加權股價「報酬指數」（含息）近 N 年背景回補（績效比較頁 Requirement 33）。
     * 立即回 {"started":true,"pending":&lt;n&gt;}，實際抓取在背景執行。
     * POST /api/twse-daily-index/refresh-tr?years=10
     */
    @PostMapping("/twse-daily-index/refresh-tr")
    public Map<String, Object> refreshTwseReturnIndex(
            @RequestParam(defaultValue = "10") int years) {
        return macroHistoryService.refreshTwseReturnIndex(years);
    }

    /**
     * 指數日線區間匯出成單一 .xlsx（日期／開盤／最高／最低／收盤五欄）（Requirement 43 / Task 209）。
     * GET /api/index-daily/export?market=TWSE&start=2020-01-01&end=2026-07-18
     * 預設回近 10 年。全域公開行情，無 owner 過濾。
     *
     * <p>白名單用 {@link MacroHistoryService#DAILY_INDEX_CODES}（＝頁面下拉的 9 個指數），
     * <b>不可</b>誤用 {@link #US_INDEX_REFRESH_CODES}——那含績效比較頁的 SP500TR，不在本頁可選範圍。
     * 資安（Requirement 29）：market 會流入查詢與產出檔名，未知代碼直接擋為 400。
     */
    @GetMapping("/index-daily/export")
    public ResponseEntity<org.springframework.core.io.ByteArrayResource> exportIndexDaily(
            @RequestParam(defaultValue = "TWSE") String market,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end)
            throws java.io.IOException {
        String code = market == null ? "" : market.trim().toUpperCase();
        if (!MacroHistoryService.DAILY_INDEX_CODES.contains(code)) {
            throw new IllegalArgumentException("未知指數代碼: " + market);
        }
        if (start == null) start = LocalDate.now().minusYears(10);
        if (end == null) end = LocalDate.now();
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("起始日不可晚於結束日");
        }
        byte[] data = excelExportService.exportIndexDaily(code, start, end);
        java.time.format.DateTimeFormatter fileFmt = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd");
        String filename = com.steven.assets.service.ExcelExportService.indexLabel(code)
                + "_" + fileFmt.format(start) + "_" + fileFmt.format(end) + ".xlsx";
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentDisposition(org.springframework.http.ContentDisposition
                .attachment().filename(filename, java.nio.charset.StandardCharsets.UTF_8).build());
        return ResponseEntity.ok().headers(headers)
                .contentType(org.springframework.http.MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(data.length)
                .body(new org.springframework.core.io.ByteArrayResource(data));
    }

    /** 指數「當日」分時走勢（Yahoo 5m，最新交易日；transient）。market ∈ {TWSE,DJI,SPX,IXIC,SOX,FTSE,DAX,KOSPI,N225}。 */
    @GetMapping("/index-intraday")
    public List<MacroHistoryService.IntradayPoint> getIndexIntraday(@RequestParam String market) {
        // 資安（Requirement 29）：market 會流入 external-materials-service 打 Yahoo；以已知指數白名單擋參數注入
        if (!"TWSE".equals(market) && !US_INDEX_CODES.contains(market)) {
            throw new IllegalArgumentException("未知指數代碼: " + market);
        }
        return macroHistoryService.fetchIndexIntraday(market);
    }
}
