package com.steven.assets.repository;

import com.steven.assets.model.DailyMarketAnalysis;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 今日股市分析（Requirement 31）。全域參考資料、無 owner 過濾。
 */
public interface DailyMarketAnalysisRepository
        extends JpaRepository<DailyMarketAnalysis, LocalDate> {

    /** 最近一筆分析（供頁面「今日」卡片）。 */
    Optional<DailyMarketAnalysis> findTopByOrderByAnalysisDateDesc();

    @Query("SELECT a FROM DailyMarketAnalysis a ORDER BY a.analysisDate DESC")
    List<DailyMarketAnalysis> findRecent(PageRequest page);

    /** 近 N 筆（analysis_date 降序），供歷史列表。 */
    default List<DailyMarketAnalysis> findRecent(int limit) {
        return findRecent(PageRequest.of(0, limit));
    }
}
