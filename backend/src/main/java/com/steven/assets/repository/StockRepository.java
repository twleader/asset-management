package com.steven.assets.repository;

import com.steven.assets.model.Stock;
import com.steven.assets.model.StockId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StockRepository extends JpaRepository<Stock, StockId> {

    Optional<Stock> findByCodeAndMarket(String code, String market);

    /**
     * 以目前畫面實際需要的代號一次載入主檔名稱；呼叫端仍以 (code, market) 精確配對，
     * 避免逐列查詢造成 dashboard 的 N+1。
     */
    @Query("SELECT s FROM Stock s WHERE s.code IN :codes")
    List<Stock> findAllByCodeIn(@Param("codes") Collection<String> codes);

    /** 是否已存在該主檔（StockMasterService 用以判定「新標的」→ 是否觸發 10 年歷史回補）。 */
    boolean existsByCodeAndMarket(String code, String market);

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
