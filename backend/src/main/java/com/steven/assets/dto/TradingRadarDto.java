package com.steven.assets.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 今日交易雷達（Requirement 43）純讀 response。
 *
 * <p>所有分數／建議皆為 {@code TW_RULES_V9} 即時計算的衍生值，不入庫；
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
     * 走勢圖指標選單（Task 262）同一組值的雷達版（Task 281）。
     *
     * <p><b>純揭露：不參與評分</b>——不進 {@code StockInput}／{@code MarketInput}，不影響
     * {@code action}／{@code score}／{@code regime}／{@code buyGate}／{@code kdHeat}／{@code timingState}，
     * 故 {@code RULE_VERSION} 不升版（理由是「規則集本身未變」，<b>不援引</b> Task 249 的
     * 「輸出完全相同」條件——本次輸出結構確有變化）。</p>
     *
     * <p><b>價基與同一列的 {@code kValue}／{@code dValue} 相同</b>：個股為還原權息序列、
     * 大盤為指數日線序列，與雙擊該列開啟的走勢圖（原始價基）<b>刻意不同</b>，
     * 凡視窗內有配息／除權的個股必然對不上，這是既有鐵則「禁止混用原始／還原價」的結果。</p>
     *
     * <p>暖機／視窗不足的欄位為 {@code null}，<b>不得以 0 充數</b>。全部 2 位小數。</p>
     */
    public record ExtendedIndicators(
            BigDecimal j9,
            BigDecimal k3d2,
            BigDecimal rsv,
            BigDecimal ema12,
            BigDecimal ema26,
            BigDecimal dif,
            BigDecimal macd,
            BigDecimal osc,
            BigDecimal rsi5,
            BigDecimal rsi10,
            BigDecimal bias10,
            BigDecimal bias20,
            BigDecimal b10b20,
            BigDecimal wr9
    ) {}

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
            String quoteStatus,
            /** 週線 MA5（Task 265）；僅供顯示，不參與評分。 */
            BigDecimal weeklyMa,
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
            String liveUpdatedAt,
            /** 走勢圖指標選單同一組值（Task 281）；純揭露、只進匯出檔，畫面不顯示。 */
            ExtendedIndicators extendedIndicators
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
            String quoteStatus,
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
            String kdHeat,
            /** 進場時機（Task 264）；供收合列辨識，並對動作做雙向覆寫。 */
            String timingState,
            String timingLabel,
            /** 現價對季線的乖離率（%），V9 的均值回歸主因子。 */
            BigDecimal ma60BiasPercent,
            /** 52 週相對位置 [0,1]（已 clamp）。 */
            BigDecimal week52Position,
            /** 週線 MA5（Task 265）；僅供顯示，不參與評分與買進閘門。 */
            BigDecimal weeklyMa,
            /** ETF 折溢價（%）；非 ETF 為 null，畫面不得顯示為 0。 */
            BigDecimal etfPremiumPct,
            /** ETF 折溢價的自身歷史分位（0–100）；樣本不足或非 ETF 為 null。 */
            BigDecimal etfPremiumPercentile,
            /** 走勢圖指標選單同一組值（Task 281）；純揭露、只進匯出檔，畫面不顯示。 */
            ExtendedIndicators extendedIndicators
    ) {}
}
