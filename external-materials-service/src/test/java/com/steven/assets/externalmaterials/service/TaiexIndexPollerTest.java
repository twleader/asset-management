package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.MacroDataFetchClient;
import com.steven.assets.externalmaterials.client.MacroDataFetchClient.IndexIntradayPoint;
import com.steven.assets.externalmaterials.client.PriceFetchClient.PriceResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link TaiexIndexPoller} 大盤盤中即時點位抓取（Task 228，Requirement 43 修訂 V6）：
 * 抓不到有效點位時不覆寫 Redis（保留上一輪真實值，比照個股 TWSE z='-' 慣例）。
 */
class TaiexIndexPollerTest {

    private final MacroDataFetchClient macroClient = mock(MacroDataFetchClient.class);
    private final PriceCacheWriter writer = mock(PriceCacheWriter.class);
    private final StockSourceQuery source = mock(StockSourceQuery.class);
    private final MarketClock clock = mock(MarketClock.class);

    private final TaiexIndexPoller poller = new TaiexIndexPoller(macroClient, writer, source, clock);

    @Test
    void noValidPoints_doesNotWriteRedis() {
        when(macroClient.fetchIndexIntraday("TWSE")).thenReturn(List.of(
                new IndexIntradayPoint("2026-07-20T09:00:00", null),
                new IndexIntradayPoint("2026-07-20T09:05:00", null)));

        poller.updateOnce();

        verify(writer, never()).write(any(), anyBoolean());
    }

    @Test
    void emptyPoints_doesNotWriteRedis() {
        when(macroClient.fetchIndexIntraday("TWSE")).thenReturn(List.of());

        poller.updateOnce();

        verify(writer, never()).write(any(), anyBoolean());
    }

    @Test
    void latestNonNullClose_priceResultFieldsAreCorrect() {
        when(macroClient.fetchIndexIntraday("TWSE")).thenReturn(List.of(
                new IndexIntradayPoint("2026-07-20T09:00:00", new BigDecimal("20000.00")),
                new IndexIntradayPoint("2026-07-20T09:05:00", new BigDecimal("20050.00")),
                new IndexIntradayPoint("2026-07-20T09:10:00", null)));
        when(source.loadRecentTaiexCloses(1)).thenReturn(
                List.of(new StockSourceQuery.ClosePoint(LocalDate.of(2026, 7, 17), new BigDecimal("19800.00"))));

        org.mockito.ArgumentCaptor<PriceResult> captor = org.mockito.ArgumentCaptor.forClass(PriceResult.class);
        poller.updateOnce();
        verify(writer).write(captor.capture(), eq(false));

        PriceResult result = captor.getValue();
        assertThat(result.stockCode()).isEqualTo("0000");
        assertThat(result.market()).isEqualTo("台股");
        assertThat(result.price()).isEqualByComparingTo("20050.00");
        assertThat(result.previousClose()).isEqualByComparingTo("19800.00");
        assertThat(result.source()).contains("(");
    }
}
