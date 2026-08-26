package com.steven.assets.externalmaterials.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** One non-queued Yahoo round worker keeps optional book recovery off the ten-second dispatcher. */
@Configuration
public class TwYahooOrderBookFallbackConfig {

    @Bean(name = "twYahooOrderBookFallbackExecutor", destroyMethod = "shutdownNow")
    public ExecutorService twYahooOrderBookFallbackExecutor() {
        return new ThreadPoolExecutor(
                0, 1, 30, TimeUnit.SECONDS, new SynchronousQueue<>(),
                Thread.ofVirtual().name("tw-yahoo-order-book-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }
}
