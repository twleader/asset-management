package com.steven.assets.repository;

import com.steven.assets.model.Stock;
import com.steven.assets.model.StockId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface StockRepository extends JpaRepository<Stock, StockId> {

    Optional<Stock> findByCodeAndMarket(String code, String market);

    /** UPSERT：存在則更新名稱，不存在則新增 */
    @Transactional
    @Modifying
    @Query(value = """
        INSERT INTO stock (code, market, name)
        VALUES (?1, ?2, ?3)
        ON CONFLICT (code, market) DO UPDATE SET name = EXCLUDED.name
        """, nativeQuery = true)
    void upsert(String code, String market, String name);
}
