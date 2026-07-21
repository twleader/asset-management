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
 * 交易雷達排程匯出的執行時間點（Requirement 48 追加 / Task 231）。
 *
 * <p><b>一列一時間點、per owner</b>：比照公開資訊爬蟲（{@link CrawlerSchedule}）可設定多個每日執行時間點；
 * 差別是爬蟲為全域設定（無 owner），而交易雷達的快照與持股皆 owner-scoped，故本表帶 {@code owner_user_id}
 * 與 {@code @Filter(ownerFilter)}。
 *
 * <p><b>{@link #lastRunDate} 刻意放在「時間點」列而非 owner 層</b>：當日 guard 必須 per 時間點，
 * 否則同日設 08:20／11:30／20:30 只會跑第一個（第一個跑完就把 owner 層 guard 設為今日）。
 *
 * <p>輸出資料夾不在本表，而在 {@link TradingRadarExportSetting}——併入會使同一路徑隨時間點列數重複儲存
 * 同一事實（CLAUDE.md 資料庫完整正規化），且刪一個時間點會連帶弄丟路徑（沿用 {@link CrawlerExportSetting}
 * 與 {@link CrawlerSchedule} 分表的既有理由）。
 *
 * <p>背景排程無 request context → {@code ownerFilter} 不啟用，{@code findAll()} 讀全部 owner 列供逐列產檔；
 * owner 一律取自列上的 {@link #ownerUserId}，不得依賴 request-scoped 的 CurrentUserContext。
 */
@Entity
@Table(name = "trading_radar_export_time", uniqueConstraints = @UniqueConstraint(
        name = "uq_tr_export_time_owner_time", columnNames = {"owner_user_id", "run_hour", "run_minute"}))
@Filter(name = "ownerFilter", condition = "owner_user_id = :ownerId")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TradingRadarExportTime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 擁有者（Requirement 28） */
    @Column(name = "owner_user_id", nullable = false)
    private Long ownerUserId;

    /** 每日執行時（0..23） */
    @Column(name = "run_hour", nullable = false)
    private Integer runHour;

    /** 每日執行分（0..59） */
    @Column(name = "run_minute", nullable = false)
    private Integer runMinute;

    /** 是否啟用此時間點 */
    @Column(nullable = false)
    @Builder.Default
    private Boolean enabled = Boolean.TRUE;

    /** 當日 guard（per 時間點）：成功或失敗都設為當日，避免命中分鐘後每 poll 重試整天。 */
    @Column(name = "last_run_date")
    private LocalDate lastRunDate;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
