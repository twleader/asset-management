package com.steven.assets.fubonhistoricalbackfill;

import com.steven.assets.externalmaterials.service.FubonHistoricalBackfillRunner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

@SpringBootTest(classes = FubonHistoricalBackfillApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {"FUBON_HISTORICAL_BACKFILL_ENABLED=true", "fubon.enabled=false",
                "fubon.historical-backfill-only=true", "spring.liquibase.enabled=false"})
@Import(FubonHistoricalBackfillPropertyBindingTest.NoConnectDataSourceConfig.class)
class FubonHistoricalBackfillPropertyBindingTest {
    @Autowired FubonHistoricalBackfillRunner runner;

    @Test void documentedEnvironmentVariableIsMappedToRunnerGate() {
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(runner, "featureEnabled")).isTrue();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class NoConnectDataSourceConfig {
        @Bean @Primary DataSource noConnectDataSource() { return mock(DataSource.class); }
    }
}
