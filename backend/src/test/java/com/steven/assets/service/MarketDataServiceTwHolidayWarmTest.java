package com.steven.assets.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Requirement 163／Task 452.10：warmTwHolidaysIfExpiringWithin 的剩餘 TTL 閘門與不毒化快取。 */
class MarketDataServiceTwHolidayWarmTest {
    private static final Duration WINDOW = Duration.ofMinutes(6);
    private static final long NOW = 1_000_000_000L;

    private interface Body { String next(int call); }

    private static <T> T withServer(Body body, java.util.function.BiFunction<MarketDataService, AtomicInteger, T> test)
            throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/tw-holidays", exchange -> {
            String text = body.next(calls.incrementAndGet());
            if (text == null) {
                exchange.sendResponseHeaders(500, -1);
            } else {
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.start();
        try {
            return test.apply(new MarketDataService("http://127.0.0.1:" + server.getAddress().getPort(), null, null), calls);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fiveMinutesRemainingRefetchesAndResetsTtl() throws Exception {
        withServer(call -> "{\"2099-01-02\":\"new\"}", (service, calls) -> {
            service.seedTwHolidayCacheForTest(2099, Map.of("2099-01-01", "old"), NOW + Duration.ofMinutes(5).toMillis());
            assertThat(service.warmTwHolidaysIfExpiringWithin(WINDOW, 2099, NOW)).isTrue();
            assertThat(calls).hasValue(1);
            // 重設 TTL 為 10 分鐘：下一次（剩 10 分鐘 > 6 分鐘）不再重抓
            assertThat(service.warmTwHolidaysIfExpiringWithin(WINDOW, 2099, NOW)).isFalse();
            assertThat(calls).hasValue(1);
            return null;
        });
    }

    @Test
    void sevenMinutesRemainingDoesNotRefetch() throws Exception {
        withServer(call -> "{\"2099-01-02\":\"new\"}", (service, calls) -> {
            service.seedTwHolidayCacheForTest(2099, Map.of("2099-01-01", "old"), NOW + Duration.ofMinutes(7).toMillis());
            assertThat(service.warmTwHolidaysIfExpiringWithin(WINDOW, 2099, NOW)).isFalse();
            assertThat(calls).hasValue(0);
            return null;
        });
    }

    @Test
    void missingOrExpiredCacheIsFetched() throws Exception {
        withServer(call -> "{\"2099-01-02\":\"new\"}", (service, calls) -> {
            assertThat(service.warmTwHolidaysIfExpiringWithin(WINDOW, 2099, NOW)).isTrue();
            service.seedTwHolidayCacheForTest(2099, Map.of("2099-01-01", "old"), NOW - 1);
            assertThat(service.warmTwHolidaysIfExpiringWithin(WINDOW, 2099, NOW)).isTrue();
            assertThat(calls).hasValue(2);
            return null;
        });
    }

    @Test
    void fetchFailureKeepsPreviousCache() throws Exception {
        withServer(call -> null, (service, calls) -> {
            long future = System.currentTimeMillis() + Duration.ofMinutes(3).toMillis();
            service.seedTwHolidayCacheForTest(2099, Map.of("2099-01-05", "old holiday"), future);
            assertThat(service.warmTwHolidaysIfExpiringWithin(WINDOW, 2099, System.currentTimeMillis())).isFalse();
            assertThat(calls).hasValue(1);
            // 舊快取仍在：2099-01-05（週一）仍判定為假日
            assertThat(service.isTwTradingDayCachedOnly(LocalDate.of(2099, 1, 5))).contains(false);
            return null;
        });
    }
}
