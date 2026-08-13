package com.steven.assets.externalmaterials.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** 三支 FX curl client 共用的有界 process runner。 */
final class CurlProcessSupport {

    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(8);

    private CurlProcessSupport() {
    }

    static Optional<String> get(String url) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(
                "curl", "-sS", "--fail-with-body",
                "--connect-timeout", "3", "--max-time", "6",
                "-H", "User-Agent: Mozilla/5.0", url);
        builder.redirectErrorStream(true);
        Process process = builder.start();

        AtomicReference<byte[]> output = new AtomicReference<>(new byte[0]);
        AtomicReference<IOException> readFailure = new AtomicReference<>();
        Thread drain = Thread.ofVirtual().name("fx-curl-output").start(() -> {
            try {
                output.set(process.getInputStream().readAllBytes());
            } catch (IOException ex) {
                readFailure.set(ex);
            }
        });

        boolean completed;
        try {
            completed = process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw ex;
        }
        if (!completed) {
            process.destroyForcibly();
            drain.join(1_000);
            return Optional.empty();
        }
        drain.join(1_000);
        if (readFailure.get() != null || process.exitValue() != 0) {
            return Optional.empty();
        }
        return Optional.of(new String(output.get(), StandardCharsets.UTF_8));
    }
}
