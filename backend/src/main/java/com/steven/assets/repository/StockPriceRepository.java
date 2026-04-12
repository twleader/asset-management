package com.steven.assets.repository;

import com.steven.assets.model.StockPrice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StockPriceRepository extends JpaRepository<StockPrice, Long> {

    Optional<StockPrice> findByStockCodeAndMarket(String stockCode, String market);

    List<StockPrice> findByMarket(String market);

    List<StockPrice> findAllByOrderByMarketAscStockCodeAsc();
}
