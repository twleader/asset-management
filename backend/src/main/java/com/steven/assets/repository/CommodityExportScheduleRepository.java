package com.steven.assets.repository;

import com.steven.assets.model.CommodityExportSchedule;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 油價金價排程自動匯出設定 repository（Requirement 41 / Task 203；多時間點 Requirement 72 / Task 330）。
 *
 * <p>HTTP 情境下 {@code @Filter(ownerFilter)} 由 {@link com.steven.assets.security.TenantFilterAspect}
 * 自動套用，{@code findByOwnerUserId} 只會回本人列；背景排程 {@code findAll()} 無 request context
 * → filter 不啟用，回全部 owner 列供逐列產檔。
 *
 * <p>一般查詢以 {@code @EntityGraph(attributePaths = "times")} 初始化聚合，供讀取使用。
 * 執行 capture／completion 與 UI 儲存由獨立短交易 store 處理；parent lock 查詢刻意不 join children，
 * 鎖定後 fresh-read 現行子集合，避免 PostgreSQL FOR UPDATE 對 outer join 的限制。
 */
public interface CommodityExportScheduleRepository extends JpaRepository<CommodityExportSchedule, Long> {

    /** Parent-only lock: collection joins would make PostgreSQL FOR UPDATE invalid. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select s from CommodityExportSchedule s where s.ownerUserId = :owner")
    Optional<CommodityExportSchedule> findLockedByOwnerUserId(@org.springframework.data.repository.query.Param("owner") Long owner);

    @EntityGraph(attributePaths = "times")
    Optional<CommodityExportSchedule> findByOwnerUserId(Long ownerUserId);

    @Override
    @EntityGraph(attributePaths = "times")
    List<CommodityExportSchedule> findAll();
}
