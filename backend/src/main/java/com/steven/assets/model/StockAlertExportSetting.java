package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 警示觸發即時匯出 JSON 的 per-user 設定（Requirement 54 / Task 254）。
 *
 * <p><b>與其他九個匯出頁的設定表刻意不同：沒有 {@code run_hour} / {@code run_minute} /
 * {@code last_run_date}。</b>那九頁是「每天某個時刻跑一次」的排程，本表是<b>事件驅動</b>——匯出掛在
 * {@code StockAlertService.recordTrigger} / {@code recordGroupTrigger} 之後，由觸發事件驅動，
 * 沒有執行時刻可設、也不是一天跑一次。套排程頁的樣板會多出三個永遠沒人讀的欄位。
 *
 * <p>每個使用者一列（{@code owner_user_id} UNIQUE），以 {@code @Filter(ownerFilter)} 隔離設定本身
 * （{@code @FilterDef} 定義於 {@code model/package-info.java}）。HTTP 情境（BFF→business）由
 * {@code TenantFilterAspect} 自動 owner-scoped 到本人；觸發路徑走 Redis 訂閱者執行緒、無 request
 * context → filter 不啟用，故一律以明確的 {@code findByOwnerUserId(ownerId)} 取件，不依賴 filter。
 *
 * <p><b>{@code lastRunStatus} 的語意是「最後一次觸發匯出」而非「今日排程結果」</b>——一天可能寫入多次。
 * Drive 狀態欄與本機狀態欄<b>分離</b>：「本機成功、Drive 失敗」是正常且必須可分辨的狀態，若共用一欄，
 * 本機明明寫成功卻顯示失敗，使用者會誤以為本機檔案沒產生而去做不必要的排查。
 *
 * <p>四個時間欄型別為 {@link LocalDateTime}，與既有<b>八支 owner-scoped</b> 匯出設定 entity 一致。
 * 第九支 {@code CrawlerExportSetting} 用 {@code Instant}——那是無 owner 的全域設定表且有 business
 * （Hibernate）與 ext（JdbcTemplate）兩個寫入端，型別選擇的前提與本表不同，不要照它。
 */
@Entity
@Table(name = "stock_alert_export_setting", uniqueConstraints = @UniqueConstraint(
        name = "uq_stock_alert_export_owner", columnNames = {"owner_user_id"}))
@Filter(name = "ownerFilter", condition = "owner_user_id = :ownerId")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StockAlertExportSetting {

    private static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 擁有者（Requirement 28） */
    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    /**
     * 是否啟用觸發即時匯出。
     *
     * <p>false（預設）時<b>觸發路徑完全不做任何事</b>——不查詢、不寫檔、不碰 Drive、不寫狀態欄。
     * 唯一例外是使用者主動按下的「立即匯出」（run-now），它是驗證工具、不看本欄。
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = false;

    /** 輸出相對子路徑（相對容器基底目錄 {@code EXPORT_OUTPUT_DIR}），預設 input */
    @Column(name = "output_subpath", nullable = false, length = 512)
    @Builder.Default
    private String outputSubpath = "input";

    /** 最後一次匯出時間（台北牆鐘）。<b>「最後一次」而非「今天那一次」</b>——一天可能寫入多次。 */
    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    /** 最後一次本機匯出結果 */
    @Column(name = "last_run_status", length = 512)
    private String lastRunStatus;

    /** 是否額外同步一份到 Google Drive（本機一律照寫，不受此開關影響）；限主要管理者啟用 */
    @Column(name = "gdrive_enabled", nullable = false)
    @Builder.Default
    private boolean gdriveEnabled = false;

    /** Drive 上的相對子路徑 */
    @Column(name = "gdrive_subpath", length = 512)
    private String gdriveSubpath;

    /**
     * 最後一次 Drive <b>上傳</b>的時間。
     *
     * <p><b>本輪被去抖合併時這一欄不得被碰</b>（連同 {@link #gdriveLastStatus}）：寫進去會覆蓋掉前一次
     * 真正成功的落點，並把時間寫成一個根本沒發生過上傳的時刻。
     */
    @Column(name = "gdrive_last_run_at")
    private LocalDateTime gdriveLastRunAt;

    /** 最後一次 Drive 上傳結果（成功／逾時／失敗／跳過），由 {@code GdriveOutputSupport} 產生並截斷至 512 字元 */
    @Column(name = "gdrive_last_status", length = 512)
    private String gdriveLastStatus;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now(TW_ZONE);

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now(TW_ZONE);
}
