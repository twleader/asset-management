package com.steven.assets.repository;

import com.steven.assets.model.FundDividendHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface FundDividendHistoryRepository extends JpaRepository<FundDividendHistory, Long> {

    /** 取指定區間的配息紀錄（用於計算近 12 個月加總），由新到舊排序。 */
    List<FundDividendHistory> findByFundCodeAndBaseDateGreaterThanEqualOrderByBaseDateDesc(
            String fundCode, LocalDate sinceInclusive);

    /** 取 [from, to] 區間內配息紀錄（Requirement 21 基準日估值用）。 */
    List<FundDividendHistory> findByFundCodeAndBaseDateBetweenOrderByBaseDateDesc(
            String fundCode, LocalDate from, LocalDate to);
}
