package com.steven.assets.integration.marketdata;

import com.steven.assets.integration.crawler.CrawlerRunHttpAdapter;
import com.steven.assets.service.port.CrawlerRunPort;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real HTTP fixtures protect the exact internal routes while services move behind ports. */
class MarketDataPortHttpContractTest {
    private HttpServer server;
    private String url;
    private final BlockingQueue<Request> requests = new LinkedBlockingQueue<>();
    private volatile int status = 200;
    private volatile String body = "{}";
    private record Request(String method, String path, Map<String, String> query) {}

    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            Map<String, String> query = new LinkedHashMap<>();
            String raw = exchange.getRequestURI().getRawQuery();
            if (raw != null) for (String part : raw.split("&")) {
                String[] keyValue = part.split("=", 2);
                query.put(URLDecoder.decode(keyValue[0], StandardCharsets.UTF_8),
                        URLDecoder.decode(keyValue.length == 1 ? "" : keyValue[1], StandardCharsets.UTF_8));
            }
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), query));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, status == 204 ? -1 : bytes.length);
            if (status != 204) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test void marketReadCapabilitiesRetainEveryVerbPathAndQuery() throws Exception {
        var adapter = new MarketDataReadHttpAdapter(url);
        var selector = Map.of("code", "0050", "market", "台股");
        adapter.quoteDetail("0050", "台股");
        expect("GET", "/internal/quote-detail", selector);
        adapter.dividendRate("0050", "台股");
        expect("GET", "/internal/dividend-rate", selector);
        adapter.etfHoldings("0050", "台股");
        expect("GET", "/internal/etf-holdings", selector);
        adapter.dividendHistory("0050", "台股", 10);
        expect("GET", "/internal/dividend-history", Map.of("code", "0050", "market", "台股", "years", "10"));
        body = "{\"closedToday\":true}";
        assertThat(adapter.detectTwClosure()).isTrue();
        expect("POST", "/internal/tw-closure/detect", Map.of());
        body = "{\"2026-10-26\":\"休市\"}";
        assertThat(adapter.twHolidays(2026)).containsEntry("2026-10-26", "休市");
        expect("GET", "/internal/tw-holidays", Map.of("year", "2026"));
    }

    @Test void historicalCapabilitiesPreserveOptionalQueriesAndFixedMaintenanceOperations() throws Exception {
        var adapter = new HistoricalDataHttpAdapter(url);
        var date = LocalDate.of(2026, 10, 26);
        adapter.intradaySession("0050", "台股", null, false);
        expect("GET", "/internal/intraday-ticks", Map.of("code", "0050", "market", "台股"));
        adapter.intradaySession("0050", "台股", date, true);
        expect("GET", "/internal/intraday-ticks", Map.of("code", "0050", "market", "台股", "date", date.toString(), "readOnly", "true"));
        body = "[]";
        assertThat(adapter.intraday5m("0050", "台股", 3)).isEmpty();
        expect("GET", "/internal/intraday-5m", Map.of("code", "0050", "market", "台股", "daysBack", "3"));
        body = "{\"records\":7}";
        assertThat(adapter.backfillStock("0050", "台股", date, null)).containsEntry("records", 7);
        expect("POST", "/internal/backfill/stock", Map.of("code", "0050", "market", "台股", "since", date.toString()));
        adapter.backfillStock("0050", "台股", date, date.plusDays(1));
        expect("POST", "/internal/backfill/stock", Map.of("code", "0050", "market", "台股", "since", date.toString(), "until", date.plusDays(1).toString()));
        adapter.backfillAll();
        expect("POST", "/internal/backfill/all", Map.of());
        adapter.backfillExchangeRate("USD", date);
        expect("POST", "/internal/backfill/exchange-rate", Map.of("currency", "USD", "since", date.toString()));
        adapter.backfillExchangeRateFrom("USD", date);
        expect("POST", "/internal/backfill/exchange-rate-from", Map.of("currency", "USD", "since", date.toString()));
        adapter.backfillCommodity("GOLD", date);
        expect("POST", "/internal/backfill/commodity", Map.of("code", "GOLD", "since", date.toString()));
        adapter.backfillCommodityFrom("GOLD", date);
        expect("POST", "/internal/backfill/commodity-from", Map.of("code", "GOLD", "since", date.toString()));
        adapter.refreshExchangeRateBot("USD");
        expect("POST", "/internal/exchange-rate/refresh-bot", Map.of("currency", "USD"));
        adapter.refreshCommodities();
        expect("POST", "/internal/commodity/live-refresh", Map.of());
        body = "{\"name\":\"台灣50\"}";
        assertThat(adapter.stockName("0050", "台股")).containsEntry("name", "台灣50");
        expect("GET", "/internal/stock-name", Map.of("code", "0050", "market", "台股"));
    }

    @Test void macroTransportKeepsDecimalCodecAndEachQuery() throws Exception {
        var adapter = new MacroDataHttpAdapter(url);
        body = "{\"2026\":123.4500}";
        assertThat(adapter.imf("NGDPDPC", "TWN", 2)).containsKey("2026");
        expect("GET", "/internal/macro/imf", Map.of("indicator", "NGDPDPC", "country", "TWN", "scale", "2"));
        body = "{}";
        adapter.dgbas();
        expect("GET", "/internal/macro/dgbas", Map.of());
        body = "[]";
        adapter.twseMonthly(YearMonth.of(2026, 10));
        expect("GET", "/internal/macro/twse-monthly", Map.of("year", "2026", "month", "10"));
        body = "{\"tradingDate\":\"2026-10-26\",\"close\":123.4500}";
        assertThat(adapter.twseReturnIndex(LocalDate.of(2026, 10, 26)).close()).isEqualByComparingTo("123.4500");
        expect("GET", "/internal/macro/twse-return-index", Map.of("date", "2026-10-26"));
        // The existing 16 MiB codec is necessary for a ten-year response; default 256 KiB would fail.
        body = "[" + String.join(",", java.util.Collections.nCopies(6000,
                "{\"tradingDate\":\"2026-10-26\",\"close\":123.4500,\"volume\":987654321,\"value\":9999.1200}")) + "]";
        assertThat(adapter.usIndex("SPX")).hasSize(6000);
        expect("GET", "/internal/macro/us-index", Map.of("code", "SPX"));
        body = "[{\"time\":\"2026-10-26T09:00:00\",\"close\":123.45}]";
        assertThat(adapter.indexIntraday("TWSE")[0].close()).isEqualByComparingTo("123.45");
        expect("GET", "/internal/macro/index-intraday", Map.of("market", "TWSE"));
    }

    @Test void readonlyErrorsStaySanitizedAndSyncAndRefreshRemainFixed() throws Exception {
        var read = new ReadOnlyIntradayHttpAdapter(url);
        var date = LocalDate.of(2026, 10, 26);
        body = "{\"tradingDate\":\"2026-10-26\",\"readStatus\":\"AVAILABLE\",\"ticks\":null}";
        assertThat(read.readIntradayTicks("0050", "台股", date).ticks()).isEmpty();
        expect("GET", "/internal/intraday-ticks-readonly", Map.of("code", "0050", "market", "台股", "date", date.toString()));
        status = 503; body = "sensitive downstream details";
        assertThatThrownBy(() -> read.readIntradayTicks("0050", "台股", date))
                .isInstanceOf(IllegalStateException.class).hasMessage("readonly intraday downstream status unavailable");
        requests.take();
        status = 204;
        assertThatThrownBy(() -> read.readIntradayTicks("0050", "台股", date))
                .hasMessage("readonly intraday downstream body unavailable");
        requests.take();
        new DividendSyncHttpAdapter(url).sync("0050", "台股");
        expect("POST", "/internal/dividend/sync", Map.of("code", "0050", "market", "台股"));
        status = 200; body = "{}";
        var refresh = new PriceRefreshHttpAdapter(url);
        refresh.refreshTradingRadarPrices();
        expect("POST", "/internal/refresh/tw-radar", Map.of());
        assertThat(refresh.triggerRefresh()).containsEntry("triggered", true);
        expect("POST", "/internal/refresh", Map.of());
    }

    @Test void crawlerCapabilitySetCannotChooseAnArbitraryUrl() throws Exception {
        var adapter = new CrawlerRunHttpAdapter(url);
        body = "{\"status\":\"OK\"}";
        String[] routes = {"export-now", "fetch-and-export-now", "public-rescan"};
        for (var operation : CrawlerRunPort.Operation.values()) {
            assertThat(adapter.run(operation, 3, null).status()).isEqualTo("OK");
            expect("POST", "/internal/news-poller/" + routes[operation.ordinal()], Map.of());
        }
    }

    private void expect(String method, String path, Map<String, String> query) throws Exception {
        assertThat(requests.poll(2, TimeUnit.SECONDS)).isEqualTo(new Request(method, path, query));
    }
}
