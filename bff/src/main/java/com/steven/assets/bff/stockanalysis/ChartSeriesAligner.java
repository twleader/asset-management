package com.steven.assets.bff.stockanalysis;

import com.steven.assets.bff.stockanalysis.dto.ChartSeriesDto;
import com.steven.assets.bff.stockanalysis.dto.IndicatorPointDto;
import com.steven.assets.bff.stockanalysis.dto.PricePointDto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * 走勢圖股價序列與指標序列的日期對齊（Task 261）。
 *
 * 抽成純函式是刻意的：BFF 端無法用 curl 驗證（`asset-bff` 映像沒有 curl，
 * 且未登入一律回 401、狀態碼無法區分 controller 有沒有接走），這裡是唯一能機械釘住
 * 「聯集對齊」與「latest 取指標序列尾筆」兩條規則的地方。
 */
public final class ChartSeriesAligner {

    private ChartSeriesAligner() {}

    /**
     * 以 tradingDate 的<b>聯集</b>對齊兩序列。
     *
     * 不可拿股價側日期當基準：指標側的日期集合可能嚴格較大——股價側要求 live 的 source
     * 不含括號，而 0000 台股大盤更是完全不併 live；指標側則比照 computeAll() 併入。
     * 取股價側會把「今日」的指標點靜默丟掉，legend 顯示前一交易日的值，
     * 等於 Task 261 要消滅的不一致原封不動留著。
     */
    public static ChartSeriesDto align(List<PricePointDto> prices, List<IndicatorPointDto> indicators) {
        List<PricePointDto> priceRows = prices == null ? List.of() : prices;
        List<IndicatorPointDto> indicatorRows = indicators == null ? List.of() : indicators;

        Map<String, PricePointDto> priceByDate = new LinkedHashMap<>();
        for (PricePointDto p : priceRows) {
            if (p != null && p.tradingDate() != null) priceByDate.put(p.tradingDate(), p);
        }
        Map<String, IndicatorPointDto> indicatorByDate = new LinkedHashMap<>();
        for (IndicatorPointDto p : indicatorRows) {
            if (p != null && p.tradingDate() != null) indicatorByDate.put(p.tradingDate(), p);
        }

        // ISO yyyy-MM-dd 的字典序即時間序，TreeSet 直接給升冪聯集
        TreeSet<String> allDates = new TreeSet<>(priceByDate.keySet());
        allDates.addAll(indicatorByDate.keySet());

        List<String> dates = new ArrayList<>(allDates);
        List<BigDecimal> priceCol = new ArrayList<>(dates.size());
        for (String date : dates) {
            PricePointDto p = priceByDate.get(date);
            priceCol.add(p == null ? null : p.closePrice());
        }

        return new ChartSeriesDto(
                dates,
                priceCol,
                column(dates, indicatorByDate, IndicatorPointDto::ma20),
                column(dates, indicatorByDate, IndicatorPointDto::ma60),
                column(dates, indicatorByDate, IndicatorPointDto::ma240),
                column(dates, indicatorByDate, IndicatorPointDto::k),
                column(dates, indicatorByDate, IndicatorPointDto::d),
                column(dates, indicatorByDate, IndicatorPointDto::j9),
                column(dates, indicatorByDate, IndicatorPointDto::k3d2),
                column(dates, indicatorByDate, IndicatorPointDto::rsv),
                latestOf(indicatorRows));
    }

    private static List<BigDecimal> column(List<String> dates,
                                           Map<String, IndicatorPointDto> byDate,
                                           Function<IndicatorPointDto, BigDecimal> getter) {
        List<BigDecimal> col = new ArrayList<>(dates.size());
        for (String date : dates) {
            IndicatorPointDto p = byDate.get(date);
            col.add(p == null ? null : getter.apply(p));
        }
        return col;
    }

    /**
     * legend 用的最新值：取<b>指標序列本身</b>的最後一筆與倒數第二筆（供漲跌箭頭），
     * 不是對齊後陣列的最後一筆——兩者在「指標有今日、股價沒有」時不同。
     */
    private static ChartSeriesDto.Latest latestOf(List<IndicatorPointDto> indicators) {
        if (indicators.isEmpty()) return null;
        IndicatorPointDto last = indicators.get(indicators.size() - 1);
        IndicatorPointDto prev = indicators.size() > 1 ? indicators.get(indicators.size() - 2) : null;
        return new ChartSeriesDto.Latest(
                last.ma20(), last.ma60(), last.ma240(),
                last.k(), last.d(), last.j9(), last.k3d2(), last.rsv(),
                prev == null ? null : prev.k(),
                prev == null ? null : prev.d(),
                prev == null ? null : prev.j9(),
                prev == null ? null : prev.k3d2(),
                prev == null ? null : prev.rsv());
    }
}
