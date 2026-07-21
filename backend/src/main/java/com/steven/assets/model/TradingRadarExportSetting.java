package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Filter;

import java.time.LocalDateTime;

/**
 * 交易雷達排程匯出的輸出資料夾與上次執行狀態（Requirement 48 追加 / Task 231）。一使用者一列。
 *
 * <p><b>只存相對子路徑</b>：實際寫入目錄 = 容器內基底 {@code EXPORT_OUTPUT_DIR}（預設 {@code /home/steven}，
 * docker volume 對映 host 家目錄）resolve {@link #outputSubpath}。絕對路徑等於把容器內檔案系統位置寫進 DB，
 * 跨環境不可攜且繞過基底防護，故一律於 service 層擋下（沿用 Requirement 34／39／41／42／45 的路徑模型）。
 *
 * <p>與 {@link TradingRadarExportTime} 分表的理由：該表語意為「一列一執行時間點」，本表為「一使用者一個值」。
 * 併入會使路徑隨時間點列數重複儲存同一事實，且刪一個時間點會連帶弄丟路徑。
 *
 * <p>使用者可能「只設了時間點、還沒設資料夾」，此時本列不存在——排程寫入狀態時須自行 upsert 建列
 * （以 {@link #DEFAULT_SUBPATH} 為預設），不可假設該列必定存在。
 */
@Entity
@Table(name = "trading_radar_export_setting", uniqueConstraints = @UniqueConstraint(
        name = "uq_tr_export_setting_owner", columnNames = {"owner_user_id"}))
@Filter(name = "ownerFilter", condition = "owner_user_id = :ownerId")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TradingRadarExportSetting {

    /** 尚未設定時的預設輸出子路徑（相對 EXPORT_OUTPUT_DIR）。 */
    public static final String DEFAULT_SUBPATH = "input";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 擁有者（Requirement 28） */
    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    /** 輸出目錄相對子路徑（相對容器基底 EXPORT_OUTPUT_DIR） */
    @Column(name = "output_subpath", nullable = false, length = 512)
    @Builder.Default
    private String outputSubpath = DEFAULT_SUBPATH;

    /** 上次執行時間 */
    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    /** 上次執行結果（「成功：/path」「當日尚無快照，未產檔」或「失敗：訊息」） */
    @Column(name = "last_run_status", length = 500)
    private String lastRunStatus;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
