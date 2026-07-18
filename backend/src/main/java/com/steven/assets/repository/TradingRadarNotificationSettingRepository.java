package com.steven.assets.repository;

import com.steven.assets.model.TradingRadarNotificationSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TradingRadarNotificationSettingRepository
        extends JpaRepository<TradingRadarNotificationSetting, Long> {

    Optional<TradingRadarNotificationSetting> findByStockCodeAndMarket(String stockCode, String market);

    List<TradingRadarNotificationSetting> findByActiveTrueAndStockCodeAndMarket(
            String stockCode, String market);

    List<TradingRadarNotificationSetting> findByActiveTrueAndMarket(String market);
}
