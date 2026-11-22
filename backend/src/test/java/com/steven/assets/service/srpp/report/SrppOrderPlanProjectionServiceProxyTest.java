package com.steven.assets.service.srpp.report;

import com.steven.assets.repository.SrppReportRepository;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.service.srpp.PublishedSwaggerIdentity;
import com.steven.assets.srpp.SrppPolicyRegistryService;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** Regression: the production transactional service must be proxyable during Spring startup. */
class SrppOrderPlanProjectionServiceProxyTest {
    @Test
    void startsAsAClassBasedTransactionalProxy() {
        try (var context = new AnnotationConfigApplicationContext(ProjectionConfiguration.class)) {
            assertThat(AopUtils.isCglibProxy(context.getBean(SrppProjectionService.class))).isTrue();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement(proxyTargetClass = true)
    static class ProjectionConfiguration {
        @Bean CurrentUserContext currentUserContext() { return mock(CurrentUserContext.class); }
        @Bean MarketDataService marketDataService() { return mock(MarketDataService.class); }
        @Bean SrppPolicyRegistryService srppPolicyRegistryService() { return mock(SrppPolicyRegistryService.class); }
        @Bean PublishedSwaggerIdentity publishedSwaggerIdentity() { return mock(PublishedSwaggerIdentity.class); }
        @Bean SrppReportAuthority srppReportAuthority() { return mock(SrppReportAuthority.class); }
        @Bean SrppReportRepository srppReportRepository() { return mock(SrppReportRepository.class); }
        @Bean PlatformTransactionManager transactionManager() { return new NoOpTransactionManager(); }
        @Bean com.steven.assets.service.PriceQueryService priceQueryService() { return mock(com.steven.assets.service.PriceQueryService.class); }
        @Bean SrppReportFubonCapturePort srppReportFubonCapturePort() { return mock(SrppReportFubonCapturePort.class); }
        /** Task 626: the v2 read-only constructor (cached quote reads, persisted Fubon capture, JSON mapper) stays a CGLIB proxy. */
        @Bean SrppProjectionService srppProjectionService(CurrentUserContext owner, MarketDataService calendar,
                                                           SrppPolicyRegistryService registry, PublishedSwaggerIdentity swagger,
                                                           SrppReportAuthority authority, SrppReportRepository repository,
                                                           com.steven.assets.service.PriceQueryService prices, SrppReportFubonCapturePort fubon) {
            return new SrppProjectionService(owner, calendar, registry, swagger, authority, repository, prices, fubon, new com.fasterxml.jackson.databind.ObjectMapper());
        }
    }

    private static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) { }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }
}
