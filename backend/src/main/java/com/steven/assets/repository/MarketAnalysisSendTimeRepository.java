package com.steven.assets.repository;

import com.steven.assets.model.MarketAnalysisSendTime;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * 今日股市分析（Requirement 31 / Task 184）可設定的分析寄送時間。全域參考資料、無 owner 過濾。
 */
public interface MarketAnalysisSendTimeRepository extends JpaRepository<MarketAnalysisSendTime, Long> {

    /** 全部（升序）——管理清單用。 */
    List<MarketAnalysisSendTime> findAllByOrderBySendTimeAsc();

    /** 啟用中（升序）——排程 tick／self-heal 用。 */
    List<MarketAnalysisSendTime> findByActiveTrueOrderBySendTimeAsc();

    /** 唯一性檢查用（send_time 已截到分）。 */
    Optional<MarketAnalysisSendTime> findBySendTime(LocalTime sendTime);
}
