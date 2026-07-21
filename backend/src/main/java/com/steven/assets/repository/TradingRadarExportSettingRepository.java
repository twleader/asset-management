package com.steven.assets.repository;

import com.steven.assets.model.TradingRadarExportSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 交易雷達排程匯出的輸出資料夾設定（Requirement 48 追加 / Task 231）。一使用者一列。 */
public interface TradingRadarExportSettingRepository extends JpaRepository<TradingRadarExportSetting, Long> {

    Optional<TradingRadarExportSetting> findByOwnerUserId(Long ownerUserId);
}
