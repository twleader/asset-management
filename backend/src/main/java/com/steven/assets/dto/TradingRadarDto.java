package com.steven.assets.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 今日交易雷達（Requirement 43）純讀 response。
 *
 * <p>所有分數／建議皆為 {@code TW_RULES_V7} 即時計算的衍生值，不入庫；
 * {@code score=null} 代表必要資料不足，不以 0 分冒充有效判斷。</p>
 */
public final class TradingRadarDto {

    private TradingRadarDto() {}

    public record Response(
            String ruleVersion,
            String generatedAt,
            MarketSummary market,
            List<StockDecision> stocks,
            int skippedNonTwStocks
    ) {}

    /**
     * 手動「重新整理」的行情回補結果（Task 249）。
     *
     * <p>{@code outcome} ∈ {@code FETCHED}（開盤中已重抓）／{@code CLOSED_SYNCED}（休市，已同步 DB 收盤）／
     * {@code SKIPPED_PENDING_CLOSE}（今日收盤尚未落 DB 的空窗，刻意不同步）／{@code COOLDOWN}／
     * {@code BUSY}／{@code TIMEOUT}／{@code FAILED}。</p>
     *
     * <p><b>刻意不含抓取檔數</b>：回補清單來自全庫（Redis 行情快取本就是跨租戶共用的市場資料），
     * 回檔數等於把「全庫台股標的數」洩漏給任一使用者；檔數只寫 log。</p>
     */
    public record PriceRefresh(String outcome, boolean twMarketOpen, long elapsedMs) {}

    /**
     * {@code POST /api/trading-radar/refresh} 的回應（Task 249）。
     *
     * <p>{@code radar} 與 {@code GET} 完全同形——{@link Response} 不得為此新增欄位，
     * 它會被 {@code TradingRadarSnapshotStore} 序列化進 Redis 快照供 Requirement 48 的區間匯出讀回。</p>
     */
    public record RefreshResponse(Response radar, PriceRefresh priceRefresh) {}

    public record MarketSummary(
            String regime,
            String regimeLabel,
            Integer score,
            boolean dataComplete,
            /** 大盤最新完成日 K 非當前交易日、且 Redis 亦無今日即時價：買進閘門關閉、不採計 RISK_ON 加分（Task 217.1，語意於 Task 228 擴充）。 */
            boolean stale,
            String asOfDate,
            BigDecimal price,
            BigDecimal changePercent,
            BigDecimal monthlyMa,
            BigDecimal quarterlyMa,
            BigDecimal annualMa,
            BigDecimal kValue,
            BigDecimal dValue,
            String quarterlyConfirmation,
            String annualConfirmation,
            List<String> reasons,
            List<String> risks,
            /** regime 是否由 Redis 今日即時點位算出（相對於「已入庫完成日 K」）（Task 228）。 */
            boolean intraday,
            /** intraday=true 時為 Redis 即時價的 updatedAt（ISO 字串）；否則為 null（Task 228）。 */
            String liveUpdatedAt
    ) {}

    public record StockDecision(
            String stockCode,
            String stockName,
            String market,
            String assetClass,
            boolean distributionAdjusted,
            boolean held,
            String action,
            String actionLabel,
            Integer score,
            String counterTrendState,
            String counterTrendLabel,
            List<String> counterTrendReasons,
            List<String> counterTrendRisks,
            boolean dataComplete,
            BigDecimal price,
            BigDecimal changePercent,
            String priceUpdatedAt,
            String asOfDate,
            BigDecimal monthlyMa,
            BigDecimal quarterlyMa,
            BigDecimal annualMa,
            BigDecimal kValue,
            BigDecimal dValue,
            String monthlyConfirmation,
            String quarterlyConfirmation,
            String annualConfirmation,
            /**
             * 底層資產幣別對台幣的五年期分位（0–100）；台幣資產為 null（Requirement 47）。
             * 供畫面揭露「現在換匯貴不貴」——台幣計價的美債 ETF 其報價相當部分由匯率驅動
             * （實測 00719B 與 USD/TWD 近一年相關 0.9737），不揭露會讓使用者以為漲勢來自標的本身。
             */
            BigDecimal fxPercentile,
            /** 底層資產幣別（TWD/USD/GBP…），供前端判斷是否顯示匯率相關說明。 */
            String underlyingCurrency,
            List<String> reasons,
            List<String> risks,
            /**
             * KD 短線熱度：{@code OVERHEATED}／{@code ELEVATED}／{@code NORMAL}（Task 232）。
             *
             * <p>供收合列即可辨識——{@code reasons}／{@code risks} 只在展開後顯示，
             * 使用者於收合狀態看不出 K 已偏高。{@code OVERHEATED} 代表買進閘門已關閉
             * （動作降級為 HOLD／WATCH，分數不變）；<b>{@code ELEVATED} 純為揭露，
             * 不影響分數與動作</b>。</p>
             */
            String kdHeat
    ) {}
}
