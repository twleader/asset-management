package com.steven.assets.bff.backuprestore;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.factory.RewritePathGatewayFilterFactory;
import org.springframework.cloud.gateway.handler.predicate.PathRoutePredicateFactory;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.cloud.gateway.support.RouteMetadataUtils;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BackupRestoreBffRoutesTest {

    @Test
    void backupRouteRelays到business且保留1500秒workflow的1530秒upstream期限() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(PathRoutePredicateFactory.class);
            context.registerBean(RewritePathGatewayFilterFactory.class);
            context.refresh();

            BackupRestoreBffRoutes config = new BackupRestoreBffRoutes();
            ReflectionTestUtils.setField(config, "businessServicesUrl", "http://business.test");
            List<Route> routes = config.backupRestoreRoutes(new RouteLocatorBuilder(context))
                    .getRoutes().collectList().block();
            Route route = routes.stream().filter(candidate -> candidate.getId().equals("backup-restore-route"))
                    .findFirst().orElseThrow();

            assertThat(route.getPredicate().toString()).contains("/api/bff/backup-restore/**");
            assertThat(route.getFilters().toString()).contains("/api/bff/backup-restore", "/api/backups");
            assertThat(route.getUri().toString()).startsWith("http://business.test");
            assertThat(route.getMetadata()).containsEntry(RouteMetadataUtils.RESPONSE_TIMEOUT_ATTR, 1_530_000);
        }
    }
}
