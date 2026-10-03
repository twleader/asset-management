package com.steven.assets.externalmaterials.client;

import com.steven.assets.externalmaterials.service.ExternalApiErrorLogWriter;
import com.steven.assets.externalmaterials.service.MarketClock;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test void task425OutboundFailuresUseTheTwoSeededImmutableErrorLogIdentities() {
        MarketClock clock = mock(MarketClock.class);
        when(clock.instant()).thenReturn(NOW);
        ExternalApiErrorLogWriter writer = mock(ExternalApiErrorLogWriter.class);
        FubonScheduledMarketClient client = client(clock, writer,
                (uri, token, body, limit, timeout) -> new FubonScheduledMarketClient.RawResponse(503, "{}".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> client.intradayVolumes("2330", DAY)).hasMessage("UPSTREAM_UNAVAILABLE");
        assertThatThrownBy(() -> client.historicalDailyCandles("2330", DAY.minusDays(364), DAY)).hasMessage("UPSTREAM_UNAVAILABLE");

        verify(writer).record(eq("FUBON_INTRADAY_VOLUMES_READ"), eq("個股當日分價量查詢"), any(Throwable.class), eq(NOW));
        verify(writer).record(eq("FUBON_HISTORICAL_DAILY_CANDLES_READ"), eq("個股歷史日K線查詢"), any(Throwable.class), eq(NOW));
        verifyNoMoreInteractions(writer);
    }

    @Test void task461IntradayTechnicalRouteReusesTheImmutableTechnicalIndicatorIdentity() {
        MarketClock clock = mock(MarketClock.class);
        when(clock.instant()).thenReturn(NOW);
        ExternalApiErrorLogWriter writer = mock(ExternalApiErrorLogWriter.class);
        FubonScheduledMarketClient client = client(clock, writer,
                (uri, token, body, limit, timeout) -> new FubonScheduledMarketClient.RawResponse(503, "{}".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> client.intradayTechnical("2330", DAY)).hasMessage("UPSTREAM_UNAVAILABLE");

        verify(writer).record(eq("FUBON_TECHNICAL_INDICATORS_READ"), eq("技術指標查詢"), any(Throwable.class), eq(NOW));
        verifyNoMoreInteractions(writer);
    }

    @Test void task461IntradayTechnicalSchemaFailureReusesTheImmutableTechnicalIndicatorIdentity() {
        MarketClock clock = mock(MarketClock.class);
        when(clock.instant()).thenReturn(NOW);
        ExternalApiErrorLogWriter writer = mock(ExternalApiErrorLogWriter.class);
        FubonScheduledMarketClient client = client(clock, writer,
                (uri, token, body, limit, timeout) -> new FubonScheduledMarketClient.RawResponse(200, "{}".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> client.intradayTechnical("2330", DAY)).hasMessage("INVALID_RESPONSE");

        verify(writer).record(eq("FUBON_TECHNICAL_INDICATORS_READ"), eq("技術指標查詢"), any(Throwable.class), eq(NOW));
        verifyNoMoreInteractions(writer);
    }

    @Test void technicalHistoryProfileFailuresAreAggregatedOnceInManifestOrder() throws Exception {
        MarketClock clock = mock(MarketClock.class);
        when(clock.instant()).thenReturn(NOW);
        ExternalApiErrorLogWriter writer = mock(ExternalApiErrorLogWriter.class);
        String body = technicalBundle("sma_d_20", "UPSTREAM_UNAVAILABLE", "rsi_d_5", "RATE_LIMITED");
        FubonScheduledMarketClient client = client(clock, writer,
                (uri, token, request, limit, timeout) -> new FubonScheduledMarketClient.RawResponse(200,
                        body.getBytes(StandardCharsets.UTF_8)));

        client.technicalV2("2330", DAY.minusDays(10), DAY);

        ArgumentCaptor<Throwable> failure = ArgumentCaptor.forClass(Throwable.class);
        verify(writer).record(eq("FUBON_TECHNICAL_INDICATORS_READ"), eq("技術指標查詢"), failure.capture(), eq(NOW));
        verifyNoMoreInteractions(writer);
        assertThat(failure.getValue().getMessage()).isEqualTo(
                "operation=FUBON_TECHNICAL_INDICATORS_READ symbol=2330 queryFrom=2026-08-27 queryTo=2026-09-06 "
                        + "profileFailures=[sma_d_20:UPSTREAM_UNAVAILABLE,rsi_d_5:RATE_LIMITED]");
        assertThat(failure.getValue().getMessage()).doesNotContain("token", "request", "response");
    }

    @Test void technicalHistoryHttpAndTransportFailuresCarrySafeInvocationContextOnce() {
        MarketClock clock = mock(MarketClock.class);
        when(clock.instant()).thenReturn(NOW);
        ExternalApiErrorLogWriter writer = mock(ExternalApiErrorLogWriter.class);
        FubonScheduledMarketClient httpClient = client(clock, writer,
                (uri, token, request, limit, timeout) -> new FubonScheduledMarketClient.RawResponse(503,
                        "secret response".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> httpClient.technicalV2("2330", DAY.minusDays(10), DAY))
                .hasMessage("UPSTREAM_UNAVAILABLE");
        ArgumentCaptor<Throwable> failure = ArgumentCaptor.forClass(Throwable.class);
        verify(writer).record(eq("FUBON_TECHNICAL_INDICATORS_READ"), eq("技術指標查詢"), failure.capture(), eq(NOW));
        assertThat(failure.getValue().getMessage()).isEqualTo(
                "operation=FUBON_TECHNICAL_INDICATORS_READ symbol=2330 queryFrom=2026-08-27 queryTo=2026-09-06 "
                        + "profileFailures=[] invocationFailure=UPSTREAM_UNAVAILABLE httpStatus=503");
        assertThat(failure.getValue().getMessage()).doesNotContain("secret", "response");
        clearInvocations(writer);

        FubonScheduledMarketClient transportClient = client(clock, writer,
                (uri, token, request, limit, timeout) -> { throw new java.net.SocketTimeoutException("secret token"); });
        assertThatThrownBy(() -> transportClient.technicalV2("2330", DAY.minusDays(10), DAY))
                .hasMessage("UPSTREAM_UNAVAILABLE");
        verify(writer).record(eq("FUBON_TECHNICAL_INDICATORS_READ"), eq("技術指標查詢"), failure.capture(), eq(NOW));
        verifyNoMoreInteractions(writer);
        assertThat(failure.getValue().getMessage()).isEqualTo(
                "operation=FUBON_TECHNICAL_INDICATORS_READ symbol=2330 queryFrom=2026-08-27 queryTo=2026-09-06 "
                        + "profileFailures=[] invocationFailure=UPSTREAM_UNAVAILABLE");
        assertThat(failure.getValue().getMessage()).doesNotContain("secret", "token");
    }

    @Test void technicalHistorySchemaFailureIsLoggedAndCancellationOrInterruptionIsNot() {
        MarketClock clock = mock(MarketClock.class);
        when(clock.instant()).thenReturn(NOW);
        ExternalApiErrorLogWriter writer = mock(ExternalApiErrorLogWriter.class);
        FubonScheduledMarketClient schemaClient = client(clock, writer,
                (uri, token, request, limit, timeout) -> new FubonScheduledMarketClient.RawResponse(200,
                        "{}".getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(() -> schemaClient.technicalV2("2330", DAY.minusDays(10), DAY))
                .hasMessage("TECHNICAL_SCHEMA_INVALID");
        ArgumentCaptor<Throwable> failure = ArgumentCaptor.forClass(Throwable.class);
        verify(writer).record(eq("FUBON_TECHNICAL_INDICATORS_READ"), eq("技術指標查詢"), failure.capture(), eq(NOW));
        assertThat(failure.getValue().getMessage()).isEqualTo(
                "operation=FUBON_TECHNICAL_INDICATORS_READ symbol=2330 queryFrom=2026-08-27 queryTo=2026-09-06 "
                        + "profileFailures=[] invocationFailure=TECHNICAL_SCHEMA_INVALID httpStatus=200");
        clearInvocations(writer);

        FubonScheduledMarketClient interruptedClient = client(clock, writer,
                (uri, token, request, limit, timeout) -> { throw new InterruptedException("secret"); });
        assertThatThrownBy(() -> interruptedClient.technicalV2("2330", DAY.minusDays(10), DAY))
                .hasMessage("INTERRUPTED");
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted();
        verifyNoInteractions(writer);

        FubonScheduledMarketClient cancelledClient = client(clock, writer,
                (uri, token, request, limit, timeout) -> { throw new java.util.concurrent.CancellationException("secret"); });
        assertThatThrownBy(() -> cancelledClient.technicalV2("2330", DAY.minusDays(10), DAY))
                .hasMessage("CANCELLED");
        verifyNoInteractions(writer);
    }

    private static String technicalBundle(String firstFailedProfile, String firstReason,
                                          String secondFailedProfile, String secondReason) throws Exception {
        var root = FubonMarketJson.MAPPER.createObjectNode();
        root.put("schemaVersion", 2);
        root.put("captureId", UUID.fromString("00000000-0000-0000-0000-000000000001").toString());
        root.put("symbol", "2330");
        root.put("market", "台股");
        root.put("provider", "FUBON_SDK");
        root.put("queryFrom", DAY.minusDays(10).toString());
        root.put("queryTo", DAY.toString());
        var profiles = root.putArray("profiles");
        for (var profile : com.steven.assets.externalmaterials.service.FubonMarketData.TECHNICAL_PROFILES) {
            var item = profiles.addObject();
            item.put("profileId", profile.profileId());
            String reason = profile.profileId().equals(firstFailedProfile) ? firstReason
                    : profile.profileId().equals(secondFailedProfile) ? secondReason : "NO_DATA";
            item.put("status", "NO_DATA".equals(reason) ? "NO_DATA" : "UNAVAILABLE");
            item.put("reason", reason);
            item.set("parameters", FubonMarketJson.MAPPER.valueToTree(profile.parameters()));
            item.put("observedAt", "2026-09-06T00:00:00Z");
            item.putArray("history");
        }
        return FubonMarketJson.MAPPER.writeValueAsString(root);
    }

    private static FubonScheduledMarketClient client(MarketClock clock, ExternalApiErrorLogWriter writer,
                                                       FubonScheduledMarketClient.Transport transport) {
        return new FubonScheduledMarketClient(
                new FubonMarketConfigState("true", "http://fake.invalid", "unused", p -> "token"), clock, transport, writer);
    }
}
