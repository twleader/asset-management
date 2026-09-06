package com.steven.assets.externalmaterials.client;

import com.steven.assets.externalmaterials.service.FubonTaiexIndexEvent;
import com.steven.assets.externalmaterials.service.FubonTaiexIndexIngestionService;
import com.steven.assets.externalmaterials.service.ExternalApiErrorLogWriter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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

    @Test
    void malformedTargetFrameIsRecordedOnceWithoutAttemptingToIngestIt() throws Exception {
        FubonTaiexIndexIngestionService ingestion = mock(FubonTaiexIndexIngestionService.class);
        ExternalApiErrorLogWriter writer = mock(ExternalApiErrorLogWriter.class);
        CountDownLatch reachedBackoff = new CountDownLatch(1);
        String body = "event: taiex-index\n"
                + "id: 1787792400123456\n\n";
        FubonTaiexIndexStreamClient client = new FubonTaiexIndexStreamClient(
                true, true, "IR0001", "http://adapter:8080", "/token", ingestion,
                (endpoint, token) -> new FubonTaiexIndexStreamClient.RawResponse(200,
                        new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))),
                millis -> { reachedBackoff.countDown(); throw new InterruptedException(); }, path -> "token", writer);

        client.start();
        assertThat(reachedBackoff.await(2, TimeUnit.SECONDS)).isTrue();
        client.stop();

        verify(ingestion, never()).ingest(any());
        verify(writer).record(eq("FUBON_TAIEX_INDEX_STREAM"), eq("加權指數串流"), any(Throwable.class), any());
    }

    @Test
    void restartOpensANewFailureIntervalAfterStop() throws Exception {
        FubonTaiexIndexIngestionService ingestion = mock(FubonTaiexIndexIngestionService.class);
        ExternalApiErrorLogWriter writer = mock(ExternalApiErrorLogWriter.class);
        CountDownLatch firstBackoff = new CountDownLatch(1);
        CountDownLatch secondBackoff = new CountDownLatch(1);
        AtomicInteger backoffs = new AtomicInteger();
        String body = "event: taiex-index\nid: 1787792400123456\n\n";
        FubonTaiexIndexStreamClient client = new FubonTaiexIndexStreamClient(
                true, true, "IR0001", "http://adapter:8080", "/token", ingestion,
                (endpoint, token) -> new FubonTaiexIndexStreamClient.RawResponse(200,
                        new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))),
                millis -> {
                    if (backoffs.incrementAndGet() == 1) firstBackoff.countDown(); else secondBackoff.countDown();
                    new CountDownLatch(1).await();
                }, path -> "token", writer);

        client.start();
        assertThat(firstBackoff.await(2, TimeUnit.SECONDS)).isTrue();
        client.stop();
        client.start();
        assertThat(secondBackoff.await(2, TimeUnit.SECONDS)).isTrue();
        client.stop();

        verify(writer, org.mockito.Mockito.times(2)).record(eq("FUBON_TAIEX_INDEX_STREAM"), eq("加權指數串流"), any(Throwable.class), any());
    }

    @Test
    void stale_cancelled_loop_cannot_log_or_stop_an_immediately_restarted_lifecycle() throws Exception {
        FubonTaiexIndexIngestionService ingestion = mock(FubonTaiexIndexIngestionService.class);
        ExternalApiErrorLogWriter writer = mock(ExternalApiErrorLogWriter.class);
        CountDownLatch oldOpen = new CountDownLatch(1);
        CountDownLatch releaseOld = new CountDownLatch(1);
        CountDownLatch oldResponseClosed = new CountDownLatch(1);
        CountDownLatch newOpen = new CountDownLatch(1);
        AtomicInteger opens = new AtomicInteger();
        PipedInputStream newBody = new PipedInputStream();
        PipedOutputStream newOutput = new PipedOutputStream(newBody);
        FubonTaiexIndexStreamClient client = new FubonTaiexIndexStreamClient(
                true, true, "IR0001", "http://adapter:8080", "/token", ingestion,
                (endpoint, token) -> {
                    if (opens.incrementAndGet() == 1) {
                        oldOpen.countDown();
                        awaitIgnoringInterrupts(releaseOld);
                        return new FubonTaiexIndexStreamClient.RawResponse(503, new ByteArrayInputStream(new byte[0]) {
                            @Override public void close() throws java.io.IOException { super.close(); oldResponseClosed.countDown(); }
                        });
                    }
                    newOpen.countDown();
                    return new FubonTaiexIndexStreamClient.RawResponse(200, newBody);
                }, millis -> { }, path -> "token", writer);

        try {
            client.start();
            assertThat(oldOpen.await(2, TimeUnit.SECONDS)).isTrue();
            client.stop();
            client.start();
            assertThat(newOpen.await(2, TimeUnit.SECONDS)).isTrue();
            releaseOld.countDown();
            assertThat(oldResponseClosed.await(2, TimeUnit.SECONDS)).isTrue();
            verifyNoInteractions(writer);
            assertThat(client.isRunning()).isTrue();
        } finally {
            client.stop();
            newOutput.close();
            newBody.close();
        }
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                if (interrupted) Thread.currentThread().interrupt();
                return;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
    }
}
