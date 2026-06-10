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

    /** 補發用：trigger 表內出現過的市場（各市場各自算「最後交易日」）。 */
    @Query("SELECT DISTINCT t.market FROM StockAlertTrigger t")
    List<String> findDistinctMarkets();

    /** 補發用：某市場 triggered_at 落在 [start, end) 當日窗的觸發（依時間升冪）。 */
    @Query("SELECT t FROM StockAlertTrigger t WHERE t.market = :market " +
            "AND t.triggeredAt >= :start AND t.triggeredAt < :end ORDER BY t.triggeredAt ASC")
    List<StockAlertTrigger> findByMarketAndTriggeredAtInDay(@Param("market") String market,
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    @Modifying
    @Query("DELETE FROM StockAlertTrigger t WHERE t.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
