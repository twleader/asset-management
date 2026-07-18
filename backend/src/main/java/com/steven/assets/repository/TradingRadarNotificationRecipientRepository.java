package com.steven.assets.repository;

import com.steven.assets.model.TradingRadarNotificationRecipient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface TradingRadarNotificationRecipientRepository
        extends JpaRepository<TradingRadarNotificationRecipient, Long> {

    @Query("SELECT r.recipientId FROM TradingRadarNotificationRecipient r " +
            "WHERE r.settingId = :settingId")
    List<Long> findRecipientIds(@Param("settingId") Long settingId);

    /** 背景寄信縱深防護：只取與 setting 同 owner 且仍啟用的收件人。 */
    @Query("SELECT r.email FROM TradingRadarNotificationRecipient j, " +
            "com.steven.assets.model.NotificationRecipient r, " +
            "com.steven.assets.model.TradingRadarNotificationSetting s " +
            "WHERE j.settingId = :settingId AND j.recipientId = r.id AND s.id = j.settingId " +
            "AND r.ownerUserId = s.ownerUserId AND r.active = true")
    List<String> findActiveEmails(@Param("settingId") Long settingId);

    @Modifying
    @Transactional
    @Query("DELETE FROM TradingRadarNotificationRecipient r WHERE r.settingId = :settingId")
    void deleteBySettingId(@Param("settingId") Long settingId);

    @Modifying
    @Transactional
    @Query("DELETE FROM TradingRadarNotificationRecipient r WHERE r.recipientId = :recipientId")
    void deleteByRecipientId(@Param("recipientId") Long recipientId);
}
