package com.steven.assets.externalmaterials.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** USD/TWD 外部輪詢的專用單工 worker，不佔住 Spring 全域 scheduler thread。 */
@Configuration
public class UsdTwdLiveExecutorConfig {

    @Bean(name = "usdTwdLiveUpdateExecutor", destroyMethod = "shutdownNow")
    public ExecutorService usdTwdLiveUpdateExecutor() {
        return Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("usd-twd-live-worker-", 0).factory());
    }
}
