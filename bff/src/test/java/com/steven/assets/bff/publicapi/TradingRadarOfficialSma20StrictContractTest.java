package com.steven.assets.bff.publicapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradingRadarOfficialSma20StrictContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private ObjectNode detail() throws Exception {
        return (ObjectNode) JSON.readTree(PublicContractJsonFixtures.TRADING_RADAR_STOCK_DETAIL);
    }

    private JsonNode decode(JsonNode body, StrictPublicJsonResponse.Contract contract) {
        return StrictPublicJsonResponse.decode(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(body.toString()).build(),
                contract, () -> new IllegalArgumentException("invalid")).block();
    }

    private void invalid(ObjectNode body) {
        assertThatThrownBy(() -> decode(body, StrictPublicJsonResponse.Contract.TRADING_RADAR_STOCK_DETAIL))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void validTypedChildrenAreAcceptedWithoutRecalculation() throws Exception {
        ObjectNode body = detail();
        ObjectNode stock = (ObjectNode) body.get("stock");
        stock.set("officialSma20Verification", JSON.readTree("""
                {"status":"CONFLICT","sourceDate":"2026-10-02","reason":"OFFICIAL_LOCAL_SMA20_CONFLICT",
                 "officialValue":101,"localValue":100,"difference":1,"gateApplied":true}
                """));
        stock.set("priceReference", JSON.readTree("""
                {"buyLower":90.00,"buyUpper":100.01,"sellLower":100.00,"sellUpper":110.01,
                 "asOfDate":"2026-10-02","applicableSide":"NONE"}
                """));
        JsonNode parsed = decode(body, StrictPublicJsonResponse.Contract.TRADING_RADAR_STOCK_DETAIL);
        assertThat(parsed.at("/stock/officialSma20Verification/status").asText()).isEqualTo("CONFLICT");
        assertThat(parsed.at("/stock/priceReference/buyLower").decimalValue()).isEqualByComparingTo("90");
    }

    @Test void shapeDatesAndPositivePriceAreStrict() throws Exception {
        ObjectNode body = detail();
        ((ObjectNode) body.get("stock")).remove("officialSma20Verification"); invalid(body);
        body = detail(); ((ObjectNode) body.get("stock")).remove("priceReference"); invalid(body);
        body = detail(); ((ObjectNode) body.get("stock")).set("officialSma20Verification", JSON.readTree("""
                {"status":"CONFIRMED","sourceDate":"2026-10-02","reason":"RAW_SMA20_CONFIRMED",
                 "officialValue":100,"localValue":100,"difference":0,"gateApplied":true}
                """)); invalid(body);
        body = detail(); ((ObjectNode) body.get("stock")).set("priceReference", JSON.readTree("""
                {"buyLower":0,"buyUpper":100,"sellLower":100,"sellUpper":110,
                 "asOfDate":"2026-10-02","applicableSide":"BUY"}
                """)); invalid(body);
    }

    @Test void compactListDoesNotAcceptNewDetailFields() throws Exception {
        ObjectNode list = (ObjectNode) JSON.readTree(PublicContractJsonFixtures.TRADING_RADAR_LIST_WITH_NULLABLE_STOCK);
        ((ObjectNode) list.get("stocks").get(0)).putNull("officialSma20Verification");
        assertThatThrownBy(() -> decode(list, StrictPublicJsonResponse.Contract.TRADING_RADAR_LIST))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
