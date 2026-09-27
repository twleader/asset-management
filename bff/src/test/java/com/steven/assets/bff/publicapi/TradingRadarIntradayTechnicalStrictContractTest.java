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

/** The public stock detail bridge validates the complete read-only intraday indicator schema. */
class TradingRadarIntradayTechnicalStrictContractTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INTRADAY = """
            {"status":"AVAILABLE","observedAt":"2026-09-25T02:13:00Z","ageSeconds":34,
             "oneMinute":{"status":"AVAILABLE","timeframe":"1","sourceDate":"2026-09-25",
               "sourceTimestamp":null,"observedAt":"2026-09-25T02:13:00Z",
               "kdj":{"k":51.2,"d":48.9,"j":55.8},
               "macd":{"macdLine":0.4,"signalLine":0.3},
               "bollinger":{"upper":1015,"middle":1004.5,"lower":994}},
             "fiveMinute":{"status":"AVAILABLE","timeframe":"5","sourceDate":"2026-09-25",
               "sourceTimestamp":"2026-09-25T02:10:00Z","observedAt":"2026-09-25T02:13:00Z",
               "kdj":{"k":50.1,"d":47.2,"j":55.9},
               "macd":{"macdLine":0.5,"signalLine":0.2},
               "bollinger":{"upper":1018,"middle":1005,"lower":992}}}
            """;

    private ObjectNode detail() throws Exception {
        return (ObjectNode) JSON.readTree(PublicContractJsonFixtures.TRADING_RADAR_STOCK_DETAIL);
    }

    private ObjectNode detailWithIntraday() throws Exception {
        ObjectNode detail = detail();
        ObjectNode resolution = JSON.createObjectNode();
        resolution.putNull("decisionInputVersion");
        resolution.putNull("source");
        resolution.putNull("binding");
        resolution.putNull("contextFingerprint");
        resolution.putNull("captureId");
        resolution.putNull("oldestObservedAt");
        resolution.putNull("freshUntil");
        resolution.putNull("ageSeconds");
        resolution.putArray("profiles");
        resolution.putArray("fieldProvenance");
        resolution.set("intraday", JSON.readTree(INTRADAY));
        ((ObjectNode) detail.get("stock")).set("technicalResolution", resolution);
        return detail;
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

    private ObjectNode intraday(ObjectNode body) {
        return (ObjectNode) body.at("/stock/technicalResolution/intraday");
    }

    private ObjectNode frame(ObjectNode body, String timeframe) {
        return (ObjectNode) intraday(body).get("1".equals(timeframe) ? "oneMinute" : "fiveMinute");
    }

    private void removeAt(ObjectNode body, String pointer) {
        String[] fields = pointer.substring(1).split("/");
        JsonNode parent = body;
        for (int index = 0; index < fields.length - 1; index++) parent = parent.get(fields[index]);
        ((ObjectNode) parent).remove(fields[fields.length - 1]);
    }

    @Test
    void validDetailPreservesVendorDecimalsAndDateOnlyTimestampNull() throws Exception {
        JsonNode decoded = decode(detailWithIntraday(), StrictPublicJsonResponse.Contract.TRADING_RADAR_STOCK_DETAIL);

        assertThat(decoded.at("/stock/technicalResolution/intraday/oneMinute/timeframe").textValue()).isEqualTo("1");
        assertThat(decoded.at("/stock/technicalResolution/intraday/oneMinute/sourceTimestamp").isNull()).isTrue();
        assertThat(decoded.at("/stock/technicalResolution/intraday/oneMinute/kdj/k").decimalValue())
                .isEqualByComparingTo("51.2");
        assertThat(decoded.at("/stock/technicalResolution/intraday/fiveMinute/bollinger/middle").decimalValue())
                .isEqualByComparingTo("1005");
    }

    @Test
    void unavailableAndLegacyNullableIntradayRemainAccepted() throws Exception {
        ObjectNode body = detailWithIntraday();
        ObjectNode unavailable = JSON.createObjectNode();
        unavailable.put("status", "UNAVAILABLE");
        unavailable.putNull("observedAt");
        unavailable.putNull("ageSeconds");
        unavailable.putNull("oneMinute");
        unavailable.putNull("fiveMinute");
        ((ObjectNode) body.at("/stock/technicalResolution")).set("intraday", unavailable);
        assertThat(decode(body, StrictPublicJsonResponse.Contract.TRADING_RADAR_STOCK_DETAIL)
                .at("/stock/technicalResolution/intraday/status").textValue()).isEqualTo("UNAVAILABLE");

        body = detailWithIntraday();
        ((ObjectNode) body.at("/stock/technicalResolution")).putNull("intraday");
        assertThat(decode(body, StrictPublicJsonResponse.Contract.TRADING_RADAR_STOCK_DETAIL)
                .at("/stock/technicalResolution/intraday").isNull()).isTrue();
    }

    @Test
    void everyIntradayObjectRequiresItsDocumentedFields() throws Exception {
        String[] paths = {
                "/stock/technicalResolution/intraday/status",
                "/stock/technicalResolution/intraday/observedAt",
                "/stock/technicalResolution/intraday/ageSeconds",
                "/stock/technicalResolution/intraday/oneMinute",
                "/stock/technicalResolution/intraday/fiveMinute",
                "/stock/technicalResolution/intraday/oneMinute/status",
                "/stock/technicalResolution/intraday/oneMinute/timeframe",
                "/stock/technicalResolution/intraday/oneMinute/sourceDate",
                "/stock/technicalResolution/intraday/oneMinute/sourceTimestamp",
                "/stock/technicalResolution/intraday/oneMinute/observedAt",
                "/stock/technicalResolution/intraday/oneMinute/kdj",
                "/stock/technicalResolution/intraday/oneMinute/macd",
                "/stock/technicalResolution/intraday/oneMinute/bollinger",
                "/stock/technicalResolution/intraday/oneMinute/kdj/k",
                "/stock/technicalResolution/intraday/oneMinute/kdj/d",
                "/stock/technicalResolution/intraday/oneMinute/kdj/j",
                "/stock/technicalResolution/intraday/oneMinute/macd/macdLine",
                "/stock/technicalResolution/intraday/oneMinute/macd/signalLine",
                "/stock/technicalResolution/intraday/oneMinute/bollinger/upper",
                "/stock/technicalResolution/intraday/oneMinute/bollinger/middle",
                "/stock/technicalResolution/intraday/oneMinute/bollinger/lower",
                "/stock/technicalResolution/intraday"
        };

        for (String path : paths) {
            ObjectNode body = detailWithIntraday();
            removeAt(body, path);
            rejected(body);
        }
    }

    @Test
    void invalidEnumsDatesNumbersAndUnknownFieldsAreRejected() throws Exception {
        ObjectNode body = detailWithIntraday(); intraday(body).put("status", "FRESH"); rejected(body);
        body = detailWithIntraday(); intraday(body).put("ageSeconds", -1); rejected(body);
        body = detailWithIntraday(); intraday(body).put("observedAt", "2026-09-25"); rejected(body);
        body = detailWithIntraday(); frame(body, "1").put("timeframe", "5"); rejected(body);
        body = detailWithIntraday(); frame(body, "1").put("sourceDate", "2026-02-30"); rejected(body);
        body = detailWithIntraday(); frame(body, "1").put("observedAt", "not-a-timestamp"); rejected(body);
        body = detailWithIntraday(); ((ObjectNode) frame(body, "1").get("kdj")).putNull("k"); rejected(body);
        body = detailWithIntraday(); intraday(body).put("unknown", true); rejected(body);
        body = detailWithIntraday(); frame(body, "1").put("unknown", true); rejected(body);
        body = detailWithIntraday(); ((ObjectNode) frame(body, "1").get("bollinger")).put("extra", 1); rejected(body);
    }

    @Test
    void compactRadarListDoesNotAcceptIntradayDetailFields() throws Exception {
        ObjectNode list = (ObjectNode) JSON.readTree(PublicContractJsonFixtures.TRADING_RADAR_LIST_WITH_NULLABLE_STOCK);
        ((ObjectNode) list.get("stocks").get(0)).putNull("technicalResolution");
        assertThatThrownBy(() -> decode(list, StrictPublicJsonResponse.Contract.TRADING_RADAR_LIST))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rootFreshnessStatusMustAgreeWithFrameStatusesAndAge() throws Exception {
        ObjectNode body = detailWithIntraday();
        frame(body, "5").put("status", "STALE");
        frame(body, "5").put("sourceTimestamp", "2026-09-25T02:05:00Z");
        rejected(body);

        body = detailWithIntraday();
        intraday(body).put("status", "STALE");
        rejected(body);

        body = detailWithIntraday();
        intraday(body).put("ageSeconds", 421);
        rejected(body);

        body = detailWithIntraday();
        intraday(body).put("status", "STALE").put("ageSeconds", 421);
        frame(body, "1").put("status", "STALE");
        rejected(body);

        body = detailWithIntraday();
        intraday(body).put("status", "STALE");
        frame(body, "5").put("status", "STALE").put("sourceTimestamp", "2026-09-25T02:05:00Z");
        assertThat(decode(body, StrictPublicJsonResponse.Contract.TRADING_RADAR_STOCK_DETAIL)
                .at("/stock/technicalResolution/intraday/status").textValue()).isEqualTo("STALE");
    }

    @Test
    void eachFrameStatusMustAgreeWithItsOldestAvailableSourceTime() throws Exception {
        ObjectNode body = detailWithIntraday();
        intraday(body).put("status", "STALE");
        frame(body, "1").put("status", "STALE").put("sourceTimestamp", "2026-09-25T02:05:00Z");
        frame(body, "5").put("sourceTimestamp", "2026-09-25T02:05:00Z");
        rejected(body);

        body = detailWithIntraday();
        intraday(body).put("status", "STALE");
        frame(body, "1").put("status", "STALE");
        rejected(body);
    }
}
