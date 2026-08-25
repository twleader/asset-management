package com.steven.assets.externalmaterials.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One non-queued worker keeps order-book persistence independent from price dispatch rounds. */
@Configuration
public class TwFubonOrderBookWriterConfig {

    @Bean(name = "twFubonOrderBookWriterExecutor", destroyMethod = "shutdownNow")
    public ExecutorService twFubonOrderBookWriterExecutor() {
        return new ThreadPoolExecutor(
                0, 1, 30, TimeUnit.SECONDS, new SynchronousQueue<>(),
                Thread.ofVirtual().name("tw-fubon-order-book-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
