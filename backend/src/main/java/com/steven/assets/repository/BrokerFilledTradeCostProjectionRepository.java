package com.steven.assets.repository;

import com.steven.assets.model.BrokerFilledTradeCostProjection;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface BrokerFilledTradeCostProjectionRepository extends JpaRepository<BrokerFilledTradeCostProjection, Long> {
    @Query("SELECT p FROM BrokerFilledTradeCostProjection p WHERE p.ownerUserId = :ownerId " +
            "AND p.broker.id = :brokerId AND p.stockCode = :code AND p.market = '台股' " +
            "AND p.currency = 'TWD' AND p.status = 'PENDING' AND p.transactionType = '買' " +
            "ORDER BY p.tradeDate ASC, p.brokerFilledNo ASC")
    List<BrokerFilledTradeCostProjection> findPendingBuys(Long ownerId, Long brokerId, String code);
}
