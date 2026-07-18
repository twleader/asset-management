package com.steven.assets.repository;

import com.steven.assets.model.CommodityExportSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 油價金價排程自動匯出設定 repository（Requirement 41 / Task 202）。
 *
 * <p>HTTP 情境下 {@code @Filter(ownerFilter)} 由 {@link com.steven.assets.security.TenantFilterAspect}
 * 自動套用，{@code findByOwnerUserId} 只會回本人列；背景排程 {@code findAll()} 無 request context
 * → filter 不啟用，回全部 owner 列供逐列產檔。
 */
public interface CommodityExportScheduleRepository extends JpaRepository<CommodityExportSchedule, Long> {

    Optional<CommodityExportSchedule> findByOwnerUserId(Long ownerUserId);
}
