package com.steven.assets.controller;

import com.steven.assets.service.PerformanceComparisonService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 績效比較頁（Requirement 33）business API：提供該登入使用者的「我的股票」下拉清單（owner-scoped）。
 *
 * 報酬率正規化與跨標的聚合放該頁專屬 BFF（PerformanceComparisonBffController），本 controller 不算報酬率。
 */
@RestController
@RequestMapping("/api/performance-comparison")
@RequiredArgsConstructor
public class PerformanceComparisonController {

    private final PerformanceComparisonService service;

    /**
     * 我的股票（可比較）清單：持股 ∪ 觀察，去重、排除 0000/台股、過濾無歷史者、股名由 stock 主檔補。
     * owner 隔離由 repository 層 TenantFilterAspect 自動生效，僅回當前使用者名下標的。
     */
    @GetMapping("/my-stocks")
    public List<PerformanceComparisonService.StockItem> getMyStocks() {
        return service.getMyComparableStocks();
    }
}
