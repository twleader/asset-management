package com.steven.assets.controller;

import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.dto.TradingRadarExportDto;
import com.steven.assets.dto.TradingRadarNotificationDto;
import com.steven.assets.service.TradingRadarExportScheduleService;
import com.steven.assets.service.TradingRadarNotificationSettingService;
import com.steven.assets.service.TradingRadarRefreshService;
import com.steven.assets.service.TradingRadarService;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 今日交易雷達與逐檔 Email 通知設定（Requirement 43／44）business API。 */
@RestController
@RequestMapping("/api/trading-radar")
@RequiredArgsConstructor
@Validated
public class TradingRadarController {

    private static final String CODE_PATTERN = "^[A-Za-z0-9.\\-]{1,12}$";
    private static final String MARKET_PATTERN = "^[\\p{L}0-9]{1,10}$";

    private final TradingRadarService service;
    private final TradingRadarNotificationSettingService notificationSettingService;
    private final TradingRadarExportScheduleService exportScheduleService;
    private final TradingRadarRefreshService refreshService;

    @GetMapping
    public TradingRadarDto.Response get() {
        return service.get();
    }

    /** 公開 configured-admin 邊界專用的純讀入口；不保存交易雷達匯出快照。 */
    @GetMapping("/current")
    public TradingRadarDto.Response getCurrent() {
        return service.getCurrent();
    }

    /**
     * 手動「重新整理」：先同步回補台股行情再重算（Task 249）。
     *
     * <p>只有使用者按下按鈕會走這裡；SSE 盤中自動更新仍走上方的 {@link #get()}，
     * 否則每個 tick 都會觸發一次外部抓取而形成自我餵食迴圈。
     * 回補逾時／失敗一律降級成 {@code priceRefresh.outcome}，不回 5xx。</p>
     */
    @PostMapping("/refresh")
    public TradingRadarDto.RefreshResponse refresh() {
        return refreshService.refreshAndGet();
    }

    /**
     * 交易雷達結果快照的 Excel 區間匯出（Requirement 48）。
     * from/to 為 Asia/Taipei 的 ISO local datetime（如 2026-07-20T00:00:00）；
     * 格式錯誤或 from>to 由 service 拋 IllegalArgumentException，經 GlobalExceptionHandler 轉 400。
     */
    @PostMapping("/export")
    public ResponseEntity<ByteArrayResource> export(
            @RequestParam String from, @RequestParam String to) throws IOException {
        // Task 283：改 POST——本端點自此有副作用（落檔、Drive 上傳），不得掛在 GET。
        // 下載那一份與落檔的兩份出自同一次查詢（Requirement 55），故不得拆成兩支端點。
        var result = exportScheduleService.exportAndWriteManual(from, to);
        byte[] data = result.xlsx();
        String filename = "交易雷達_" + compact(from) + "_" + compact(to) + ".xlsx";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition
                .attachment().filename(filename, StandardCharsets.UTF_8).build());
        // ASCII-safe：ok／failed／skipped，供前端顯示誠實訊息（標頭放中文路徑需另外編碼）
        headers.set("X-Dir-Export", result.dirOutcome());
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .contentLength(data.length)
                .body(new ByteArrayResource(data));
    }

    /** 把 ISO datetime 壓成檔名用的 YYYYMMDDHHmm（取數字前 12 碼；純日期則 8 碼）。 */
    private static String compact(String iso) {
        String digits = iso.replaceAll("[^0-9]", "");
        return digits.substring(0, Math.min(12, digits.length()));
    }

    // ===== 排程自動匯出到指定伺服器目錄（Requirement 48 追加 / Task 231）=====

    /** 目前使用者的排程執行時間點清單。 */
    @GetMapping("/export-schedule/times")
    public List<TradingRadarExportDto.TimeItem> listExportTimes() {
        return exportScheduleService.listTimes();
    }

    /** 整批覆寫執行時間點；時分不合法或重複時分丟 IllegalArgumentException → 400。 */
    @PutMapping("/export-schedule/times")
    public List<TradingRadarExportDto.TimeItem> replaceExportTimes(
            @RequestBody TradingRadarExportDto.TimesRequest request) {
        return exportScheduleService.replaceTimes(request == null ? List.of() : request.times());
    }

    /** 輸出資料夾設定與上次執行狀態。 */
    @GetMapping("/export-schedule/setting")
    public TradingRadarExportDto.SettingResponse getExportSetting() {
        return exportScheduleService.getSetting();
    }

    /**
     * 儲存輸出資料夾（相對子路徑）與 Drive 同步設定；跳脫基底目錄丟 IllegalArgumentException → 400，
     * 非主要管理者要啟用 Drive 丟 AdminRequiredException → 403。
     *
     * <p><b>傳整個 request 而非拆出單一欄位</b>：原本是 {@code request.outputSubpath()} 裸傳字串，
     * 那樣新增的 Drive 兩欄會在此靜默消失（Task 244.1）。
     */
    @PutMapping("/export-schedule/setting")
    public TradingRadarExportDto.SettingResponse saveExportSetting(
            @RequestBody TradingRadarExportDto.SettingRequest request) {
        return exportScheduleService.saveSetting(request);
    }

    /** 立即匯出到設定目錄（驗證用）；不動任何時間點的當日 guard。 */
    @PostMapping("/export-schedule/run-now")
    public TradingRadarExportDto.RunNowResponse runExportNow() {
        return exportScheduleService.runNow();
    }

    @GetMapping("/notifications/{stockCode}")
    public TradingRadarNotificationDto.Response getNotification(
            @PathVariable @Pattern(regexp = CODE_PATTERN, message = "股票代號格式不合法") String stockCode,
            @RequestParam @Pattern(regexp = MARKET_PATTERN, message = "市場別格式不合法") String market) {
        return notificationSettingService.get(stockCode, market);
    }

    @PutMapping("/notifications/{stockCode}")
    public TradingRadarNotificationDto.Response saveNotification(
            @PathVariable @Pattern(regexp = CODE_PATTERN, message = "股票代號格式不合法") String stockCode,
            @RequestParam @Pattern(regexp = MARKET_PATTERN, message = "市場別格式不合法") String market,
            @RequestBody TradingRadarNotificationDto.Request request) {
        return notificationSettingService.save(stockCode, market, request);
    }
}
