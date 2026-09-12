package com.steven.assets.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDataServiceTwHolidayProxyTest {

    @Test
    void cachedOnlyTaiwanCalendarMissFailsClosedWithoutCallingHolidayProxy() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/tw-holidays", exchange -> {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(500, -1);
            exchange.close();
        });
        server.start();
        try {
            MarketDataService service = new MarketDataService(
                    "http://127.0.0.1:" + server.getAddress().getPort(), null, null);

            assertThat(service.isTwTradingDayCachedOnly(LocalDate.of(2099, 1, 2))).isEmpty();
            assertThat(service.futureTradingSessionsCachedOnly(
                    "台股", LocalDate.of(2099, 1, 1), 20, 90)).isEmpty();
            assertThat(calls).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void consecutiveNonCurrentYearReadsCanObserveDgpaThenTwseUpgrade() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/tw-holidays", exchange -> {
            int call = calls.incrementAndGet();
            String body = call == 1
                    ? "{\"2099-01-01\":\"DGPA provisional\"}"
                    : "{\"2099-01-02\":\"TWSE authority\"}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            MarketDataService service = new MarketDataService(
                    "http://127.0.0.1:" + server.getAddress().getPort(), null, null);

            assertThat(service.getTwHolidays(2099))
                    .containsExactlyEntriesOf(Map.of("2099-01-01", "DGPA provisional"));
            assertThat(service.getTwHolidays(2099))
                    .containsExactlyEntriesOf(Map.of("2099-01-02", "TWSE authority"));
            assertThat(calls).hasValue(2);
        } finally {
            server.stop(0);
        }
    }
}
