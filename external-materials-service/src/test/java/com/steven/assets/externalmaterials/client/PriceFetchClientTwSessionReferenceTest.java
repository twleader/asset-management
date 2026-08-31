package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class PriceFetchClientTwSessionReferenceTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void yCanBeUsedAsExactDateReferenceWhenZHasNoTradeButNotAsLivePrice() throws Exception {
        var item = mapper.readTree("{\"c\":\"00881\",\"d\":\"20260818\",\"y\":\"50.5500\",\"z\":\"-\"}");
        var reference = PriceFetchClient.parseTwSessionReference("00881", item, LocalDate.of(2026, 8, 18));
        assertThat(reference).isPresent();
        assertThat(reference.orElseThrow().price()).isEqualByComparingTo("50.55");
        assertThat(PriceFetchClient.parseTwseLivePrice("00881", item)).isEmpty();
    }

    @Test
    void referenceRequiresMatchingDateAndPositiveY() throws Exception {
        var item = mapper.readTree("{\"c\":\"2885\",\"d\":\"20260818\",\"y\":\"65.70\",\"z\":\"64.00\"}");
        assertThat(PriceFetchClient.parseTwSessionReference("2885", item, LocalDate.of(2026, 8, 17))).isEmpty();
        item = mapper.readTree("{\"c\":\"2885\",\"d\":\"20260818\",\"y\":\"0\",\"z\":\"64.00\"}");
        assertThat(PriceFetchClient.parseTwSessionReference("2885", item, LocalDate.of(2026, 8, 18))).isEmpty();
    }

    @Test
    void mismatchedTseItemDoesNotBlockOtcFallbackSelection() throws Exception {
        var tse = mapper.readTree("[{\"c\":\"9999\"}]");
        var otc = mapper.readTree("[{\"c\":\"2885\",\"d\":\"20260818\",\"y\":\"65.70\"}]");
        assertThat(PriceFetchClient.selectTwseItem(tse, "2885")).isNull();
        assertThat(PriceFetchClient.selectTwseItem(otc, "2885").path("c").asText()).isEqualTo("2885");
    }

    @Test
    void fubonPreviousCloseAndYahooCannotPretendToBeQualifiedTwseMisDY() {
        assertThatIllegalArgumentException().isThrownBy(() -> new PriceFetchClient.TwSessionReference(
                "00881", LocalDate.of(2026, 8, 18), new BigDecimal("50.55"), "FUBON_PREVIOUS_CLOSE"));
        assertThatIllegalArgumentException().isThrownBy(() -> new PriceFetchClient.TwSessionReference(
                "00881", LocalDate.of(2026, 8, 18), new BigDecimal("50.55"), "YAHOO"));
    }

    @Test
    void validZStillBuildsLivePriceRatherThanReferenceValue() throws Exception {
        var item = mapper.readTree("{\"c\":\"2885\",\"d\":\"20260818\",\"z\":\"64.00\",\"y\":\"65.70\",\"n\":\"元大金\"}");
        var live = PriceFetchClient.parseTwseLivePrice("2885", item);
        assertThat(live).isPresent();
        assertThat(live.orElseThrow().price()).isEqualByComparingTo("64.00");
        assertThat(live.orElseThrow().previousClose()).isEqualByComparingTo("65.70");
    }

    @Test
    void invalidCodeAndIndexAreRejectedBeforeAnyMisHttpProbe() {
        HttpClient http = mock(HttpClient.class);
        PriceFetchClient client = new PriceFetchClient("", http);

        assertThat(client.fetchTwSessionReference("0000", LocalDate.of(2026, 8, 18))).isEmpty();
        assertThat(client.fetchTwSessionReference("00881;BAD", LocalDate.of(2026, 8, 18))).isEmpty();

        verifyNoInteractions(http);
    }
}
