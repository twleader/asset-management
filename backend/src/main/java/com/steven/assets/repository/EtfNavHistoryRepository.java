package com.steven.assets.repository;

import com.steven.assets.model.EtfNavHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

/**
 * ETF 淨值／折溢價歷史的唯讀查詢（Task 264）。
 *
 * <p>全域公開行情，不套 owner 過濾。</p>
 */
public interface EtfNavHistoryRepository extends JpaRepository<EtfNavHistory, Long> {

    /**
     * 該標的最近 N 筆非 null 的折溢價（新到舊），供自身歷史分位計算。
     *
     * <p>回傳純數值而非 entity：分位只需要折溢價這一欄，取整列會多載無用欄位。</p>
     */
    @Query("""
            select e.premiumDiscountPct from EtfNavHistory e
            where e.stockCode = :code and e.market = :market and e.premiumDiscountPct is not null
            order by e.navDate desc
            """)
    List<BigDecimal> findRecentPremiumPct(
            @Param("code") String code, @Param("market") String market,
            org.springframework.data.domain.Pageable pageable);
}
