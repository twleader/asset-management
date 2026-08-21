package com.steven.assets.service;

import com.steven.assets.dto.QuoteDetailDto;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MarketDataQuoteDetailServiceTest {
    private HttpServer server;
    private final AtomicReference<Reply> reply = new AtomicReference<>();
    private MarketDataService service;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/internal/quote-detail", exchange -> {
            Reply r = reply.get();
            byte[] body = r.body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(r.status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        service = new MarketDataService("http://127.0.0.1:" + server.getAddress().getPort(), null);
    }

    @AfterEach void stop() { server.stop(0); }

    @Test
    void proxy成功時完整透傳總量與時間型別() {
        reply.set(new Reply(200, """
                {"stockCode":"2330","stockName":"台積電","market":"台股","supported":true,"available":true,"source":"YAHOO_TW","message":null,
                "sourceTime":"2026-08-21T01:00:00Z","fetchedAt":"2026-08-21T01:02:00Z","marketStatus":"OPEN","price":100,"previousClose":99,
                "bidTotalLots":0,"askTotalLots":7,"levels":[]}"""));
        QuoteDetailDto.Response result = service.getQuoteDetail("2330", "台股");
        assertThat(result.available()).isTrue();
        assertThat(result.bidTotalLots()).isZero();
        assertThat(result.askTotalLots()).isEqualTo(7L);
        assertThat(result.sourceTime().toString()).isEqualTo("2026-08-21T01:00:00Z");
    }

    @Test
    void 空body五百與decode失敗均failSoft為typedUnavailable() {
        for (Reply r : new Reply[]{new Reply(200, ""), new Reply(500, "{}"), new Reply(200, "{bad")}) {
            reply.set(r);
            QuoteDetailDto.Response result = service.getQuoteDetail("2330", "台股");
            assertThat(result.supported()).isTrue();
            assertThat(result.available()).isFalse();
            assertThat(result.levels()).isEmpty();
        }
    }

    private record Reply(int status, String body) {}
}
