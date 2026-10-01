package com.steven.assets.externalmaterials;

import com.steven.assets.fubonhistoricalbackfill.FubonHistoricalBackfillApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import javax.sql.DataSource;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = FubonHistoricalBackfillApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"spring.liquibase.enabled=false", "fubon.enabled=false",
                "fubon.historical-backfill-only=true", "fubon.historical-backfill-enabled=false"})
@Import(FubonHistoricalBackfillIsolationTest.NoConnectDataSourceConfig.class)
class FubonHistoricalBackfillIsolationTest {
    @Autowired ApplicationContext context;
    @Autowired DataSource dataSource;

    @TestConfiguration(proxyBeanMethods = false)
    static class NoConnectDataSourceConfig {
        @Bean @Primary DataSource noConnectDataSource() { return mock(DataSource.class); }
    }

    @Test void oneShotEntryPointUsesExplicitImportsWithoutComponentScanOrScheduling() {
        Class<?> app = FubonHistoricalBackfillApplication.class;
        assertThat(app.isAnnotationPresent(SpringBootConfiguration.class)).isTrue();
        assertThat(app.isAnnotationPresent(EnableAutoConfiguration.class)).isTrue();
        assertThat(app.isAnnotationPresent(ComponentScan.class)).isFalse();
        assertThat(app.isAnnotationPresent(EnableScheduling.class)).isFalse();
        Import imports = app.getAnnotation(Import.class);
        assertThat(imports).isNotNull();
        assertThat(imports.value()).contains(com.steven.assets.externalmaterials.service.FubonHistoricalBackfillRunner.class,
                com.steven.assets.externalmaterials.service.FubonHistoricalBackfillReceiptStore.class,
                com.steven.assets.externalmaterials.service.FubonHistoricalDailyCandleStore.class,
                com.steven.assets.externalmaterials.service.FubonMarketDataHistoryStore.class);
        assertThat(imports.value()).doesNotContain(com.steven.assets.externalmaterials.ExternalMaterialsApplication.class,
                com.steven.assets.externalmaterials.service.FubonHistoricalDailyCandleSyncService.class,
                com.steven.assets.externalmaterials.client.FubonTaiexIndexStreamClient.class);
        assertThat(context.getBeansOfType(org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
        assertThat(context.getBeansOfType(org.springframework.scheduling.TaskScheduler.class)).isEmpty();
        assertThat(context.getBeansOfType(org.springframework.context.SmartLifecycle.class).keySet())
                .doesNotContain("fubonTaiexIndexStreamClient", "fubonStockPushStreamClient");
        assertThat(context.getBeansOfType(com.steven.assets.externalmaterials.service.FubonHistoricalDailyCandleSyncService.class)).isEmpty();
        verifyNoInteractions(dataSource);
    }
}
