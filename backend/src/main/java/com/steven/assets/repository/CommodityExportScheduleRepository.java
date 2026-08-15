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
 * <p>兩支查詢皆加 {@code @EntityGraph(attributePaths = "times")}（比照
 * {@code ExportScheduleSettingRepository}）：讓 {@code times} 隨查詢一次 fetch join 進來，不依賴
 * session 是否還開著。<b>刻意不用「service 方法補 {@code @Transactional(readOnly=true)}」</b>這種替代方案——
 * 背景 due runner（{@code CommodityExportScheduleService.runDueExports()}）會在同一個掃描迴圈裡持續
 * {@code save(...)} 寫回每個到點 child 的 guard／狀態，若標成 {@code readOnly=true}，Hibernate 的
 * flush mode 常態性不自動 flush，這些寫入可能在 commit 時完全沒有真的落地。
 */
public interface CommodityExportScheduleRepository extends JpaRepository<CommodityExportSchedule, Long> {

    @EntityGraph(attributePaths = "times")
    Optional<CommodityExportSchedule> findByOwnerUserId(Long ownerUserId);

    @Override
    @EntityGraph(attributePaths = "times")
    List<CommodityExportSchedule> findAll();
}
