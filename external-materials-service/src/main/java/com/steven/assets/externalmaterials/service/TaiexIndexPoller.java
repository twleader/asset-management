package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.MacroDataFetchClient;
import com.steven.assets.externalmaterials.client.MacroDataFetchClient.IndexIntradayPoint;
import com.steven.assets.externalmaterials.client.PriceFetchClient.PriceResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 台股大盤（0000）盤中即時點位 → Redis（Task 228，Requirement 43 修訂 V6）。
 *
 * 推翻 Task 217「零外部行情抓取」的決定：改為比照個股既有機制，把大盤盤中點位寫進
 * price:台股:0000（與個股 price:{market}:{code} 同一 schema），供 TradingRadarService／
 * TechnicalIndicatorService 透過既有 PriceQueryService.getLive 讀取。twse_index_daily_history
 * 的既有盤後批次（TwseIndexPoller）完全不受影響，仍是完成日 K 的唯一權威來源；本類別只餵
 * Redis 的盤中即時層。
 *
 * 抓取來源沿用既有 MacroDataFetchClient.fetchIndexIntraday("TWSE")（Yahoo ^TWII 5 分 K，
 * 「股市大盤查詢」頁「當日走勢」圖表已在用同一支方法，Requirement 18），不新增外部 API 依賴。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaiexIndexPoller {

    private static final String TAIEX_CODE = "0000";
    private static final String TW_MARKET = "台股";

    private final MacroDataFetchClient macroClient;
    private final PriceCacheWriter writer;
    private final StockSourceQuery source;
    private final MarketClock clock;

    /** 盤中輪詢：週一～五 09:00–13:30 Asia/Taipei，每 2 分鐘，與個股 PricePoller.scheduledTwIntradayUpdate 同頻率。 */
    @Scheduled(cron = "0 0/2 9-13 * * MON-FRI", zone = "Asia/Taipei")
    public void scheduledTaiexIntradayUpdate() {
        if (!clock.isTwMarketOpen()) return;
        try {
            updateOnce();
        } catch (Exception e) {
            log.warn("大盤盤中點位更新失敗: {}", e.getMessage());
        }
    }

    /** package-private：供測試直接呼叫，略過 cron/isTwMarketOpen 判斷。 */
    void updateOnce() {
        List<IndexIntradayPoint> points = macroClient.fetchIndexIntraday("TWSE");
        BigDecimal latestClose = null;
        for (int i = points.size() - 1; i >= 0; i--) {
            if (points.get(i).close() != null) {
                latestClose = points.get(i).close();
                break;
            }
        }
        // 查無有效點位：本輪不寫，保留 Redis 上一輪真實值（比照個股 TWSE z='-' 的既有慣例，
        // 不得以昨收或空值覆寫；見 PricePoller.updatePrices 對 Optional.empty() 的處理）。
        if (latestClose == null) return;

        List<StockSourceQuery.ClosePoint> prevRows = source.loadRecentTaiexCloses(1);
        BigDecimal previousClose = prevRows.isEmpty() ? null : prevRows.get(prevRows.size() - 1).close();

        PriceResult result = new PriceResult(
                TAIEX_CODE, TW_MARKET, latestClose,
                null, null,                 // change / changePct：由 PriceCacheWriter 依 previousClose 自算
                "TWSE指數(5m)",              // 含括號 → PriceCacheWriter 不會把這筆併入 IntradayTickStore
                "台股大盤",
                null, null,                 // buyPrice / sellPrice：指數無此概念
                null,                       // openPrice：MA/KD 判斷不吃這欄，留白不臆造
                previousClose,
                null, null,                 // highPrice / lowPrice：交給 PriceCacheWriter 內的 IntradayHighLowTracker 自動聚合
                null);                      // volume：指數無成交量概念
        writer.write(result, false);
    }
}
