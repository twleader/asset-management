package com.steven.assets.service;

import com.steven.assets.model.StockPriceHistory;
import com.steven.assets.repository.StockPriceHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Task 428: Redis misses use the already-bounded exact-pair series, never a hidden repository N+1. */
@ExtendWith(MockitoExtension.class)
class PriceQueryServiceBatchTest {
    @Mock private StringRedisTemplate redis;
    @Mock private ValueOperations<String, String> values;
    @Mock private StockPriceHistoryRepository historyRepository;
    @Mock private MarketDataService marketDataService;

    @Test
    void quoteBatch以完整market加code鍵讀取且miss不再查repository() {
        when(redis.opsForValue()).thenReturn(values);
        when(values.multiGet(anyList())).thenReturn(Arrays.asList(
                "{\"stockCode\":\"2330\",\"market\":\"台股\",\"price\":100,\"tradingDate\":\"2026-09-12\"}",
                null));
        PriceQueryService service = new PriceQueryService(redis, historyRepository, "http://unused",
                marketDataService, Clock.systemUTC());
        PriceQueryService.PriceKey tw = new PriceQueryService.PriceKey("2330", "台股");
        PriceQueryService.PriceKey us = new PriceQueryService.PriceKey("2330", "美股");
        Map<PriceQueryService.PriceKey, Optional<PriceQueryService.LivePrice>> result = service.getLiveBatch(
                Set.of(tw, us), Map.of(tw, List.of(), us, List.of(
                        history("2330", "美股", "2026-09-11", "90"),
                        history("2330", "美股", "2026-09-10", "80"))));

        assertThat(result.get(tw)).isPresent();
        assertThat(result.get(us)).isPresent();
        assertThat(result.get(us).orElseThrow().price()).isEqualByComparingTo("90");
        verify(historyRepository, never()).findRecentN(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt());
    }

    private static StockPriceHistory history(String code, String market, String date, String close) {
        return StockPriceHistory.builder().stockCode(code).market(market).tradingDate(LocalDate.parse(date))
                .closePrice(new BigDecimal(close)).build();
    }
}
