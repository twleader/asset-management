package com.steven.assets.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.security.CurrentUserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 今日交易雷達手動「重新整理」：先同步回補台股行情，再走既有純讀重算（Task 249，Requirement 43 修訂）。
 *
 * <p>本服務推翻了 Requirement 43 原本的「不得因重新整理而觸發外部行情抓取」，
 * <b>但只限使用者明確按下按鈕的這一條路徑</b>：{@code GET /api/trading-radar} 行為完全不變，
 * SSE 盤中自動更新仍走 GET——否則抓取寫 Redis 會觸發 {@code price-update} 事件、再觸發下一輪抓取，
 * 形成自我餵食迴圈。仍不呼叫任何 AI／LLM API，也不觸發新聞爬蟲。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradingRadarRefreshService {

    private static final String TW_MARKET = "台股";
    private static final long COOLDOWN_SECONDS = 30;
    private static final String COOLDOWN_KEY_PREFIX = "radar:refresh:cooldown:";
    private static final String COOLDOWN_GLOBAL_KEY = COOLDOWN_KEY_PREFIX + "global";

    private final TradingRadarService tradingRadarService;
    private final PriceQueryService priceQueryService;
    private final MarketDataService marketDataService;
    private final CurrentUserContext currentUserContext;
    private final StringRedisTemplate redis;

    public TradingRadarDto.RefreshResponse refreshAndGet() {
        long t0 = System.nanoTime();
        // 開／休市判斷在呼叫 external 之前求值一次、全程沿用（含 COOLDOWN 分支）。
        // 若等 external 回來（最長 30 秒）才算，13:29:55 按下會得到「抓了即時報價卻標成休市」的矛盾 payload。
        boolean twOpen = marketDataService.isMarketOpenNow(TW_MARKET);

        String outcome = acquireCooldown()
                ? refreshPrices(twOpen)
                : "COOLDOWN";

        TradingRadarDto.Response radar = tradingRadarService.get();
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        return new TradingRadarDto.RefreshResponse(
                radar, new TradingRadarDto.PriceRefresh(outcome, twOpen, elapsedMs));
    }

    /**
     * 雙鍵冷卻：per-owner ＋ 全域，兩把都取得才抓。
     *
     * <p>全域鍵不可省：回補清單是全庫的，只有 per-owner 鍵時「A 按完 5 秒後 B 按」不會被擋，
     * 而 external 端的 Semaphore 只擋併發、不擋速率。刻意不寫成 {@code && } 短路——短路後
     * owner 鍵已寫入卻沒真的抓，該使用者的冷卻會被無故燒掉。</p>
     *
     * <p>Redis 本身失敗時 fail-open（視為取得），不因 Redis 抖動就永遠不抓。</p>
     */
    private boolean acquireCooldown() {
        String ownerKey = COOLDOWN_KEY_PREFIX
                + (currentUserContext.hasUser() ? currentUserContext.getEffectiveUserId() : "anonymous");
        Duration ttl = Duration.ofSeconds(COOLDOWN_SECONDS);
        try {
            if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(ownerKey, "1", ttl))) {
                return false;
            }
            if (Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(COOLDOWN_GLOBAL_KEY, "1", ttl))) {
                return true;
            }
            redis.delete(ownerKey);  // 沒真的抓，就不燒掉自己的冷卻
            return false;
        } catch (Exception e) {
            log.warn("交易雷達回補冷卻閘門讀寫失敗，本次放行：{}", e.toString());
            return true;
        }
    }

    /** 逾時與失敗一律降級為 outcome，不上拋、不回 5xx——使用者至少要拿到以現有 Redis 值重算的結果。 */
    private String refreshPrices(boolean twOpen) {
        try {
            JsonNode summary = priceQueryService.refreshTradingRadarPrices();
            if (summary != null && summary.path("busy").asBoolean(false)) {
                return "BUSY";
            }
            if (summary != null && summary.path("skippedPendingClose").asBoolean(false)) {
                return "SKIPPED_PENDING_CLOSE";
            }
            return twOpen ? "FETCHED" : "CLOSED_SYNCED";
        } catch (IllegalStateException e) {
            // Reactor 的 block(Duration) 逾時拋此型別（"Timeout on blocking read for ..."）
            log.warn("交易雷達行情回補逾時：{}", e.toString());
            return "TIMEOUT";
        } catch (Exception e) {
            log.warn("交易雷達行情回補失敗：{}", e.toString());
            return "FAILED";
        }
    }
}
