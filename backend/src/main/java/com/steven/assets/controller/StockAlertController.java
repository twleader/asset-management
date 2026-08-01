package com.steven.assets.controller;

import com.steven.assets.dto.NotificationRecipientDto;
import com.steven.assets.dto.StockAlertDto;
import com.steven.assets.dto.StockAlertExportDto;
import com.steven.assets.model.StockAlertExportSetting;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.security.UnauthenticatedException;
import com.steven.assets.service.StockAlertService;
import com.steven.assets.service.StockAlertTriggerExportService;
import com.steven.assets.service.StockMasterService;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/stock-alerts")
@RequiredArgsConstructor
@Validated
public class StockAlertController {

    // 資安（Requirement 29）：lookup-name 的 code/market 會流入外部行情 client；白名單格式驗證（規則同 MarketDataController）
    private static final String CODE_PATTERN = "^[A-Za-z0-9.\\-]{1,12}$";
    private static final String MARKET_PATTERN = "^[\\p{L}0-9]{1,10}$";

    private final StockAlertService service;
    private final StockMasterService stockMasterService;
    /** 觸發即時匯出設定與 run-now（Requirement 54 / Task 254）。 */
    private final StockAlertTriggerExportService exportService;
    private final ObjectProvider<CurrentUserContext> currentUserProvider;

    @GetMapping
    public List<StockAlertDto.Response> findAll() {
        return service.findAll();
    }

    /** 警示對話框「通知對象」可挑選的收件人清單（Task 125）；委派 NotificationRecipientService，與通知設定頁同源。 */
    @GetMapping("/recipients")
    public List<NotificationRecipientDto.Response> recipients() {
        return service.listRecipients();
    }

    @PostMapping
    public StockAlertDto.Response create(@RequestBody StockAlertDto.Request req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    public StockAlertDto.Response update(@PathVariable Long id, @RequestBody StockAlertDto.Request req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/active")
    public StockAlertDto.Response toggleActive(@PathVariable Long id) {
        return service.toggleActive(id);
    }

    /**
     * 建立複合條件群組（Task 253）：2～5 個條件在同一次檢查中同時成立才觸發（單層 AND）。
     * 條件筆數、型別合法性、群組內／群組間重複皆由 service 驗證，違反時丟 IllegalArgumentException
     * → {@code GlobalExceptionHandler} 轉 400 ProblemDetail。
     */
    @PostMapping("/groups")
    public StockAlertDto.Response createGroup(@RequestBody StockAlertDto.GroupRequest req) {
        return service.createGroup(req);
    }

    /** 更新複合條件群組；成員為整組覆寫（先刪後建），詳見 {@link StockAlertService#updateGroup}。 */
    @PutMapping("/groups/{id}")
    public StockAlertDto.Response updateGroup(@PathVariable Long id, @RequestBody StockAlertDto.GroupRequest req) {
        return service.updateGroup(id, req);
    }

    /** 刪除複合條件群組（連帶刪除其成員與收件人 join）。 */
    @DeleteMapping("/groups/{id}")
    public ResponseEntity<Void> deleteGroup(@PathVariable Long id) {
        service.deleteGroup(id);
        return ResponseEntity.noContent().build();
    }

    /** 群組啟停：只翻轉群組那一列，成員的 active 恆為 true、不跟著動。 */
    @PatchMapping("/groups/{id}/active")
    public StockAlertDto.Response toggleGroupActive(@PathVariable Long id) {
        return service.toggleGroupActive(id);
    }

    /**
     * 更新排列順序，body 為排序後的 {@code {kind, id}} 陣列。
     *
     * <p>Task 253 起獨立條件與群組共用同一個排序空間，但兩者 id 分屬 {@code stock_alert} /
     * {@code stock_alert_group} 兩張表、值必然重疊，所以不能只送 id 陣列，必須以 {@code kind}
     * （{@code "SINGLE"} / {@code "GROUP"}）指明要更新哪一張表。
     */
    @PutMapping("/reorder")
    public ResponseEntity<Void> reorder(@RequestBody List<StockAlertDto.OrderItem> orderedItems) {
        service.reorder(orderedItems);
        return ResponseEntity.noContent().build();
    }

    /** 手動觸發一次到價警示檢查 */
    @PostMapping("/check")
    public ResponseEntity<Void> triggerCheck() {
        service.checkAlerts();
        return ResponseEntity.noContent().build();
    }

    /**
     * 查詢股票名稱：本地 stock 主檔優先 → 查無時打外部 API（台股→FinMind，美股/英股→Yahoo）
     * → 查到後寫入 stock 主檔供下次使用。實作見 {@link StockMasterService#resolveName}。
     * 資安（Requirement 29）：code/market 會流入外部 client，於 web 層先過 {@code @Pattern} 白名單再委派。
     */
    @GetMapping("/lookup-name")
    public ResponseEntity<Map<String, String>> lookupName(
            @RequestParam @Pattern(regexp = CODE_PATTERN, message = "股票代號格式不合法") String code,
            @RequestParam @Pattern(regexp = MARKET_PATTERN, message = "市場別格式不合法") String market) {
        return ResponseEntity.ok(Map.of("stockName", stockMasterService.resolveName(code, market)));
    }

    /**
     * 反向查找：依股名查股票代號（只查本地 stock 主檔，精確匹配）。實作見 {@link StockMasterService#resolveCode}。
     *  - 「台股大盤」＋台股特例：直接回 0000
     *  - 主檔找不到時回空字串，由前端提示使用者改輸入代號
     */
    @GetMapping("/lookup-code")
    public ResponseEntity<Map<String, String>> lookupCode(
            @RequestParam String name,
            @RequestParam String market) {
        return ResponseEntity.ok(Map.of("stockCode", stockMasterService.resolveCode(name, market)));
    }

    // ===== 觸發即時匯出設定（Requirement 54 / Task 254）=====

    /** 取當前使用者的觸發匯出設定；查無時回預設值（不寫入 DB）。 */
    @GetMapping("/export-setting")
    public StockAlertExportDto.SettingResponse getExportSetting() {
        return toResponse(exportService.getOrDefault(requireOwnerId()), null);
    }

    /**
     * upsert 觸發匯出設定。
     *
     * <p>Drive 開關要求設為 true 而當前使用者不是主要管理者時回 <b>403</b>（{@code AdminRequiredException}
     * → {@code GlobalExceptionHandler}）；子路徑不合法回 400。本機輸出路徑與啟用開關則所有使用者皆可設定
     * ——只有 Drive 那一項會把資料送出本機，此不對稱是刻意的。
     */
    @PutMapping("/export-setting")
    public StockAlertExportDto.SettingResponse updateExportSetting(
            @RequestBody StockAlertExportDto.SettingRequest req) {
        Long ownerId = requireOwnerId();
        String warning = exportService.update(ownerId, req.enabled(), req.outputSubpath(),
                req.gdriveEnabled(), req.gdriveSubpath());
        return toResponse(exportService.getOrDefault(ownerId), warning);
    }

    /**
     * 立即把<b>視窗內（近 3 天）已發生的觸發</b>產檔（驗證落點用）。
     *
     * <p><b>不看 {@code enabled}</b>——設定尚未啟用時使用者同樣需要確認落點正確。視窗內尚無任何觸發時
     * 仍寫出 {@code triggers: []} 的合法 JSON，不回 404、不靜默不產檔。
     */
    @PostMapping("/export-setting/run-now")
    public StockAlertExportDto.RunNowResponse runNowExport() {
        Long ownerId = requireOwnerId();
        try {
            StockAlertTriggerExportService.ExportResult r = exportService.runNow(ownerId);
            String msg = r.triggerCount() == 0
                    ? "近 " + exportService.windowDays() + " 天尚無觸發，已寫出空的觸發清單（可用於驗證落點）"
                    : "已匯出近 " + exportService.windowDays() + " 天共 " + r.triggerCount() + " 筆觸發";
            return new StockAlertExportDto.RunNowResponse(
                    // 既有欄位維持指向 .json（本頁的對外契約）；xlsx 那份走新增的三欄
                    r.file() == null ? null : r.file().toString(), r.sizeBytes(), r.triggerCount(), msg,
                    r.gdrivePath(), r.gdriveStatus(),
                    r.xlsxFile() == null ? null : r.xlsxFile().toString(),
                    r.xlsxSizeBytes(), r.xlsxGdrivePath());
        } catch (IOException e) {
            throw new RuntimeException("立即匯出失敗：" + e.getMessage(), e);
        }
    }

    private Long requireOwnerId() {
        CurrentUserContext ctx = currentUserProvider.getObject();
        if (!ctx.hasUser()) {
            throw new UnauthenticatedException("未識別使用者，無法存取觸發匯出設定");
        }
        return ctx.getEffectiveUserId();
    }

    /**
     * @param selfCheckWarning 本次啟用 Drive 時的自檢警告（Requirement 52）；正常時為 null。
     *                         <b>不入庫</b>——尤其不得寫進 {@code gdriveLastStatus}，那一欄是「上次上傳」。
     */
    private StockAlertExportDto.SettingResponse toResponse(StockAlertExportSetting s, String selfCheckWarning) {
        return new StockAlertExportDto.SettingResponse(
                s.isEnabled(),
                s.getOutputSubpath(),
                exportService.baseDir(),
                exportService.resolvedDirDisplay(s.getOutputSubpath()),
                StockAlertTriggerExportService.FILENAME_PATTERN,
                StockAlertTriggerExportService.fmt(s.getLastRunAt()),
                s.getLastRunStatus(),
                // 讀取一律不驗證 Drive 子路徑：DB 值可能被繞過 API 直改，若讀取也擲例外，設定頁會 500
                // 而使用者沒有任何入口能把它改回正常值——唯一的修正入口被自己鎖死。存檔時才驗。
                s.isGdriveEnabled(),
                s.getGdriveSubpath(),
                exportService.gdriveRemoteName(),
                StockAlertTriggerExportService.fmt(s.getGdriveLastRunAt()),
                s.getGdriveLastStatus(),
                selfCheckWarning);
    }
}
