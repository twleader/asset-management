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
        properties = {"fubon.enabled=false", "fubon.historical-backfill-only=true", "spring.liquibase.enabled=false"})
@Import(FubonHistoricalBackfillDefaultGateTest.NoConnectDataSourceConfig.class)
class FubonHistoricalBackfillDefaultGateTest {
    @Test void dedicatedEntrypointRequiresTheExplicitRunnerCommand() {
        assertThat(FubonHistoricalBackfillApplication.hasBackfillCommand(new String[]{})).isFalse();
        assertThat(FubonHistoricalBackfillApplication.hasBackfillCommand(
                new String[]{"--fubon.historical-backfill-only=false"})).isFalse();
        assertThat(FubonHistoricalBackfillApplication.hasBackfillCommand(
                new String[]{"--fubon-historical-backfill=run"})).isTrue();
    }

    @Autowired FubonHistoricalBackfillRunner runner;

    @Test void environmentGateDefaultsToFalse() {
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(runner, "featureEnabled")).isFalse();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class NoConnectDataSourceConfig {
        @Bean @Primary DataSource noConnectDataSource() { return mock(DataSource.class); }
    }
}
