package com.steven.assets.repository;

import com.steven.assets.model.ExportScheduleSetting;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.EntityGraph;

import java.util.Optional;

/**
 * 排程自動匯出設定 repository（Requirement 34 / Task 171）。
 *
 * <p>HTTP 情境下 {@code @Filter(ownerFilter)} 由 {@link com.steven.assets.security.TenantFilterAspect}
 * 自動套用，{@code findByOwnerUserId} 只會回本人列；背景排程 {@code findAll()} 無 request context
 * → filter 不啟用，回全部 owner 列供逐列產檔。
 */
public interface ExportScheduleSettingRepository extends JpaRepository<ExportScheduleSetting, Long> {

    @EntityGraph(attributePaths = "times")
    Optional<ExportScheduleSetting> findByOwnerUserId(Long ownerUserId);

    @Override
    @EntityGraph(attributePaths = "times")
    java.util.List<ExportScheduleSetting> findAll();
}
