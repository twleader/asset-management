package com.steven.assets.repository;

import com.steven.assets.model.RealizedGainExportSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 已實現損益排程自動匯出設定 repository（Requirement 39 / Task 196）。
 *
 * <p>HTTP 情境下 {@code @Filter(ownerFilter)} 由 {@link com.steven.assets.security.TenantFilterAspect}
 * 自動套用，{@code findByOwnerUserId} 只會回本人列；背景排程 {@code findAll()} 無 request context
 * → filter 不啟用，回全部 owner 列供逐列產檔。
 */
public interface RealizedGainExportScheduleRepository extends JpaRepository<RealizedGainExportSchedule, Long> {

    Optional<RealizedGainExportSchedule> findByOwnerUserId(Long ownerUserId);
}
