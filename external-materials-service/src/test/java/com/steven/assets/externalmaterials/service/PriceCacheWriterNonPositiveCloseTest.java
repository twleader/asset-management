package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.PriceFetchClient.PriceResult;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Task 279：{@link PriceCacheWriter#writeVerifiedClose} 的非正收盤守門。
 *
 * <p>這條路徑<b>不經過 DB</b>：{@code ClosePersister.verify*CloseWithFinMind} 在
 * {@code upsertHistory}（已有守門）之後仍會呼叫本方法把值寫進 Redis live cache 並
 * {@code convertAndSend("price-update", …)}。只擋 DB 的話，前端讀 Redis 仍會看到股價 0。
 *
 * <p>守門刻意放在這一層而非各市場的解析分支：台股走 {@code parseTwClosingRow}、美股走
 * {@code getUsClosingPriceFromFinMind}、英股走 Yahoo verify，三者都匯流到本方法，
 * 一處守門即涵蓋三個市場。
 */
class PriceCacheWriterNonPositiveCloseTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final StockSourceQuery source = mock(StockSourceQuery.class);
    private final IntradayHighLowTracker hlTracker = mock(IntradayHighLowTracker.class);
    private final IntradayTickStore tickStore = mock(IntradayTickStore.class);
    private final TradingDateResolver tradingDateResolver = mock(TradingDateResolver.class);

    private final PriceCacheWriter writer =
            new PriceCacheWriter(redis, source, hlTracker, tickStore, tradingDateResolver);

    private static PriceResult result(String market, BigDecimal price) {
        return new PriceResult("006208", market, price, null, null, "FinMind",
                null, null, null, null, null, null, null, 0L);
    }

    @Test
    void zeroClose_isNotWrittenToRedis_andNotPublished() {
        writer.writeVerifiedClose(result("台股", BigDecimal.ZERO));
        verifyNothingWritten();
    }

    @Test
    void negativeClose_isNotWrittenToRedis_andNotPublished() {
        writer.writeVerifiedClose(result("台股", new BigDecimal("-2.5")));
        verifyNothingWritten();
    }

    @Test
    void nullClose_isNotWrittenToRedis_andNotPublished() {
        writer.writeVerifiedClose(result("台股", null));
        verifyNothingWritten();
    }

    /** 守門必須涵蓋美股／英股：它們的收盤校正同樣匯流到本方法。 */
    @Test
    void zeroClose_isRejectedForUsAndUkToo() {
        writer.writeVerifiedClose(result("美股", BigDecimal.ZERO));
        writer.writeVerifiedClose(result("英股", BigDecimal.ZERO));
        verifyNothingWritten();
    }

    @Test
    void rejection_doesNotThrow() {
        assertThatCode(() -> writer.writeVerifiedClose(result("台股", BigDecimal.ZERO)))
                .doesNotThrowAnyException();
    }

    private void verifyNothingWritten() {
        // 不寫 live cache、不加進 index、不推播——Redis 完全沒被碰
        verifyNoInteractions(redis);
        verify(redis, never()).convertAndSend(anyString(), any());
    }

    /** 正常收盤仍照寫（守門不得誤傷合法值）：至少會取得 ValueOperations 準備寫入。 */
    @Test
    void positiveClose_stillWrites() {
        ValueOperations<String, String> ops = mock(ValueOperations.class);
        org.mockito.Mockito.when(redis.opsForValue()).thenReturn(ops);
        writer.writeVerifiedClose(result("台股", new BigDecimal("39.60")));
        verify(redis, org.mockito.Mockito.atLeastOnce()).opsForValue();
    }
}
