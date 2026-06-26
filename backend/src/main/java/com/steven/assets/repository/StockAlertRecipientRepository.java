package com.steven.assets.repository;

import com.steven.assets.model.StockAlertRecipient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 警示 ↔ 收件人 join 存取（Requirement 23 / Task 125）。
 */
@Repository
public interface StockAlertRecipientRepository extends JpaRepository<StockAlertRecipient, Long> {

    /** 某警示目前選定的收件人 id（供 Response 回填、對話框預勾）。 */
    @Query("SELECT s.recipientId FROM StockAlertRecipient s WHERE s.alertId = :alertId")
    List<Long> findRecipientIdsByAlertId(@Param("alertId") Long alertId);

    /**
     * 某警示「選定 ∩ active=true」的收件人 email（dispatcher 寄信用，已正規化小寫）。
     * 直接 join notification_recipient，一次取出實際收件 email，避免兩段查詢。
     */
    @Query("SELECT r.email FROM StockAlertRecipient s, com.steven.assets.model.NotificationRecipient r " +
            "WHERE s.alertId = :alertId AND s.recipientId = r.id AND r.active = true")
    List<String> findActiveEmailsByAlertId(@Param("alertId") Long alertId);

    // 用 bulk @Modifying delete（呼叫當下立即執行 DELETE），而非衍生刪除：
    // 衍生刪除是 entity-remove，Hibernate action queue 預設「先 insert 後 delete」，
    // 會讓 replaceRecipients（先刪後插同一 alert）的新 insert 撞到尚未刪的舊列 → unique 違反。
    @Modifying
    @Transactional
    @Query("DELETE FROM StockAlertRecipient s WHERE s.alertId = :alertId")
    void deleteByAlertId(@Param("alertId") Long alertId);

    @Modifying
    @Transactional
    @Query("DELETE FROM StockAlertRecipient s WHERE s.recipientId = :recipientId")
    void deleteByRecipientId(@Param("recipientId") Long recipientId);
}
