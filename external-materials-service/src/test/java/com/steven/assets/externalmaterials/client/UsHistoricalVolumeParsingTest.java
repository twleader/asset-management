package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class UsHistoricalVolumeParsingTest {
    @Test void missingNullAndOverflowVolumeRemainUnknownWhileActualZeroRemainsZero() throws Exception {
        for (String volumes : new String[]{"[]", "[null]", "[9223372036854775808]", "[\"unknown\"]"}) {
            assertThat(parse(volumes)).isNull();
        }
        assertThat(parse("[0]")).isZero();
        assertThat(parse("[4652800]")).isEqualTo(4652800L);
    }

    @Test void wrongOrMissingSymbolOrCurrencyCannotBecomeTheRequestedStockHistory() throws Exception {
        for (String meta : new String[]{"{}", "{\"symbol\":\"VT\",\"currency\":\"USD\"}",
                "{\"symbol\":\"VOO\",\"currency\":\"EUR\"}"}) {
            var root = new ObjectMapper().readTree("{\"chart\":{\"result\":[{\"meta\":" + meta
                    + ",\"timestamp\":[1792762200],\"indicators\":{\"quote\":[{\"close\":[101]}]}}]}}");
            assertThat(PriceFetchClient.parseUsHistoricalResponse(root, "VOO", LocalDate.of(2026, 10, 1))).isEmpty();
        }
    }

    @Test void malformedTimestampCannotBeCoercedIntoAnEpochOrTradingDate() throws Exception {
        for (String timestamp : new String[]{"null", "\"1792762200\"", "1792762200.5", "9223372036854775808", "-1", "0"}) {
            var root = new ObjectMapper().readTree("""
                    {"chart":{"result":[{"meta":{"symbol":"VOO","currency":"USD"},
                      "timestamp":[%s],"indicators":{"quote":[{"close":[101],"volume":[100]}]}}]}}
                    """.formatted(timestamp));
            assertThat(PriceFetchClient.parseUsHistoricalResponse(root, "VOO", LocalDate.of(1960, 1, 1))).isEmpty();
        }
    }

    private Long parse(String volumes) throws Exception {
        var root = new ObjectMapper().readTree("""
                {"chart":{"result":[{"meta":{"symbol":"VOO","currency":"USD"},"timestamp":[1792762200],"indicators":{"quote":[{
                  "open":[698.47],"high":[701.77],"low":[697.38],"close":[698.58],"volume":%s
                }]}}]}}
                """.formatted(volumes));
        return PriceFetchClient.parseUsHistoricalResponse(root, "VOO", LocalDate.of(2026, 10, 1))
                .getFirst().volume();
    }
}
