package com.steven.assets.bff.tradingradar;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class TradingRadarIntradayCandlePayloadTest {
    @Test void confirmedAndWaitRequireWholeMinuteEndpointsInBrowserEvaluation() throws Exception {
        for (String status : java.util.List.of("CONFIRMED", "WAIT")) {
            var root = TradingRadarPanelFixtures.evaluation("台股", "2330");
            var confirmation = (ObjectNode) new ObjectMapper().readTree("""
                    {"status":"CONFIRMED","reason":"COMPLETED_PRICE_VOLUME_CONFIRMED","sourceDate":"2026-10-08",
                     "observedAt":"2026-10-08T01:13:59Z","lastCompletedAt":"2026-10-08T01:12:00Z",
                     "fiveMinuteAt":"2026-10-08T01:10:00Z","oneMinuteAt":"2026-10-08T01:12:00Z",
                     "aggregationSource":"LOCAL_AGGREGATED_FUBON_1M"}
                    """);
            confirmation.put("status", status);
            confirmation.put("reason", status.equals("WAIT") ? "ONE_MINUTE_WEAKENING" : "COMPLETED_PRICE_VOLUME_CONFIRMED");
            ((ObjectNode) root.get("summary")).set("intradayCandleConfirmation", confirmation);
            ((ObjectNode) root.get("stock")).set("intradayCandleConfirmation", confirmation);
            assertThat(TradingRadarPayloadValidator.evaluation(root, "2330", "台股")
                    .summary().at("/intradayCandleConfirmation/status").asText()).isEqualTo(status);
            confirmation.put("lastCompletedAt", "2026-10-08T01:12:30Z");
            confirmation.put("oneMinuteAt", "2026-10-08T01:12:30Z");
            assertThatThrownBy(() -> TradingRadarPayloadValidator.evaluation(root, "2330", "台股"))
                    .isInstanceOf(ResponseStatusException.class);
        }
    }

    @Test void browserRequiresSameReceiptOnSummaryAndFullAndRejectsMalformedMetadata() throws Exception {
        var root = TradingRadarPanelFixtures.evaluation("台股", "2330");
        var confirmation = new ObjectMapper().readTree("""
                {"status":"UNAVAILABLE","reason":"CAPTURE_MISSING","sourceDate":"2026-10-08",
                 "observedAt":null,"lastCompletedAt":null,"fiveMinuteAt":null,"oneMinuteAt":null,"aggregationSource":null}
                """);
        ((ObjectNode) root.get("stock")).set("intradayCandleConfirmation", confirmation);
        assertThatThrownBy(() -> TradingRadarPayloadValidator.evaluation(root, "2330", "台股"))
                .isInstanceOf(ResponseStatusException.class);
        ((ObjectNode) root.get("summary")).set("intradayCandleConfirmation", confirmation);
        assertThat(TradingRadarPayloadValidator.evaluation(root, "2330", "台股").summary().get("intradayCandleConfirmation"))
                .isEqualTo(confirmation);
        ((ObjectNode) root.get("stock").get("intradayCandleConfirmation")).put("status", "AVAILABLE");
        assertThatThrownBy(() -> TradingRadarPayloadValidator.evaluation(root, "2330", "台股"))
                .isInstanceOf(ResponseStatusException.class);
    }
}
