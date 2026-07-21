package com.steven.assets.repository;

import com.steven.assets.model.TradingRadarExportTime;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 交易雷達排程匯出時間點（Requirement 48 追加 / Task 231）。
 *
 * <p>HTTP 情境由 {@code TenantFilterAspect} 自動套 {@code ownerFilter}；仍建議顯式用
 * {@link #findAllByOwnerUserIdOrderByRunHourAscRunMinuteAsc} 表達意圖。
 * 背景排程無 request context → filter 不啟用，{@code findAll()} 讀全部 owner 列供逐列產檔。
 */
public interface TradingRadarExportTimeRepository extends JpaRepository<TradingRadarExportTime, Long> {

    List<TradingRadarExportTime> findAllByOwnerUserIdOrderByRunHourAscRunMinuteAsc(Long ownerUserId);

    void deleteByOwnerUserId(Long ownerUserId);
}
