package com.steven.assets.repository;

import com.steven.assets.model.AssetTransactionExportSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * 資產交易紀錄排程自動匯出設定 repository（Requirement 49 / Task 238；Task 255 起每人多列）。
 *
 * <p>HTTP 情境下 {@code @Filter(ownerFilter)} 由 {@link com.steven.assets.security.TenantFilterAspect}
 * 自動套用，derived query 只會回本人列；背景排程 {@code findAll()} 無 request context
 * → filter 不啟用，回全部 owner 的全部列供逐列產檔。
 *
 * <p><b>{@link #findByIdAndOwnerUserId} 是多租戶關鍵，不得以 {@code findById} 取代</b>：Hibernate
 * {@code @Filter} 不套用於 {@code EntityManager.find()}（即 {@code findById}／{@code deleteById}），
 * 用它會讓任何人以他人排程 id 讀取／修改／刪除／立即觸發他人的排程
 * （見 {@link com.steven.assets.security.TenantGuard} javadoc）。
 */
public interface AssetTransactionExportScheduleRepository
        extends JpaRepository<AssetTransactionExportSchedule, Long> {

    /** 本人全部排程，依執行時間排序（同時間再依 id，確保順序穩定）。 */
    List<AssetTransactionExportSchedule> findByOwnerUserIdOrderByRunHourAscRunMinuteAscIdAsc(Long ownerUserId);

    /** by-id 存取一律帶 owner 條件；查無＝不存在或非本人，呼叫端一律回 404（不區分兩者）。 */
    Optional<AssetTransactionExportSchedule> findByIdAndOwnerUserId(Long id, Long ownerUserId);

    /** 每人排程數上限檢查用。 */
    long countByOwnerUserId(Long ownerUserId);
}
