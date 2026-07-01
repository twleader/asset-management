package com.steven.assets.repository;

import com.steven.assets.model.NotificationRecipient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface NotificationRecipientRepository extends JpaRepository<NotificationRecipient, Long> {

    Optional<NotificationRecipient> findByEmail(String email);

    List<NotificationRecipient> findAllByOrderByCreatedAtAsc();

    List<NotificationRecipient> findByActiveTrueOrderByCreatedAtAsc();

    /**
     * 依 id 批次查詢（Requirement 30）：受 {@code ownerFilter} 覆蓋的派生查詢，
     * 在 HTTP 請求執行緒下必被限縮成只回當前租戶擁有的收件人。
     * 供 {@code StockAlertService.replaceRecipients} 過濾使用者傳入的 recipientIds，
     * 避免把他人收件人綁進自己的警示。
     */
    List<NotificationRecipient> findByIdIn(Collection<Long> ids);
}
