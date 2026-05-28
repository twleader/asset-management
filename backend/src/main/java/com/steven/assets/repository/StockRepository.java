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

    /** 反向查找：依股名精確匹配回傳第一筆（理論上 (name, market) 應唯一，極端撞名取 code 升冪第一筆）。 */
    Optional<Stock> findFirstByNameAndMarketOrderByCodeAsc(String name, String market);

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
