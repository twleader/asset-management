package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 股市大盤指數日線每日排程自動匯出設定（Requirement 45 / Task 216）。
 *
 * <p>每個使用者一列（{@code owner_user_id} UNIQUE），以 {@code @Filter(ownerFilter)} 隔離設定本身。
 * HTTP 情境（BFF→business）由 {@link com.steven.assets.security.TenantFilterAspect} 自動 owner-scoped；
 * 背景排程無 request context → filter 不啟用，{@code findAll()} 讀全部列供逐列產檔。
 *
 * <p><b>與 {@link ExchangeRateExportSchedule} 的關鍵差異：多一個 {@code market} 欄。</b>
 * 匯率頁是單一幣別頁，刻意不設 {@code currency} 欄（加欄＝為不存在的多幣別頁預留未使用欄位）；
 * 本頁的指數下拉<b>本來就有 9 個選項</b>，「排程要匯出哪一個指數」是使用者當下的實際選擇，故必須存。
 *
 * <p>被匯出的兩張日線表（{@link TwseIndexDailyHistory}／{@link UsIndexDailyHistory}）是<b>全域公開行情</b>
 * （無 {@code owner_user_id}、未套 {@code @Filter}），故背景產檔<b>不需要</b>手動 {@code enableFilter}
 * ——隔離的只有「設定」，資料本身人人相同（同 Requirement 41／42）。
 */
@Entity
@Table(name = "index_export_schedule", uniqueConstraints = @UniqueConstraint(
        name = "uq_index_export_schedule_owner", columnNames = {"owner_user_id"}))
@Filter(name = "ownerFilter", condition = "owner_user_id = :ownerId")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IndexExportSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 擁有者（Requirement 28） */
    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    /** 是否啟用每日排程 */
    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = Boolean.FALSE;

    /** 每日執行時（0..23） */
    @Column(name = "run_hour", nullable = false)
    @Builder.Default
    private Integer runHour = 8;

    /** 每日執行分（0..59） */
    @Column(name = "run_minute", nullable = false)
    @Builder.Default
    private Integer runMinute = 0;

    /**
     * 匯出的指數代碼（TWSE 或 {@code MacroHistoryService.OVERSEAS_INDEX_CODES} 之一）。
     * 合法值清單不寫成 DB CHECK：單一來源在 Java 端，寫進 DDL 會變第二份清單而漂移。
     */
    @Column(name = "market", nullable = false, length = 16)
    @Builder.Default
    private String market = "TWSE";

    /** 輸出相對子路徑（相對容器基底目錄 EXPORT_OUTPUT_DIR），預設 input */
    @Column(name = "output_subpath", nullable = false, length = 255)
    @Builder.Default
    private String outputSubpath = "input";

    /**
     * 匯出範圍月數；{@code null} ＝ 全部十年。
     * 每次執行以「執行當日往前推 N 個月」計算起訖，使留存檔隨時間滾動而非固定區間。
     */
    @Column(name = "range_months")
    private Integer rangeMonths;

    /** 當日已執行的日期（成功或失敗都設，避免同分鐘每 poll 重跑） */
    @Column(name = "last_run_date")
    private LocalDate lastRunDate;

    /** 上次執行時間 */
    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    /** 上次執行結果（「成功：/path」或「失敗：訊息」） */
    @Column(name = "last_run_status", length = 500)
    private String lastRunStatus;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
