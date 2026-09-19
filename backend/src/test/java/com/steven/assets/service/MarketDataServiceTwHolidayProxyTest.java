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

    /**
     * Task 447.2：{@code getTwHolidays(year, requestScopedCache)} 必須讓同一年度在同一個
     * cache 生命週期內只觸發一次 {@code fetchTwHolidaysFromExt}（此處用真實 HTTP 呼叫計數器
     * 直接量測，而非「程式碼看起來對」）。對照組沿用既有無快取版本
     * {@link #consecutiveNonCurrentYearReadsCanObserveDgpaThenTwseUpgrade}：同樣呼叫兩次會
     * 打到 server 兩次；帶 cache 呼叫十次也只打一次。
     */
    @Test
    void requestScopedCacheCallsHolidayProxyOnceRegardlessOfRepeatedYearLookups() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/tw-holidays", exchange -> {
            calls.incrementAndGet();
            String body = "{\"2099-01-01\":\"DGPA provisional\"}";
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
            Map<Integer, Map<String, String>> requestScopedCache = new java.util.HashMap<>();

            Map<String, String> first = service.getTwHolidays(2099, requestScopedCache);
            for (int i = 0; i < 9; i++) {
                assertThat(service.getTwHolidays(2099, requestScopedCache)).isEqualTo(first);
            }
            // 2099-01-05 是週一（非週末，不會被短路）且不在假日清單內 → 交易日；
            // 驗證 isTwTradingDayKnown(date, cache) 也共用同一份年度快取，不再另外打一次 proxy。
            assertThat(service.isTwTradingDayKnown(LocalDate.of(2099, 1, 5), requestScopedCache))
                    .contains(true);
            assertThat(calls).as("同一年度在同一 requestScopedCache 生命週期內只應打一次 proxy").hasValue(1);

            Map<Integer, Map<String, String>> anotherRequestCache = new java.util.HashMap<>();
            service.getTwHolidays(2099, anotherRequestCache);
            assertThat(calls).as("不同 requestScopedCache 實例互不污染，各自獨立計數").hasValue(2);
        } finally {
            server.stop(0);
        }
    }
}
