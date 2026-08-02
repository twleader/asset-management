package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.client.PriceFetchClient.HistoricalBar;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 279：FinMind {@code TaiwanStockPrice} 對「當日無整股成交」回 {@code close: 0.0}
 * （交易所本身回 {@code '--'}），舊版只擋 null，導致 175 列 0 元收盤被當成真實收盤寫入
 * {@code stock_price_history}，單一列即讓當日乖離率變成 −100% 並污染 MA60 與波動度。
 *
 * <p>本測試的資料取自實測回應：006208 於 2016-08-03（完全無成交）與 2017-03-28
 * （成交 113 股、金額 4,859、3 筆，OHLC 仍為 {@code '--'}，即當日只有零股／盤後成交）。
 */
class PriceFetchClientTwHistoricalZeroCloseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void zeroCloseRows_areSkipped_andNormalRowsAllKept() throws Exception {
        // 兩種髒列型態都要有樣本：volume=0 與 volume>0，證明判準是 close 而非 volume
        JsonNode data = MAPPER.readTree("""
                [
                  {"date":"2016-08-02","Trading_Volume":3000,"open":39.84,"max":39.84,"min":39.58,"close":39.6},
                  {"date":"2016-08-03","Trading_Volume":0,"open":0.0,"max":0.0,"min":0.0,"close":0.0},
                  {"date":"2016-08-04","Trading_Volume":1000,"open":39.22,"max":39.22,"min":39.22,"close":39.22},
                  {"date":"2017-03-28","Trading_Volume":113,"open":0.0,"max":0.0,"min":0.0,"close":0.0},
                  {"date":"2017-03-29","Trading_Volume":5136,"open":43.93,"max":43.93,"min":43.73,"close":43.73}
                ]
                """);

        List<HistoricalBar> bars = PriceFetchClient.parseTwHistoricalRows(data, "006208");

        // 兩列非正收盤被跳過；三列正常的全部保留（不得整批丟棄或提前 return）
        assertThat(bars).hasSize(3);
        assertThat(bars).extracting(HistoricalBar::tradingDate)
                .containsExactly(
                        LocalDate.of(2016, 8, 2),
                        LocalDate.of(2016, 8, 4),
                        LocalDate.of(2017, 3, 29));
        assertThat(bars).allSatisfy(b ->
                assertThat(b.close().signum()).isPositive());
    }

    @Test
    void zeroCloseWithNonZeroVolume_isStillSkipped() throws Exception {
        // 48/175 列屬此型：有成交量但交易所仍不發布 OHLC。用 volume 當判準會漏掉它們。
        JsonNode data = MAPPER.readTree("""
                [{"date":"2017-06-12","Trading_Volume":1240,"open":0.0,"max":0.0,"min":0.0,"close":0.0}]
                """);
        assertThat(PriceFetchClient.parseTwHistoricalRows(data, "006208")).isEmpty();
    }

    @Test
    void negativeAndMissingClose_areSkipped() throws Exception {
        JsonNode data = MAPPER.readTree("""
                [
                  {"date":"2020-01-02","Trading_Volume":100,"close":-3.5},
                  {"date":"2020-01-03","Trading_Volume":100},
                  {"date":"2020-01-06","Trading_Volume":100,"close":12.5}
                ]
                """);
        List<HistoricalBar> bars = PriceFetchClient.parseTwHistoricalRows(data, "1234");
        assertThat(bars).hasSize(1);
        assertThat(bars.get(0).tradingDate()).isEqualTo(LocalDate.of(2020, 1, 6));
    }

    @Test
    void allRowsPositive_nothingDropped() throws Exception {
        JsonNode data = MAPPER.readTree("""
                [
                  {"date":"2020-01-02","Trading_Volume":100,"open":10.0,"max":11.0,"min":9.5,"close":10.5},
                  {"date":"2020-01-03","Trading_Volume":200,"open":10.5,"max":12.0,"min":10.4,"close":11.8}
                ]
                """);
        assertThat(PriceFetchClient.parseTwHistoricalRows(data, "1234")).hasSize(2);
    }
}
