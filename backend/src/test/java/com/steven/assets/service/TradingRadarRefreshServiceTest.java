package com.steven.assets.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.security.CurrentUserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 249：手動「重新整理」先回補行情再重算。
 *
 * <p>核心不變式：任何降級路徑仍回傳 outcome，但不得偷渡完整 radar tree。</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TradingRadarRefreshServiceTest {

    @Test
    void explicitOwnerBackgroundPathNeverResolvesRequestScopeAndKeepsBothCooldownKeys() {
        when(marketDataService.isMarketOpenNow("台股")).thenReturn(true);
        when(priceQueryService.refreshTradingRadarPrices()).thenReturn(summary("{}"));

        assertEquals("FETCHED", service.refreshForOwner(42L).priceRefresh().outcome());

        org.mockito.Mockito.verifyNoInteractions(currentUserContext);
        verify(valueOps).setIfAbsent(eq("radar:refresh:cooldown:42"), eq("1"), any(Duration.class));
        verify(valueOps).setIfAbsent(eq("radar:refresh:cooldown:global"), eq("1"), any(Duration.class));
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock private PriceQueryService priceQueryService;
    @Mock private MarketDataService marketDataService;
    @Mock private CurrentUserContext currentUserContext;
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> valueOps;

    private TradingRadarRefreshService service;

    @BeforeEach
    void setUp() {
        service = new TradingRadarRefreshService(
                priceQueryService, marketDataService, currentUserContext, redis);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(currentUserContext.hasUser()).thenReturn(true);
        when(currentUserContext.getEffectiveUserId()).thenReturn(1L);
        cooldownAvailable();
    }

    /** 兩把冷卻鍵都可取得。 */
    private void cooldownAvailable() {
        when(valueOps.setIfAbsent(anyString(), eq("1"), any(Duration.class))).thenReturn(true);
    }

    private com.fasterxml.jackson.databind.JsonNode summary(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void 開盤中觸發回補後只回傳outcome() {
        when(marketDataService.isMarketOpenNow("台股")).thenReturn(true);
        when(priceQueryService.refreshTradingRadarPrices())
                .thenReturn(summary("{\"performed\":true,\"busy\":false,\"skippedPendingClose\":false}"));

        TradingRadarDto.RefreshResponse resp = service.refreshAndGet();

        verify(priceQueryService).refreshTradingRadarPrices();
        assertEquals("FETCHED", resp.priceRefresh().outcome());
        assertTrue(resp.priceRefresh().twMarketOpen());
    }

    @Test
    void 休市時回報已同步收盤價() {
        when(marketDataService.isMarketOpenNow("台股")).thenReturn(false);
        when(priceQueryService.refreshTradingRadarPrices())
                .thenReturn(summary("{\"performed\":true,\"busy\":false,\"skippedPendingClose\":false}"));

        TradingRadarDto.RefreshResponse resp = service.refreshAndGet();

        assertEquals("CLOSED_SYNCED", resp.priceRefresh().outcome());
    }

    @Test
    void 收盤未落檔的空窗回報跳過() {
        when(marketDataService.isMarketOpenNow("台股")).thenReturn(false);
        when(priceQueryService.refreshTradingRadarPrices())
                .thenReturn(summary("{\"performed\":true,\"busy\":false,\"skippedPendingClose\":true}"));

        TradingRadarDto.RefreshResponse resp = service.refreshAndGet();

        assertEquals("SKIPPED_PENDING_CLOSE", resp.priceRefresh().outcome());
    }

    @Test
    void owner冷卻中不呼叫外部也不組完整雷達() {
        when(marketDataService.isMarketOpenNow("台股")).thenReturn(true);
        when(valueOps.setIfAbsent(eq("radar:refresh:cooldown:1"), eq("1"), any(Duration.class)))
                .thenReturn(false);

        TradingRadarDto.RefreshResponse resp = service.refreshAndGet();

        verify(priceQueryService, never()).refreshTradingRadarPrices();
        assertEquals("COOLDOWN", resp.priceRefresh().outcome());
    }

    /** 全域鍵專屬案例：只測 owner 鍵不算覆蓋——多使用者輪流按正是靠全域鍵擋下的。 */
    @Test
    void 全域冷卻中不呼叫外部且釋放已取得的owner鍵() {
        when(marketDataService.isMarketOpenNow("台股")).thenReturn(true);
        when(valueOps.setIfAbsent(eq("radar:refresh:cooldown:1"), eq("1"), any(Duration.class)))
                .thenReturn(true);
        when(valueOps.setIfAbsent(eq("radar:refresh:cooldown:global"), eq("1"), any(Duration.class)))
                .thenReturn(false);

        TradingRadarDto.RefreshResponse resp = service.refreshAndGet();

        verify(priceQueryService, never()).refreshTradingRadarPrices();
        // 沒真的抓，就不該燒掉使用者自己的 30 秒冷卻
        verify(redis).delete("radar:refresh:cooldown:1");
        assertEquals("COOLDOWN", resp.priceRefresh().outcome());
    }

    @Test
    void 外部忙碌時降級為BUSY() {
        when(marketDataService.isMarketOpenNow("台股")).thenReturn(true);
        when(priceQueryService.refreshTradingRadarPrices())
                .thenReturn(summary("{\"performed\":false,\"busy\":true}"));

        assertEquals("BUSY", service.refreshAndGet().priceRefresh().outcome());
    }

    @Test
    void 外部逾時不上拋且只回outcome() {
        when(marketDataService.isMarketOpenNow("台股")).thenReturn(true);
        when(priceQueryService.refreshTradingRadarPrices())
                .thenThrow(new IllegalStateException("Timeout on blocking read for 30000 MILLISECONDS"));

        TradingRadarDto.RefreshResponse resp = service.refreshAndGet();

        assertEquals("TIMEOUT", resp.priceRefresh().outcome());
    }

    @Test
    void 外部失敗不上拋且只回outcome() {
        when(marketDataService.isMarketOpenNow("台股")).thenReturn(true);
        when(priceQueryService.refreshTradingRadarPrices())
                .thenThrow(new RuntimeException("connection refused"));

        TradingRadarDto.RefreshResponse resp = service.refreshAndGet();

        assertEquals("FAILED", resp.priceRefresh().outcome());
    }

    /** outcome 與 twMarketOpen 必須同源，否則會出現「抓了即時報價卻標成休市」的矛盾 payload。 */
    @Test
    void 開休市判斷不採用外部回傳值() {
        when(marketDataService.isMarketOpenNow("台股")).thenReturn(true);
        when(priceQueryService.refreshTradingRadarPrices())
                .thenReturn(summary("{\"performed\":true,\"busy\":false,\"twMarketOpen\":false}"));

        TradingRadarDto.RefreshResponse resp = service.refreshAndGet();

        assertEquals("FETCHED", resp.priceRefresh().outcome());
        assertTrue(resp.priceRefresh().twMarketOpen());
    }

    /** Redis 抖動不得讓功能永久失效。 */
    @Test
    void 冷卻閘門讀寫失敗時放行() {
        when(marketDataService.isMarketOpenNow("台股")).thenReturn(true);
        doThrow(new RuntimeException("redis down"))
                .when(valueOps).setIfAbsent(anyString(), anyString(), any(Duration.class));
        when(priceQueryService.refreshTradingRadarPrices())
                .thenReturn(summary("{\"performed\":true,\"busy\":false}"));

        assertEquals("FETCHED", service.refreshAndGet().priceRefresh().outcome());
    }
}
