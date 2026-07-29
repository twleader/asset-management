package com.steven.assets.repository;

import com.steven.assets.model.StockAlertExportSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 警示觸發即時匯出設定（Requirement 54 / Task 254），每個使用者一列。
 *
 * <p>觸發路徑走 Redis 訂閱者執行緒、無 request context，{@code ownerFilter} 不會被 enable，
 * 故一律以明確的 {@link #findByOwnerUserId(Long)} 取件、不依賴 filter。
 */
public interface StockAlertExportSettingRepository extends JpaRepository<StockAlertExportSetting, Long> {

    Optional<StockAlertExportSetting> findByOwnerUserId(Long ownerUserId);
}
