package com.steven.assets.dto;

import lombok.Builder;

import java.util.List;

/**
 * 資產交易紀錄每日排程自動匯出設定 DTO（Requirement 49 / Task 238；Task 255 起每人多筆）。
 *
 * <p>資料夾瀏覽沿用 Requirement 34 既有的 {@code GET /api/export-schedule/browse}
 * （語意相同＝列出基底家目錄下子目錄），故本 DTO 不重複定義 Browse／DirEntry。
 */
public class AssetTransactionExportDto {

    /**
     * 清單回應。<b>所有變更端點（POST／PUT／DELETE）都回「變更後的完整清單」</b>，
     * 前端不做客戶端合併——少一次來回，也不會出現「本地狀態與 DB 分歧」的中間態。
     */
    @Builder
    public record SchedulesResponse(
            String baseDir,          // 容器內基底目錄（顯示落點用的衍生值，不入庫）
            String gdriveRemote,     // rclone remote 名稱（衍生顯示值，不入庫）
            List<Item> schedules,
            // 只有「本次請求把某筆的 Drive 開關從 false 翻成 true」且本地自檢發現問題時才有值，其餘一律 null。
            // 不入庫，尤其不得寫進 gdriveLastStatus——那一欄語意是「上次上傳」，
            // 寫進去會永久覆蓋昨晚真正上傳成功的落點與大小（Task 247.3.4）。
            String gdriveSelfCheckWarning
    ) {}

    /** 單筆排程。 */
    @Builder
    public record Item(
            Long id,
            String name,             // 選填，可為 null；非空時進檔名
            boolean enabled,
            Integer runHour,
            Integer runMinute,
            String outputSubpath,
            String lastRunAt,        // yyyy-MM-dd HH:mm:ss，無則 null
            String lastRunStatus,    // 「成功：/path」或「失敗：訊息」
            boolean gdriveEnabled,
            String gdriveSubpath,
            String gdriveLastRunAt,  // yyyy-MM-dd HH:mm:ss，無則 null
            String gdriveLastStatus  // 「成功：…」「逾時（…）：…」「失敗：…」「跳過：…」
    ) {}

    /**
     * 新增／修改共用，<b>全量取代語意</b>（前端一律送出整筆設定）：
     * {@code name}／{@code enabled}／{@code runHour}／{@code runMinute}／{@code outputSubpath}
     * 送 null ＝套用預設值或清空（name → null、enabled → false、8 時 0 分、outputSubpath → "input"）。
     *
     * <p><b>只有 {@code gdriveEnabled}／{@code gdriveSubpath} 兩欄</b> null ＝未送出＝不變更
     * （Task 243.1.1：把已開啟的 Drive 開關靜默關掉，使用者會以為還在同步）。兩套語意刻意不同、
     * 不得統一：name 若當「未送出即保留」，前端就無從表達「清空名稱」；Drive 若當「未送出即 false」
     * 則會靜默關閉同步。
     */
    public record SettingRequest(
            String name,
            Boolean enabled,
            Integer runHour,
            Integer runMinute,
            String outputSubpath,
            Boolean gdriveEnabled,
            String gdriveSubpath
    ) {}

    @Builder
    public record RunNowResponse(
            String path,
            long sizeBytes,
            // run-now 的用途就是驗證落點正確，故 Drive 啟用時它也上傳並回報落點與狀態（Task 243.3.3）。
            String gdrivePath,    // 實際 Drive 落點（remote:subpath/檔名）；未啟用或未上傳成功為 null
            String gdriveStatus,  // 同 gdriveLastStatus 的措辭；未啟用為 null
            // 雙格式匯出新增（Requirement 55 / Task 270）：json 那一份的落點，
            // 主檔名與 xlsx 相同、只差副檔名；既有三欄語意不變（一律指 xlsx）
            String jsonPath,
            long jsonSizeBytes,
            String jsonGdrivePath
    ) {}
}
