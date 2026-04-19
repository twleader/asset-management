package com.steven.assets.repository;

import com.steven.assets.model.StockAlert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockAlertRepository extends JpaRepository<StockAlert, Long> {
    List<StockAlert> findAllByOrderByDisplayOrderAsc();
    List<StockAlert> findByActiveTrue();
    List<StockAlert> findByStockCodeAndMarket(String stockCode, String market);
}
