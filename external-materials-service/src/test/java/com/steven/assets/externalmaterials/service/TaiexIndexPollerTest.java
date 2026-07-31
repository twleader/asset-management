package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.MacroDataFetchClient;
import com.steven.assets.externalmaterials.client.MacroDataFetchClient.DayQuote;
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
 * {@link TaiexIndexPoller} 大盤盤中即時點位抓取。
 *
 * <p>Task 228（Requirement 43 修訂 V6）：抓不到有效點位時不覆寫 Redis（保留上一輪真實值，
 * 比照個股 TWSE z='-' 慣例）。
 *
 * <p>Task 263：來源回傳橫跨最近五個交易日，「最新交易日」不等於「今日」——Yahoo 尚未產生今日
 * 第一根 5 分格時它就是昨日，而 cron 第一輪落在 09:00:00 整，故每個交易日開盤都會撞到一次。
 * 故須依點位所屬日期守門、當日 OHLC 取自來源、且不參與 IntradayHighLowTracker 的本地聚合。
 */
class TaiexIndexPollerTest {

    private final MacroDataFetchClient macroClient = mock(MacroDataFetchClient.class);
    private final PriceCacheWriter writer = mock(PriceCacheWriter.class);
    private final StockSourceQuery source = mock(StockSourceQuery.class);
    private final MarketClock clock = mock(MarketClock.class);

    private final TaiexIndexPoller poller = new TaiexIndexPoller(macroClient, writer, source, clock);

    private static final LocalDate TODAY = LocalDate.now(MarketClock.TW_ZONE);

    @Test
    void noValidClose_doesNotWriteRedis() {
        when(macroClient.fetchIndexIntradayDay("TWSE")).thenReturn(
                new DayQuote(TODAY, new BigDecimal("20000.00"), new BigDecimal("20100.00"),
                        new BigDecimal("19900.00"), null));

        poller.updateOnce();

        verify(writer, never()).write(any(), anyBoolean(), anyBoolean());
    }

    @Test
    void nullDayQuote_doesNotWriteRedis() {
        when(macroClient.fetchIndexIntradayDay("TWSE")).thenReturn(null);

        poller.updateOnce();

        verify(writer, never()).write(any(), anyBoolean(), anyBoolean());
    }

    /** Task 263 的核心守門：昨日點位不得被當成今日 tick 寫入。 */
    @Test
    void dayQuoteIsYesterday_doesNotWriteRedis() {
        when(macroClient.fetchIndexIntradayDay("TWSE")).thenReturn(
                new DayQuote(TODAY.minusDays(1), new BigDecimal("20000.00"), new BigDecimal("20100.00"),
                        new BigDecimal("19900.00"), new BigDecimal("20050.00")));

        poller.updateOnce();

        verify(writer, never()).write(any(), anyBoolean(), anyBoolean());
    }

    @Test
    void dayQuoteIsToday_writesOhlcFromSource() {
        when(macroClient.fetchIndexIntradayDay("TWSE")).thenReturn(
                new DayQuote(TODAY, new BigDecimal("20000.00"), new BigDecimal("20180.00"),
                        new BigDecimal("19870.00"), new BigDecimal("20050.00")));
        when(source.loadRecentTaiexCloses(2)).thenReturn(
                List.of(new StockSourceQuery.ClosePoint(TODAY.minusDays(4), new BigDecimal("19700.00")),
                        new StockSourceQuery.ClosePoint(TODAY.minusDays(1), new BigDecimal("19800.00"))));

        org.mockito.ArgumentCaptor<PriceResult> captor = org.mockito.ArgumentCaptor.forClass(PriceResult.class);
        poller.updateOnce();
        verify(writer).write(captor.capture(), eq(false), eq(false));

        PriceResult result = captor.getValue();
        assertThat(result.stockCode()).isEqualTo("0000");
        assertThat(result.market()).isEqualTo("台股");
        assertThat(result.price()).isEqualByComparingTo("20050.00");
        assertThat(result.openPrice()).isEqualByComparingTo("20000.00");
        assertThat(result.highPrice()).isEqualByComparingTo("20180.00");
        assertThat(result.lowPrice()).isEqualByComparingTo("19870.00");
        assertThat(result.previousClose()).isEqualByComparingTo("19800.00");
        assertThat(result.source()).contains("(");
        // 大盤無買賣盤口、無成交量定義
        assertThat(result.buyPrice()).isNull();
        assertThat(result.sellPrice()).isNull();
        assertThat(result.volume()).isNull();
    }

    /** aggregateHighLow=false：來源已給當日權威 high/low，不得再走 price:dayhl 的本地聚合。 */
    @Test
    void dayQuoteIsToday_writesWithAggregateHighLowFalse() {
        when(macroClient.fetchIndexIntradayDay("TWSE")).thenReturn(
                new DayQuote(TODAY, new BigDecimal("20000.00"), new BigDecimal("20180.00"),
                        new BigDecimal("19870.00"), new BigDecimal("20050.00")));
        when(source.loadRecentTaiexCloses(2)).thenReturn(
                List.of(new StockSourceQuery.ClosePoint(TODAY.minusDays(1), new BigDecimal("19800.00"))));

        poller.updateOnce();

        verify(writer).write(any(), eq(false), eq(false));
    }

    /**
     * 昨收取「嚴格早於點位日期」的最後一筆——不可取無條件最新一筆，否則 TwseIndexPoller 於 14:00
     * 寫入今日完成日 K 後，昨收會變成今日收盤。
     */
    @Test
    void previousClose_isStrictlyBeforePointDate() {
        when(macroClient.fetchIndexIntradayDay("TWSE")).thenReturn(
                new DayQuote(TODAY, new BigDecimal("20000.00"), new BigDecimal("20180.00"),
                        new BigDecimal("19870.00"), new BigDecimal("20050.00")));
        // 升冪：前一交易日、以及「與點位同日」的今日完成日 K（14:00 之後才會有）
        when(source.loadRecentTaiexCloses(2)).thenReturn(
                List.of(new StockSourceQuery.ClosePoint(TODAY.minusDays(1), new BigDecimal("19800.00")),
                        new StockSourceQuery.ClosePoint(TODAY, new BigDecimal("20050.00"))));

        org.mockito.ArgumentCaptor<PriceResult> captor = org.mockito.ArgumentCaptor.forClass(PriceResult.class);
        poller.updateOnce();
        verify(writer).write(captor.capture(), eq(false), eq(false));

        assertThat(captor.getValue().previousClose()).isEqualByComparingTo("19800.00");
    }
}
