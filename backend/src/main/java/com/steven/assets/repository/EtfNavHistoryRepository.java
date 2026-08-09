package com.steven.assets.repository;

import com.steven.assets.model.EtfNavHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

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

    /** 只取決策目標完成日的 dated observation，避免 DB fallback 把舊 NAV 當成今日。 */
    @Query("""
            select e from EtfNavHistory e
            where e.stockCode = :code and e.market = :market
              and e.navDate = :navDate and e.premiumDiscountPct is not null
            order by e.id desc
            """)
    Optional<EtfNavHistory> findPremiumObservationOnDate(
            @Param("code") String code,
            @Param("market") String market,
            @Param("navDate") LocalDate navDate);

    /** 只回傳不晚於決策日的最近 observation，供 stale provenance 揭露與歷史分位。 */
    @Query("""
            select e from EtfNavHistory e
            where e.stockCode = :code and e.market = :market
              and e.navDate <= :asOfDate and e.premiumDiscountPct is not null
            order by e.navDate desc, e.id desc
            """)
    List<EtfNavHistory> findRecentPremiumObservationsAsOf(
            @Param("code") String code,
            @Param("market") String market,
            @Param("asOfDate") LocalDate asOfDate,
            org.springframework.data.domain.Pageable pageable);

    @Query("""
            select e.premiumDiscountPct from EtfNavHistory e
            where e.stockCode = :code and e.market = :market
              and e.navDate <= :asOfDate and e.premiumDiscountPct is not null
            order by e.navDate desc
            """)
    List<BigDecimal> findRecentPremiumPctAsOf(
            @Param("code") String code,
            @Param("market") String market,
            @Param("asOfDate") LocalDate asOfDate,
            org.springframework.data.domain.Pageable pageable);

    /**
     * 該標的的折溢價全序列（升序），供 Task 273 的回測逐日對齊。
     *
     * <p><b>回測期間結構性缺值</b>：本表建於 2026-07-19 且不回填，實測僅 11 個交易日／19 檔，
     * 十年 2400+ 個交易日中約 0.5% 有值。回測必須逐標的揭露「實際有值的天數」，
     * 不得讓缺值靜默變成「折溢價否決永不成立」而被誤讀為 production 行為。</p>
     */
    List<EtfNavHistory> findByStockCodeAndMarketOrderByNavDateAsc(String stockCode, String market);
}
