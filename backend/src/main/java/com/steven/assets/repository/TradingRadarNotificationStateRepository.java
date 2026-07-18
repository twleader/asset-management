package com.steven.assets.repository;

import com.steven.assets.model.TradingRadarNotificationState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface TradingRadarNotificationStateRepository
        extends JpaRepository<TradingRadarNotificationState, Long> {

    @Query("SELECT s.stateCode FROM TradingRadarNotificationState s " +
            "WHERE s.settingId = :settingId AND s.stateType = :stateType")
    List<String> findStateCodes(@Param("settingId") Long settingId,
                                @Param("stateType") String stateType);

    @Modifying
    @Transactional
    @Query("DELETE FROM TradingRadarNotificationState s WHERE s.settingId = :settingId")
    void deleteBySettingId(@Param("settingId") Long settingId);
}
