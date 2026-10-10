package com.steven.assets.bff.publicapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.*;

class TradingRadarIntradayCandleStrictContractTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static ObjectNode receipt() throws Exception {
        return (ObjectNode) JSON.readTree("""
                {"status":"CONFIRMED","reason":"COMPLETED_PRICE_VOLUME_CONFIRMED","sourceDate":"2026-10-08",
                 "observedAt":"2026-10-08T01:13:59Z","lastCompletedAt":"2026-10-08T01:12:00Z",
                 "fiveMinuteAt":"2026-10-08T01:10:00Z","oneMinuteAt":"2026-10-08T01:12:00Z",
                 "aggregationSource":"LOCAL_AGGREGATED_FUBON_1M"}
                """);
    }
    JsonNode decode(JsonNode body, StrictPublicJsonResponse.Contract contract) {
        return StrictPublicJsonResponse.decode(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(body.toString()).build(),
                contract, () -> new IllegalArgumentException("invalid")).block();
    }
    @Test void publicFullAndCompactShareExactlyTheSameNullableTypedReceipt() throws Exception {
        var full = (ObjectNode) JSON.readTree(PublicContractJsonFixtures.TRADING_RADAR_STOCK_DETAIL);
        var compact = (ObjectNode) JSON.readTree(PublicContractJsonFixtures.TRADING_RADAR_LIST_WITH_NULLABLE_STOCK);
        ((ObjectNode) full.get("stock")).set("intradayCandleConfirmation", receipt());
        ((ObjectNode) compact.get("stocks").get(0)).set("intradayCandleConfirmation", receipt());
        var one = decode(full, StrictPublicJsonResponse.Contract.TRADING_RADAR_STOCK_DETAIL).at("/stock/intradayCandleConfirmation");
        var two = decode(compact, StrictPublicJsonResponse.Contract.TRADING_RADAR_LIST).at("/stocks/0/intradayCandleConfirmation");
        assertThat(one).isEqualTo(two);
        ((ObjectNode) full.get("stock")).putNull("intradayCandleConfirmation");
        assertThat(decode(full, StrictPublicJsonResponse.Contract.TRADING_RADAR_STOCK_DETAIL)
                .at("/stock/intradayCandleConfirmation").isNull()).isTrue();
    }
    @Test void shapeStatusDatesSourceAndCompletedEndpointsFailClosed() throws Exception {
        var invalid = new ArrayList<ObjectNode>();
        var value = receipt(); value.put("extra", true); invalid.add(value);
        value = receipt(); value.remove("status"); invalid.add(value);
        value = receipt(); value.put("status", "AVAILABLE"); invalid.add(value);
        value = receipt(); value.put("sourceDate", "2026-10-99"); invalid.add(value);
        value = receipt(); value.put("aggregationSource", "FUBON_OFFICIAL_5M"); invalid.add(value);
        value = receipt(); value.put("fiveMinuteAt", "2026-10-08T01:11:00Z"); invalid.add(value);
        value = receipt(); value.putNull("observedAt"); invalid.add(value);
        value = receipt(); value.put("oneMinuteAt", "2026-10-08T01:11:00Z"); invalid.add(value);
        value = receipt(); value.put("status", "NOT_APPLICABLE"); invalid.add(value);
        for (var bad : invalid) {
            var full = (ObjectNode) JSON.readTree(PublicContractJsonFixtures.TRADING_RADAR_STOCK_DETAIL);
            ((ObjectNode)full.get("stock")).set("intradayCandleConfirmation", bad);
            assertThatThrownBy(() -> decode(full, StrictPublicJsonResponse.Contract.TRADING_RADAR_STOCK_DETAIL)).isInstanceOf(IllegalArgumentException.class);
        }
        var missing=(ObjectNode)JSON.readTree(PublicContractJsonFixtures.TRADING_RADAR_STOCK_DETAIL);
        ((ObjectNode)missing.get("stock")).remove("intradayCandleConfirmation");
        assertThatThrownBy(()->decode(missing,StrictPublicJsonResponse.Contract.TRADING_RADAR_STOCK_DETAIL)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void confirmedAndWaitRequireWholeMinuteEndpointsOnBothPublicProjections() throws Exception {
        for (String status : java.util.List.of("CONFIRMED", "WAIT")) {
            var confirmation = receipt();
            confirmation.put("status", status);
            confirmation.put("reason", status.equals("WAIT") ? "ONE_MINUTE_WEAKENING" : "COMPLETED_PRICE_VOLUME_CONFIRMED");
            for (String endpoint : java.util.List.of("01:12:00Z", "01:12:30Z")) {
                confirmation.put("lastCompletedAt", "2026-10-08T" + endpoint);
                confirmation.put("oneMinuteAt", "2026-10-08T" + endpoint);
                var full = (ObjectNode) JSON.readTree(PublicContractJsonFixtures.TRADING_RADAR_STOCK_DETAIL);
                var compact = (ObjectNode) JSON.readTree(PublicContractJsonFixtures.TRADING_RADAR_LIST_WITH_NULLABLE_STOCK);
                ((ObjectNode) full.get("stock")).set("intradayCandleConfirmation", confirmation);
                ((ObjectNode) compact.get("stocks").get(0)).set("intradayCandleConfirmation", confirmation);
                if (endpoint.equals("01:12:00Z")) {
                    assertThat(decode(full, StrictPublicJsonResponse.Contract.TRADING_RADAR_STOCK_DETAIL)
                            .at("/stock/intradayCandleConfirmation/status").asText()).isEqualTo(status);
                    assertThat(decode(compact, StrictPublicJsonResponse.Contract.TRADING_RADAR_LIST)
                            .at("/stocks/0/intradayCandleConfirmation/status").asText()).isEqualTo(status);
                } else {
                    assertThatThrownBy(() -> decode(full, StrictPublicJsonResponse.Contract.TRADING_RADAR_STOCK_DETAIL))
                            .isInstanceOf(IllegalArgumentException.class);
                    assertThatThrownBy(() -> decode(compact, StrictPublicJsonResponse.Contract.TRADING_RADAR_LIST))
                            .isInstanceOf(IllegalArgumentException.class);
                }
            }
        }
    }
}
