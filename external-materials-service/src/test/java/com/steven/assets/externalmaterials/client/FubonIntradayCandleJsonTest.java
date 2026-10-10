package com.steven.assets.externalmaterials.client;

import java.time.Instant;
import java.time.LocalDate;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FubonIntradayCandleJsonTest {
    final LocalDate day=LocalDate.of(2026,10,9);
    final Instant now=Instant.parse("2026-10-09T05:40:00Z");
    @Test void all271MinutesIncludingClosingAuctionAndCumulativeAverageAreValidWireFacts() {
        var root=source();
        var read=FubonMarketJson.candles(root,"2330",day,now);
        assertThat(read.candles()).hasSize(271);
        assertThat(read.candles().getLast().candleAt()).isEqualTo(Instant.parse("2026-10-09T05:30:00Z"));
        assertThat(read.candles().getFirst().average()).isEqualByComparingTo("999");
    }
    @Test void nonPositiveAverageAndNonMinuteTimestampsRemainRejected() {
        var root=source();
        ((ObjectNode)root.get("candles").get(0)).put("average","0");
        final var badAverage=root;
        assertThatThrownBy(()->FubonMarketJson.candles(badAverage,"2330",day,now)).isInstanceOf(RuntimeException.class);
        root=source(); ((ObjectNode)root.get("candles").get(0)).put("candleAt","2026-10-09T01:00:01Z");
        final var badTimestamp=root;
        assertThatThrownBy(()->FubonMarketJson.candles(badTimestamp,"2330",day,now)).isInstanceOf(RuntimeException.class);
    }
    ObjectNode source() {
        ObjectNode root=(ObjectNode)FubonMarketJson.parse("""
                {"schemaVersion":1,"symbol":"2330","market":"台股","provider":"FUBON_SDK",
                 "sourceDate":"2026-10-09","observedAt":"2026-10-09T05:40:00Z","instrumentType":"EQUITY",
                 "exchange":"TWSE","sourceMarket":"TSE","timeframe":1,"status":"AVAILABLE","reason":null,"candles":[]}
                """);
        ArrayNode rows=(ArrayNode)root.get("candles");
        for(int n=0;n<=270;n++) rows.addObject().put("candleAt",Instant.parse("2026-10-09T01:00:00Z").plusSeconds(n*60L).toString())
                .put("open","10").put("high","11").put("low","9").put("close","10").put("volume","123").put("average","999");
        return root;
    }
}
