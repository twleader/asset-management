package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FxProviderTimestampTest {

    @Test
    void megaUpdateUsesStrictTaipeiWallClockParsing() {
        assertThat(MegaFxFetchClient.parseUpdate("20260813231012"))
                .isEqualTo(Instant.parse("2026-08-13T15:10:12Z"));
        assertThat(MegaFxFetchClient.parseUpdate("20260230010101")).isNull();
        assertThat(MegaFxFetchClient.parseUpdate("")).isNull();
    }

    @Test
    void yahooMetaParsesMidAndEpochSeconds() throws Exception {
        var meta = new ObjectMapper().readTree(
                "{\"regularMarketPrice\":32.12345,\"regularMarketTime\":1786633812}");
        FxSpotQuote quote = YahooFxFetchClient.parseMeta(meta).orElseThrow();

        assertThat(quote.spotBuy()).isEqualByComparingTo("32.1235");
        assertThat(quote.spotSell()).isEqualByComparingTo("32.1235");
        assertThat(quote.sourceUpdatedAt()).isEqualTo(Instant.ofEpochSecond(1786633812L));
    }

    @Test
    void yahooRejectsMissingTimestampOrNonPositiveRate() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        assertThat(YahooFxFetchClient.parseMeta(
                mapper.readTree("{\"regularMarketPrice\":32.1}"))).isEmpty();
        assertThat(YahooFxFetchClient.parseMeta(
                mapper.readTree("{\"regularMarketPrice\":0,\"regularMarketTime\":1786633812}"))).isEmpty();
    }

    @Test
    void allThreeCurlClientsUseSharedBoundedProcessRunner() throws Exception {
        String support = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/com/steven/assets/externalmaterials/client/CurlProcessSupport.java"));
        assertThat(support).contains("--connect-timeout", "--max-time", "destroyForcibly");
        for (String client : new String[]{"BotFxFetchClient.java", "MegaFxFetchClient.java", "YahooFxFetchClient.java"}) {
            String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "src/main/java/com/steven/assets/externalmaterials/client/" + client));
            assertThat(source).contains("CurlProcessSupport.get");
        }
    }
}
