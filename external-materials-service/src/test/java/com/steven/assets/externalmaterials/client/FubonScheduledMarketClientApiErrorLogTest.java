package com.steven.assets.externalmaterials.client;

import com.steven.assets.externalmaterials.service.ExternalApiErrorLogWriter;
import com.steven.assets.externalmaterials.service.MarketClock;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FubonScheduledMarketClientApiErrorLogTest {
    private static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 6);

    @Test void outbound_non_200_is_logged_once_with_the_matching_catalog_operation() {
        MarketClock clock = mock(MarketClock.class);
        when(clock.instant()).thenReturn(NOW);
        ExternalApiErrorLogWriter writer = mock(ExternalApiErrorLogWriter.class);
        FubonScheduledMarketClient client = client(clock, writer,
                (uri, token, body, limit, timeout) -> new FubonScheduledMarketClient.RawResponse(503, "{}".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> client.technical("2330", DAY)).hasMessage("UPSTREAM_UNAVAILABLE");

        verify(writer).record(eq("FUBON_TECHNICAL_INDICATORS_READ"), eq("技術指標查詢"), any(Throwable.class), eq(NOW));
        verifyNoMoreInteractions(writer);
    }

    @Test void schema_rejection_after_outbound_is_logged_once_but_invalid_input_and_disabled_are_not() {
        MarketClock clock = mock(MarketClock.class);
        when(clock.instant()).thenReturn(NOW);
        ExternalApiErrorLogWriter writer = mock(ExternalApiErrorLogWriter.class);
        FubonScheduledMarketClient client = client(clock, writer,
                (uri, token, body, limit, timeout) -> new FubonScheduledMarketClient.RawResponse(200, "{}".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> client.basic("2330", DAY)).hasMessage("STOCK_BASIC_SCHEMA_INVALID");
        verify(writer).record(eq("FUBON_STOCK_BASIC_READ"), eq("個股基本資料查詢"), any(Throwable.class), eq(NOW));
        clearInvocations(writer);

        assertThatThrownBy(() -> client.dividends(List.of(), DAY)).hasMessage("INVALID_REQUEST");
        verifyNoInteractions(writer);

        FubonScheduledMarketClient disabled = new FubonScheduledMarketClient(
                new FubonMarketConfigState("false", "http://fake.invalid", "unused", p -> "token"), clock,
                (uri, token, body, limit, timeout) -> { throw new AssertionError("must not call transport"); }, writer);
        assertThatThrownBy(() -> disabled.technical("2330", DAY)).hasMessage("DISABLED");
        verifyNoInteractions(writer);
    }

    private static FubonScheduledMarketClient client(MarketClock clock, ExternalApiErrorLogWriter writer,
                                                       FubonScheduledMarketClient.Transport transport) {
        return new FubonScheduledMarketClient(
                new FubonMarketConfigState("true", "http://fake.invalid", "unused", p -> "token"), clock, transport, writer);
    }
}
