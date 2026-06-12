package com.steven.assets.bff.dashboard.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 「資產配置分佈」第 3 tab「美股個股」回傳 DTO。
 *
 * 計算邏輯（見 Requirement 9 + DashboardBffController.getUsStockLookthrough）：
 *  1. 取 snapshot 的 mergedStocks → 篩 market = 美股
 *  2. ETF（external US_ETF_WHITELIST，holdings 非空）依 Yahoo topHoldings 前 10 大「真實權重」
 *     拆解 currentValue × weight/100（不正規化）；未揭露尾段 currentValue × (1 − Σweight/100) 歸「其它」
 *  3. 直接持股不拆，整筆計入該代號
 *  4. 同 stockCode 加總（Yahoo 成分股有 symbol，故以代號為聚合鍵），排序取 top 10，其餘合併為 others
 *
 * 與台股 TwStockLookthroughDto 的差異：美股不正規化（未揭露歸其它）、以代號聚合、無 degradedEtfs
 * 動態清單（改以 lookthroughEtfCount 觸發前端固定註記）。percent 為佔美股總值的百分比。
 */
@Data
public class UsStockLookthroughDto {

    private String snapshotDate;
    private BigDecimal totalUsStockValue;
    private List<Item> items;
    private Others others;
    /** 成功穿透（取得前 10 大成分股）的美股 ETF 檔數；> 0 時前端顯示「僅前 10 大」固定註記 */
    private int lookthroughEtfCount;

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
        /** 落在「其它」的個股代號數量（不含 ETF 未揭露尾段） */
        private int constituentCount;
    }
}
