package com.steven.assets.service.aiflow;

import com.steven.assets.repository.aiflow.AiFlowRepository;
import com.steven.assets.repository.aiflow.JdbcAiFlowRepository;
import java.time.LocalDate;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.annotation.PersistenceExceptionTranslationAdvisor;
import org.springframework.dao.annotation.PersistenceExceptionTranslationPostProcessor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.assertThat;

/** 真 Spring bean 建立及 production CGLIB exception translation；不以手動 new/mocks 取代啟動。 */
@Testcontainers
class AiFlowSpringContextTest {
    @Container
    static final PostgreSQLContainer<?> DB = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("ai_flow_context_test").withUsername("assets").withPassword("test-only");

    @Test
    void repositoryAndServicesStartWithRealClassBasedExceptionTranslation() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(DataSource.class,
                    () -> new DriverManagerDataSource(DB.getJdbcUrl(), DB.getUsername(), DB.getPassword()));
            context.registerBean(JdbcTemplate.class, () -> new JdbcTemplate(context.getBean(DataSource.class)));
            context.registerBean(PlatformTransactionManager.class,
                    () -> new DataSourceTransactionManager(context.getBean(DataSource.class)));
            context.registerBean(AiFlowOwnerPort.class, () -> new AiFlowOwnerPort() {
                public long configuredOwner() { return 7; }
                public long readOwner() { return 7; }
            });
            context.register(TranslationConfiguration.class, JdbcAiFlowRepository.class,
                    AiFlowService.class, AiFlowRetentionService.class, AiFlowCredentialService.class);
            context.refresh();

            AiFlowRepository repository = context.getBean(JdbcAiFlowRepository.class);
            assertThat(context.getBean(AiFlowRepository.class)).isSameAs(repository);
            assertThat(AopUtils.isCglibProxy(repository)).isTrue();
            assertThat(((Advised) repository).getAdvisors())
                    .anyMatch(PersistenceExceptionTranslationAdvisor.class::isInstance);
            assertThat(repository.databaseDate()).isEqualTo(context.getBean(JdbcTemplate.class)
                    .queryForObject("SELECT (transaction_timestamp() AT TIME ZONE 'Asia/Taipei')::date", LocalDate.class));
            assertThat(context.getBean(AiFlowService.class)).isNotNull();
            assertThat(context.getBean(AiFlowRetentionService.class)).isNotNull();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TranslationConfiguration {
        @Bean
        static PersistenceExceptionTranslationPostProcessor exceptionTranslation() {
            var processor = new PersistenceExceptionTranslationPostProcessor();
            processor.setProxyTargetClass(true);
            return processor;
        }
    }
}
