package com.steven.assets.repository;

import com.steven.assets.model.WatchStock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WatchStockRepository extends JpaRepository<WatchStock, Long> {
    List<WatchStock> findAllByOrderByDisplayOrderAsc();
    Optional<WatchStock> findByStockCodeAndMarket(String stockCode, String market);
}
