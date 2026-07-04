package com.steven.assets.repository;

import com.steven.assets.model.MarketAnalysisSetting;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 今日股市分析（Requirement 31）單列設定。全域參考資料、無 owner 過濾。
 */
public interface MarketAnalysisSettingRepository extends JpaRepository<MarketAnalysisSetting, Integer> {
}
