package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.client.PriceFetchClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PriceCacheWriterTest {

    private static final LocalDate LATEST = LocalDate.of(2026, 7, 23);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private StockSourceQuery source;
    /** Task 263：write(..., aggregateHighLow) 的兩條路徑要能 verify，故提為欄位（原為建構時的匿名 mock）。 */
    private IntradayHighLowTracker hlTracker;
    private PriceCacheWriter writer;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        SetOperations<String, String> sets = mock(SetOperations.class);
        source = mock(StockSourceQuery.class);
        hlTracker = mock(IntradayHighLowTracker.class);
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(sets);
        when(source.findMaxTradingDate(anyString(), anyString())).thenReturn(Optional.of(LATEST));
        // trading date 的決策已抽到 TradingDateResolver（其自身邏輯由 MarketClock 驅動，另行涵蓋）；
        // 這裡固定回 LATEST，讓本測試專注在 Redis payload 與 previousClose 的組裝。
        TradingDateResolver tradingDateResolver = mock(TradingDateResolver.class);
        when(tradingDateResolver.resolve(anyString(), anyString())).thenReturn(LATEST);
        writer = new PriceCacheWriter(redis, source,
                hlTracker, mock(IntradayTickStore.class), tradingDateResolver);
    }

    @Test
    void coldCache_usesPreviousDbCloseAndCalculatesChange() throws Exception {
        when(values.get("price:台股:009804")).thenReturn(null);
        when(source.findPreviousCloseBefore("009804", "台股", LATEST))
                .thenReturn(Optional.of(new BigDecimal("21.20")));

        JsonNode payload = syncAndCapture("009804", "21.70");

        assertThat(payload.path("previousClose").decimalValue()).isEqualByComparingTo("21.20");
        assertThat(payload.path("priceChange").decimalValue()).isEqualByComparingTo("0.50");
        assertThat(payload.path("changePercent").decimalValue()).isEqualByComparingTo("2.358491");
    }

    @Test
    void flatClose_writesNumericZeros() throws Exception {
        when(source.findPreviousCloseBefore("2885", "台股", LATEST))
                .thenReturn(Optional.of(new BigDecimal("63.10")));

        JsonNode payload = syncAndCapture("2885", "63.10");

        assertThat(payload.path("priceChange").decimalValue()).isEqualByComparingTo("0");
        assertThat(payload.path("changePercent").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    void staleRedisPreviousClose_isIgnoredInFavorOfDb() throws Exception {
        when(values.get("price:台股:2885")).thenReturn(
                "{\"tradingDate\":\"2026-07-22\",\"previousClose\":59.90}");
        when(source.findPreviousCloseBefore("2885", "台股", LATEST))
                .thenReturn(Optional.of(new BigDecimal("61.30")));

        JsonNode payload = syncAndCapture("2885", "63.10");

        assertThat(payload.path("previousClose").decimalValue()).isEqualByComparingTo("61.30");
        assertThat(payload.path("priceChange").decimalValue()).isEqualByComparingTo("1.80");
    }

    @Test
    void noPreviousHistory_omitsDerivedFields() throws Exception {
        when(source.findPreviousCloseBefore("NEW", "台股", LATEST)).thenReturn(Optional.empty());

        JsonNode payload = syncAndCapture("NEW", "10.00");

        assertThat(payload.has("previousClose")).isFalse();
        assertThat(payload.has("priceChange")).isFalse();
        assertThat(payload.has("changePercent")).isFalse();
        assertThat(payload.path("price").decimalValue()).isEqualByComparingTo("10.00");
    }

    /**
     * 個股路徑（兩參數版 → aggregateHighLow=true）：外部值與當日本地聚合取 max(high)/min(low)。
     * 動機見 {@link IntradayHighLowTracker}：NASDAQ info API 對 ETF 的 keyStats 為 null，
     * 沒有這層聚合的話 VOO/VT 等的 high/low 永遠抓不到。
     */
    @Test
    void write_defaultPath_mergesWithLocalAggregate() throws Exception {
        when(hlTracker.observe(anyString(), anyString(), any(), any()))
                .thenReturn(new IntradayHighLowTracker.HighLow(
                        new BigDecimal("12.50"), new BigDecimal("11.00")));

        JsonNode payload = writeAndCapture(priceResult("2330", "12.00", "12.20", "11.50"), null);

        verify(hlTracker).observe(anyString(), anyString(), any(), any());
        assertThat(payload.path("highPrice").decimalValue()).isEqualByComparingTo("12.50");  // 聚合值較高
        assertThat(payload.path("lowPrice").decimalValue()).isEqualByComparingTo("11.00");   // 聚合值較低
    }

    /**
     * 大盤路徑（Task 263，aggregateHighLow=false）：來源已給當日權威 high/low，
     * 不得碰 price:dayhl:* —— 聚合是 max/min 的單向累積，誤入的極值無法被後續正確值修正。
     */
    @Test
    void write_noAggregatePath_usesResultHighLowAndSkipsTracker() throws Exception {
        JsonNode payload = writeAndCapture(priceResult("0000", "20050.00", "20180.00", "19870.00"), false);

        verify(hlTracker, never()).observe(any(), any(), any(), any());
        assertThat(payload.path("highPrice").decimalValue()).isEqualByComparingTo("20180.00");
        assertThat(payload.path("lowPrice").decimalValue()).isEqualByComparingTo("19870.00");
    }

    private static PriceFetchClient.PriceResult priceResult(String code, String price, String high, String low) {
        return new PriceFetchClient.PriceResult(
                code, "台股", new BigDecimal(price),
                null, null, "TWSE", "測試", null, null, null,
                new BigDecimal("10.00"), new BigDecimal(high), new BigDecimal(low), null);
    }

    /** aggregateHighLow 為 null 時走兩參數版（既有呼叫端形狀）。 */
    private JsonNode writeAndCapture(PriceFetchClient.PriceResult result, Boolean aggregateHighLow) throws Exception {
        if (aggregateHighLow == null) {
            writer.write(result, false);
        } else {
            writer.write(result, false, aggregateHighLow);
        }
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(values).set(anyString(), json.capture(), any());
        return MAPPER.readTree(json.getValue());
    }

    private JsonNode syncAndCapture(String code, String close) throws Exception {
        writer.syncClosedFromDb(code, "台股", new BigDecimal(close));
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(values).set(anyString(), json.capture(), any());
        return MAPPER.readTree(json.getValue());
    }
}
