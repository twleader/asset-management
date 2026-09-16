package com.steven.assets.bff.publicapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R156: full detail extends its strict shape; compact rows keep their existing shape. */
class TradingRadarBollingerStrictContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String BANDS = """
            {"asOfDate":"2026-09-15","period":20,"standardDeviationMultiplier":2,
            "middleBand":100,"upperBand":110.5,"lowerBand":89.5,"percentB":0.75,"bandWidthPercent":21}
            """;

    private ObjectNode detail() throws Exception {
        return (ObjectNode) JSON.readTree(PublicContractJsonFixtures.TRADING_RADAR_STOCK_DETAIL);
    }
    private ObjectNode bands(ObjectNode detail) throws Exception {
        ObjectNode bands = (ObjectNode) JSON.readTree(BANDS);
        ((ObjectNode) detail.get("stock")).set("bollinger", bands);
        return bands;
    }
    private JsonNode decode(JsonNode body, StrictPublicJsonResponse.Contract contract) {
        return StrictPublicJsonResponse.decode(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(body.toString()).build(),
                contract, () -> new IllegalArgumentException("sanitized invalid payload")).block();
    }
    private void rejected(ObjectNode body) {
        assertThatThrownBy(() -> decode(body, StrictPublicJsonResponse.Contract.TRADING_RADAR_STOCK_DETAIL))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("sanitized invalid payload");
    }

    @Test void wholeObjectMayBeNull() throws Exception {
        assertThat(decode(detail(), StrictPublicJsonResponse.Contract.TRADING_RADAR_STOCK_DETAIL)
                .at("/stock/bollinger").isNull()).isTrue();
    }
    @Test void validBandsKeepDecimalsAndDetailKeys() throws Exception {
        ObjectNode body = detail(); bands(body);
        JsonNode decoded = decode(body, StrictPublicJsonResponse.Contract.TRADING_RADAR_STOCK_DETAIL);
        assertThat(decoded.at("/stock/bollinger/upperBand").decimalValue()).isEqualByComparingTo("110.5");
        assertThat(decoded.get("stock").size()).isEqualTo(75);
    }
    @ParameterizedTest @ValueSource(strings = {"asOfDate", "middleBand", "upperBand", "lowerBand", "percentB", "bandWidthPercent"})
    void documentedNullableValuesRemainAccepted(String key) throws Exception {
        ObjectNode body = detail(); bands(body).putNull(key);
        assertThat(decode(body, StrictPublicJsonResponse.Contract.TRADING_RADAR_STOCK_DETAIL)
                .at("/stock/bollinger/" + key).isNull()).isTrue();
    }
    @ParameterizedTest @ValueSource(strings = {"asOfDate", "period", "standardDeviationMultiplier", "middleBand", "upperBand", "lowerBand", "percentB", "bandWidthPercent"})
    void eachRequiredKeyMustBePresent(String key) throws Exception {
        ObjectNode body = detail(); bands(body).remove(key); rejected(body);
    }
    @ParameterizedTest @ValueSource(strings = {"asOfDate", "period", "standardDeviationMultiplier", "middleBand", "upperBand", "lowerBand", "percentB", "bandWidthPercent"})
    void eachWrongScalarTypeIsRejected(String key) throws Exception {
        ObjectNode body = detail(); bands(body).put(key, true); rejected(body);
    }
    @Test void unknownNestedAndTopLevelFieldsRemainRejected() throws Exception {
        ObjectNode body = detail(); bands(body).put("unknownBand", 1); rejected(body);
        body = detail(); ((ObjectNode) body.get("stock")).put("unknownDetail", 1); rejected(body);
    }
    @ParameterizedTest @ValueSource(strings = {"period", "standardDeviationMultiplier"})
    void periodAndMultiplierRejectNullFractionalAndWrongConstant(String key) throws Exception {
        ObjectNode body = detail(); bands(body).putNull(key); rejected(body);
        body = detail(); bands(body).put(key, 2.5); rejected(body);
        body = detail(); bands(body).put(key, 19); rejected(body);
        body = detail(); bands(body).put(key, Long.MAX_VALUE); rejected(body);
    }
    @Test void malformedDateAndMissingWholeObjectFieldRemainRejected() throws Exception {
        ObjectNode body = detail(); bands(body).put("asOfDate", "2026-02-30"); rejected(body);
        body = detail(); ((ObjectNode) body.get("stock")).remove("bollinger"); rejected(body);
        body = detail(); ((ObjectNode) body.get("stock")).put("bollinger", 1); rejected(body);
    }
    @Test void compactListStillRejectsBollinger() throws Exception {
        ObjectNode list = (ObjectNode) JSON.readTree(PublicContractJsonFixtures.TRADING_RADAR_LIST_WITH_NULLABLE_STOCK);
        ((ObjectNode) list.get("stocks").get(0)).putNull("bollinger");
        assertThatThrownBy(() -> decode(list, StrictPublicJsonResponse.Contract.TRADING_RADAR_LIST))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
