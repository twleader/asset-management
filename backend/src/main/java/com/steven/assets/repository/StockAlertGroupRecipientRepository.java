package com.steven.assets.repository;

import com.steven.assets.model.StockAlertGroupRecipient;
import com.steven.assets.repository.projection.AlertRecipientTarget;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 複合條件群組 ↔ 收件人 join 存取（Requirement 23 / Task 253）。
 * 與 {@link StockAlertRecipientRepository} 一一對應，只是主體由單一警示換成群組。
 */
@Repository
public interface StockAlertGroupRecipientRepository extends JpaRepository<StockAlertGroupRecipient, Long> {

    /** 某群組目前選定的收件人 id（供 Response 回填、對話框預勾）。 */
    @Query("SELECT s.recipientId FROM StockAlertGroupRecipient s WHERE s.groupId = :groupId")
    List<Long> findRecipientIdsByGroupId(@Param("groupId") Long groupId);

    /**
     * 某群組「選定 ∩ active=true」的收件人身分（dispatcher 寄信用；email 已正規化小寫）。
     * 直接 join notification_recipient，一次取出 id / email / addToCalendar，避免兩段查詢。
     *
     * <p>Requirement 30 defense-in-depth（Task 145 的洞，不可從群組這個新入口重新打開）：
     * 本查詢由背景寄信 cron（{@code AlertNotificationDispatcher.flush()}，{@code @Scheduled}）呼叫，
     * 無 HTTP request context → {@code TenantFilterAspect} 明文放行、Hibernate {@code ownerFilter} 不啟用；
     * 且 {@link StockAlertGroupRecipient} join entity 無 owner 欄位／{@code @Filter}。故額外 join
     * {@code StockAlertGroup g} 並加「收件人須與群組同一擁有者」條件（{@code r.ownerUserId = g.ownerUserId}），
     * 即使 join 表殘存跨租戶列，也不會把通知寄到他人租戶 email。
     * 與寫入端 {@code replaceGroupRecipients} 的白名單交集過濾互補；同租戶正當收件人結果不變。
     *
     * <p>投影用 {@link AlertRecipientTarget}（Task 248）：夾帶日曆邀請需要收件人的 {@code id}
     * （寫進 ics 的 UID）與 {@code addToCalendar}，而**不能**以 email 反查取得
     * ——{@code notification_recipient} 唯一鍵是複合 {@code (owner_user_id, email)}，不同使用者可各自
     * 使用同一 email，在無 {@code ownerFilter} 的背景執行緒下反查會撈到別的租戶。
     *
     * <p>{@code SELECT new} 後寫完整套件路徑是 Hibernate constructor expression 的限制，不可簡寫。
     */
    @Query("SELECT new com.steven.assets.repository.projection.AlertRecipientTarget(r.id, r.email, r.addToCalendar) " +
            "FROM StockAlertGroupRecipient s, com.steven.assets.model.NotificationRecipient r, " +
            "com.steven.assets.model.StockAlertGroup g " +
            "WHERE s.groupId = :groupId AND s.recipientId = r.id AND g.id = s.groupId " +
            "AND r.ownerUserId = g.ownerUserId AND r.active = true")
    List<AlertRecipientTarget> findActiveTargetsByGroupId(@Param("groupId") Long groupId);

    // 用 bulk @Modifying delete（呼叫當下立即執行 DELETE），而非衍生刪除：
    // 衍生刪除是 entity-remove，Hibernate action queue 預設「先 insert 後 delete」，
    // 會讓 replaceGroupRecipients（先刪後插同一 group）的新 insert 撞到尚未刪的舊列
    // → uq_stock_alert_group_recipient 唯一鍵違反。
    @Modifying
    @Transactional
    @Query("DELETE FROM StockAlertGroupRecipient s WHERE s.groupId = :groupId")
    void deleteByGroupId(@Param("groupId") Long groupId);

    @Modifying
    @Transactional
    @Query("DELETE FROM StockAlertGroupRecipient s WHERE s.recipientId = :recipientId")
    void deleteByRecipientId(@Param("recipientId") Long recipientId);
}
