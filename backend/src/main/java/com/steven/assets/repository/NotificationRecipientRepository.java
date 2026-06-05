package com.steven.assets.repository;

import com.steven.assets.model.NotificationRecipient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NotificationRecipientRepository extends JpaRepository<NotificationRecipient, Long> {

    Optional<NotificationRecipient> findByEmail(String email);

    List<NotificationRecipient> findAllByOrderByCreatedAtAsc();

    List<NotificationRecipient> findByActiveTrueOrderByCreatedAtAsc();
}
