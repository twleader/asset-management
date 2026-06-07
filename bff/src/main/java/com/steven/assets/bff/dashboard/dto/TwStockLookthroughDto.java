package com.steven.assets.bff.dashboard.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 「資產配置分佈」第 2 tab「台股個股」回傳 DTO。
 *
 * 計算邏輯（見 Requirement 9 + DashboardBffController.getTwStockLookthrough）：
 *  1. 取 snapshot 的 mergedStocks → 篩 market = 台股
 *  2. ETF（stockCode 以 00 開頭）依 FinMind TaiwanETFHoldings 權重拆解 currentValue × weight%
 *  3. 直接持股不拆，整筆計入該代號
 *  4. 同 stockCode 加總，排序取 top 10，其餘合併為 others
 *
 * percent 為佔台股總值（totalTwStockValue）的百分比，前端直接顯示。
 */
@Data
public class TwStockLookthroughDto {

    private String snapshotDate;
    private BigDecimal totalTwStockValue;
    private List<Item> items;
    private Others others;
    /** ETF 抓不到成分股 → 該 ETF 整筆退回以代號自身計入，並列入此清單供前端顯示降級註記 */
    private List<DegradedEtf> degradedEtfs;

    @Data
    public static class Item {
        private String stockCode;
        private String stockName;
        private BigDecimal value;
        private BigDecimal percent;
    }

    @Data
    public static class Others {
        private BigDecimal value;
        private BigDecimal percent;
        /** 落在「其它」的個股代號數量 */
        private int constituentCount;
    }

    @Data
    public static class DegradedEtf {
        private String code;
        private String message;
    }
}
