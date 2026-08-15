package com.steven.assets.repository;

import com.steven.assets.model.RealizedGainExportSchedule;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 已實現損益排程自動匯出設定 repository（Requirement 39 / Task 196；多時間點 Requirement 73 / Task 331）。
 *
 * <p>HTTP 情境下 {@code @Filter(ownerFilter)} 由 {@link com.steven.assets.security.TenantFilterAspect}
 * 自動套用，{@code findByOwnerUserId} 只會回本人列；背景排程 {@code findAll()} 無 request context
 * → filter 不啟用，回全部 owner 列供逐列產檔。
 *
 * <p>兩支查詢都以 {@code @EntityGraph} fetch join {@code times}：背景 poll 需要在同一 transaction 內
 * 可靠讀到 children，HTTP 端則避免每列再補一次 lazy 查詢。
 */
public interface RealizedGainExportScheduleRepository extends JpaRepository<RealizedGainExportSchedule, Long> {

    @EntityGraph(attributePaths = "times")
    Optional<RealizedGainExportSchedule> findByOwnerUserId(Long ownerUserId);

    @Override
    @EntityGraph(attributePaths = "times")
    java.util.List<RealizedGainExportSchedule> findAll();
}
