package com.steven.assets.repository;

import com.steven.assets.model.CrawlerExportSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * 公開資訊爬蟲輸出檔案路徑設定（Requirement 38 / Task 209）。全域設定，無租戶過濾。
 * 一爬蟲一列（{@code crawler_key} UNIQUE），設定變更走 upsert。
 */
public interface CrawlerExportSettingRepository extends JpaRepository<CrawlerExportSetting, Long> {

    Optional<CrawlerExportSetting> findByCrawlerKey(String crawlerKey);
}
