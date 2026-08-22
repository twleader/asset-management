package com.steven.assets.bff.stockanalysis;

import com.steven.assets.bff.stockanalysis.dto.EtfHoldingDto;
import com.steven.assets.bff.stockanalysis.dto.EtfHoldingsDto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * ETF 持股明細「前 10 大 + 其它」聚合（Task 359.4，359.1~359.3 上線後由使用者實測發現）。
 *
 * 使用者實際點開「00919 群益台灣精選高息」測試，該檔 MoneyDJ 成分股超過 20 檔，
 * 全部個別繪成扇形導致標籤密集交錯、幾乎無法閱讀（前端 `ETF_HOLDINGS_PIE_COLORS`
 * 只有 10 色，超過 10 檔還會重複配色，加劇混淆）。
 *
 * 依 CLAUDE.md「BFF 負責跨服務 aggregation、預先計算 / 排序 / 過濾，前端只負責 render」
 * （`spec/steering/structure.md` §3.2 第 5 條），排序＋截斷不放在前端做；視覺效果比照
 * Dashboard「台股個股穿透」圖既有的 {@code DashboardBffController.buildLookthrough()}
 * 「排序取前 10 名 + 其它聚合」模式。
 */
public final class EtfHoldingsAggregator {

    /** 個別保留的最大檔數；第 11 名起加總為單一「其它」列。 */
    static final int TOP_N = 10;

    private EtfHoldingsAggregator() {}

    /**
     * 依 {@code weight} 降冪排序後取前 {@link #TOP_N} 大個別保留，第 11 筆起（若存在）
     * 加總 {@code weight} 為一筆「其它」附加在陣列尾端。
     *
     * 檔數 <= {@link #TOP_N}（含 0 檔、{@code holdings} 為 {@code null}）時原樣透傳，
     * 不產生「其它」列——不得無條件多附加一筆 0% 的「其它」（359.4d）。
     * {@code supported}／{@code source}／{@code asOfDate}／{@code message} 等頂層欄位
     * 一律原封不動透傳，不因排序截斷而遺失。
     */
    public static EtfHoldingsDto aggregate(EtfHoldingsDto source) {
        if (source == null || source.holdings() == null || source.holdings().size() <= TOP_N) {
            return source;
        }

        List<EtfHoldingDto> sorted = source.holdings().stream()
                .sorted(Comparator.comparing(EtfHoldingDto::weight,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        List<EtfHoldingDto> result = new ArrayList<>(sorted.subList(0, TOP_N));

        BigDecimal othersWeight = sorted.subList(TOP_N, sorted.size()).stream()
                .map(EtfHoldingDto::weight)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // stockCode=null／shares=null：不同個股股數不能直接相加，前端既有 null-safe
        // 邏輯（tooltip 依 stockCode 判斷、shares==null 顯示「—」）會自然正確處理（359.4c）。
        result.add(new EtfHoldingDto(null, "其它", othersWeight, null));

        return new EtfHoldingsDto(source.stockCode(), source.market(), source.supported(),
                source.source(), source.asOfDate(), source.message(), result);
    }
}
