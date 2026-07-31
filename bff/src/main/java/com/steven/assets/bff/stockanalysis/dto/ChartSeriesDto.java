package com.steven.assets.bff.stockanalysis.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 走勢圖（StockAnalysisDialog）上下兩個 pane 的完整資料（Task 261）。
 *
 * 所有 List 等長，逐格對應 {@code dates}；某日在某一側缺值即為 null。
 * 前端只 render——日期聯集對齊、指標尾值挑選都已在 BFF 完成。
 */
public record ChartSeriesDto(
        List<String> dates,
        List<BigDecimal> prices,
        List<BigDecimal> ma20,
        List<BigDecimal> ma60,
        List<BigDecimal> ma240,
        List<BigDecimal> k,
        List<BigDecimal> d,
        List<BigDecimal> j9,
        List<BigDecimal> k3d2,
        List<BigDecimal> rsv,
        Latest latest) {

    /**
     * legend 與「當日」水平參考線用的最新值。
     *
     * 取自<b>指標序列本身</b>的最後一筆，而非對齊後陣列的最後一筆——兩者在
     * 「指標有今日、股價沒有」時會不同（0000 台股大盤必然如此），取錯就會顯示前一交易日的值。
     * prev* 僅五個 KD 指標需要（供 legend 漲跌箭頭比較），均線不加箭頭。
     */
    public record Latest(
            BigDecimal ma20,
            BigDecimal ma60,
            BigDecimal ma240,
            BigDecimal k,
            BigDecimal d,
            BigDecimal j9,
            BigDecimal k3d2,
            BigDecimal rsv,
            BigDecimal prevK,
            BigDecimal prevD,
            BigDecimal prevJ9,
            BigDecimal prevK3d2,
            BigDecimal prevRsv) {}
}
