package com.steven.assets.repository;

import com.steven.assets.model.StockAlertTrigger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface StockAlertTriggerRepository extends JpaRepository<StockAlertTrigger, Long> {

    List<StockAlertTrigger> findByAlertIdOrderByTriggeredAtDesc(Long alertId);

    @Modifying
    @Query("DELETE FROM StockAlertTrigger t WHERE t.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
