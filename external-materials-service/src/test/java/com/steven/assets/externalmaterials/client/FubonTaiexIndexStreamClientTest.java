package com.steven.assets.externalmaterials.client;

import com.steven.assets.externalmaterials.service.FubonTaiexIndexEvent;
import com.steven.assets.externalmaterials.service.FubonTaiexIndexIngestionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class FubonTaiexIndexStreamClientTest {

    @Test
    void disabledOrInvalidNonSecretGateDoesNotReadTokenOrOpenHttpStream() {
        assertNoSecretSideEffects(false, true, "IR0001");
        assertNoSecretSideEffects(true, false, "IR0001");
        assertNoSecretSideEffects(true, true, "invalid symbol");
        assertNoSecretSideEffects(true, true, "");
    }

    @Test
    void non2xxReconnectUsesBoundedBackoffAndDoesNotIngest() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        CountDownLatch secondBackoff = new CountDownLatch(1);
        ConcurrentLinkedQueue<Long> delays = new ConcurrentLinkedQueue<>();
        FubonTaiexIndexIngestionService ingestion = mock(FubonTaiexIndexIngestionService.class);
        FubonTaiexIndexStreamClient client = new FubonTaiexIndexStreamClient(
                true, true, "IR0001", "http://adapter:8080", "/token", ingestion,
                (endpoint, token) -> {
                    opens.incrementAndGet();
                    return new FubonTaiexIndexStreamClient.RawResponse(503, null);
                }, millis -> {
                    delays.add(millis);
                    if (delays.size() == 2) {
                        secondBackoff.countDown();
                        throw new InterruptedException();
                    }
                }, path -> "token");

        client.start();

        assertThat(secondBackoff.await(2, TimeUnit.SECONDS)).isTrue();
        client.stop();
        assertThat(opens).hasValue(2);
        assertThat(delays).containsExactly(250L, 500L);
        verify(ingestion, never()).ingest(any());
    }

    private static void assertNoSecretSideEffects(boolean fubonEnabled, boolean streamEnabled, String symbol) {
        AtomicInteger tokenReads = new AtomicInteger();
        AtomicInteger opens = new AtomicInteger();
        FubonTaiexIndexStreamClient client = new FubonTaiexIndexStreamClient(
                fubonEnabled, streamEnabled, symbol, "http://adapter:8080", "/never-read", mock(FubonTaiexIndexIngestionService.class),
                (endpoint, token) -> {
                    opens.incrementAndGet();
                    return null;
                }, millis -> { }, path -> {
                    tokenReads.incrementAndGet();
                    return "secret";
                });

        client.start();

        assertThat(tokenReads).hasValue(0);
        assertThat(opens).hasValue(0);
        assertThat(client.isRunning()).isFalse();
    }

    @Test
    void enabledButMissingTokenDoesNotOpenOrRetryStream() {
        AtomicInteger tokenReads = new AtomicInteger();
        AtomicInteger opens = new AtomicInteger();
        FubonTaiexIndexStreamClient client = new FubonTaiexIndexStreamClient(
                true, true, "IR0001", "http://adapter:8080", "/missing", mock(FubonTaiexIndexIngestionService.class),
                (endpoint, token) -> {
                    opens.incrementAndGet();
                    return null;
                }, millis -> { }, path -> {
                    tokenReads.incrementAndGet();
                    return null;
                });

        client.start();

        assertThat(tokenReads).hasValue(1);
        assertThat(opens).hasValue(0);
        assertThat(client.isRunning()).isFalse();
    }

    @Test
    void exactSseFrameReachesIngestionAndLongStreamHasNoRequestTimeoutContract() throws Exception {
        FubonTaiexIndexIngestionService ingestion = mock(FubonTaiexIndexIngestionService.class);
        CountDownLatch received = new CountDownLatch(1);
        doAnswer(invocation -> {
            received.countDown();
            return FubonTaiexIndexIngestionService.IngestionOutcome.DB_APPLIED;
        }).when(ingestion).ingest(any());
        String body = "event: taiex-index\n"
                + "id: 1787792400123456\n"
                + "data: {\"symbol\":\"IR0001\",\"exchange\":\"TWSE\",\"type\":\"INDEX\",\"index\":\"22345.67\",\"time\":1787792400123456}\n\n";
        FubonTaiexIndexStreamClient client = new FubonTaiexIndexStreamClient(
                true, true, "IR0001", "http://adapter:8080", "/token", ingestion,
                (endpoint, token) -> new FubonTaiexIndexStreamClient.RawResponse(200,
                        new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))),
                millis -> { throw new InterruptedException(); }, path -> "token");

        client.start();
        assertThat(received.await(2, TimeUnit.SECONDS)).isTrue();
        client.stop();
        ArgumentCaptor<FubonTaiexIndexEvent> event = ArgumentCaptor.forClass(FubonTaiexIndexEvent.class);
        verify(ingestion).ingest(event.capture());
        assertThat(event.getValue().timeMicros()).isEqualTo(1787792400123456L);
        assertThat(event.getValue().index()).isEqualTo("22345.67");
    }

    @Test
    void duplicateJsonAndMismatchedIdNeverReachIngestion() throws Exception {
        FubonTaiexIndexIngestionService ingestion = mock(FubonTaiexIndexIngestionService.class);
        CountDownLatch reachedBackoff = new CountDownLatch(1);
        String body = "event: taiex-index\n"
                + "id: 1787792400123455\n"
                + "data: {\"symbol\":\"IR0001\",\"symbol\":\"IR0001\",\"exchange\":\"TWSE\",\"type\":\"INDEX\",\"index\":\"22345.67\",\"time\":1787792400123456}\n\n";
        FubonTaiexIndexStreamClient client = new FubonTaiexIndexStreamClient(
                true, true, "IR0001", "http://adapter:8080", "/token", ingestion,
                (endpoint, token) -> new FubonTaiexIndexStreamClient.RawResponse(200,
                        new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))),
                millis -> {
                    reachedBackoff.countDown();
                    throw new InterruptedException();
                }, path -> "token");

        client.start();
        // Reaching backoff proves the malformed EOF frame was fully parsed and rejected without
        // relying on scheduler timing.
        assertThat(reachedBackoff.await(2, TimeUnit.SECONDS)).isTrue();
        client.stop();
        verify(ingestion, never()).ingest(any());
    }
}
