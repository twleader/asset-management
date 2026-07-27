package com.steven.assets.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * {@code @Scheduled} 專用平台執行緒排程器。
 *
 * <p><b>背景（bug fix）</b>：本專案 {@code spring.threads.virtual.enabled=true}，Spring Boot 會為
 * {@code @Scheduled} 自動配置以 virtual thread 執行的 {@code SimpleAsyncTaskScheduler}。實測在此組態下，
 * 唯一的 {@code fixedDelay} 任務——{@code MarketAnalysisScheduler.pollBatches}（今日股市分析 Batch API 收尾
 * poller，Requirement 31）——不會週期執行：已 {@code ENDED} 的批次因而永遠停在 {@code PROCESSING}、無法收尾落
 * {@code OK}／寄信（連 12h 逾時判 FAILED 都不觸發）。其餘 7 個皆為 cron 任務，症狀不明顯。
 *
 * <p><b>修法</b>：定義一個名為 {@code taskScheduler} 的 {@link TaskScheduler} bean，使 Boot 的 virtual
 * 排程器 auto-config 退讓（{@code @ConditionalOnMissingBean(TaskScheduler.class)}），所有 {@code @Scheduled}
 * 改於平台執行緒的固定池上執行。一舉解掉兩種可能病因：(a) {@code fixedDelay} 在 virtual 排程器下未正確重排；
 * (b) 排程任務內同步呼叫 Anthropic SDK（OkHttp）時 virtual thread 阻塞／pinning。多執行緒池（pool=3）並避免
 * 8 個排程任務共用單執行緒時互相阻塞。Web／MVC 層仍維持 virtual threads，不受影響。
 */
@Configuration
public class SchedulingConfig {

    /**
     * 供 {@code @Scheduled} 使用的平台執行緒排程器。bean 名須為 {@code taskScheduler} 以被
     * {@code ScheduledAnnotationBeanPostProcessor} 採用並讓 Boot virtual 排程器退讓。
     */
    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(3);
        scheduler.setThreadNamePrefix("scheduled-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }
}
