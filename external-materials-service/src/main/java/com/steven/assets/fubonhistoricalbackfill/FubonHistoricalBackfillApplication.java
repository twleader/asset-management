package com.steven.assets.fubonhistoricalbackfill;

import com.steven.assets.externalmaterials.client.DgpaCalendarClient;
import com.steven.assets.externalmaterials.client.FubonMarketConfigState;
import com.steven.assets.externalmaterials.client.FubonNormalizedQuoteClient;
import com.steven.assets.externalmaterials.client.FubonScheduledMarketClient;
import com.steven.assets.externalmaterials.service.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

/** Explicit bean graph for the opt-in historical job. No component scan, web server, or scheduling. */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import({
        FubonMarketConfigState.class, FubonScheduledMarketClient.class, FubonNormalizedQuoteClient.class,
        DgpaCalendarClient.class, MarketDataFetchService.class, MarketCalendar.class, MarketClock.class,
        StockSourceQuery.class, TwTyphoonClosureService.class, TwMarketClosureQuery.class, IntradayTickStore.class,
        FubonRadarScope.class, FubonHistoricalDailyCandleStore.class, FubonMarketDataHistoryStore.class,
        FubonHistoricalBackfillReceiptStore.class, FubonHistoricalBackfillRunner.class,
        FubonHistoricalBackfillCampaignLock.class,
        ApiErrorLogDiagnosticRenderer.class, ExternalApiErrorLogWriter.class
})
public class FubonHistoricalBackfillApplication {
    public static void main(String[] args) {
        if (!hasBackfillCommand(args)) {
            System.err.println("{\"campaignOutcome\":\"REJECTED\",\"errorCode\":\"HISTORICAL_BACKFILL_COMMAND_REQUIRED\"}");
            System.exit(2);
            return;
        }
        SpringApplication application = new SpringApplication(FubonHistoricalBackfillApplication.class);
        application.setDefaultProperties(java.util.Map.of(
                "spring.main.web-application-type", "none",
                "spring.liquibase.enabled", "false",
                "fubon.historical-backfill-only", "true"));
        ConfigurableApplicationContext context = application.run(args);
        int code = SpringApplication.exit(context);
        if (code != 0) System.exit(code);
    }

    static boolean hasBackfillCommand(String[] args) {
        return java.util.Arrays.stream(args).anyMatch(arg -> arg.equals("--fubon-historical-backfill")
                || arg.startsWith("--fubon-historical-backfill="));
    }
}
