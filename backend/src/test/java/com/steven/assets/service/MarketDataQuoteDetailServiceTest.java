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
        reply.set(new Reply(200, completeSnapshot("FUBON_BOOKS")));
        QuoteDetailDto.Response result = service.getQuoteDetail("2330", "台股");
        assertThat(result.available()).isTrue();
        assertThat(result.bidTotalLots()).isEqualTo(15L);
        assertThat(result.askTotalLots()).isEqualTo(65L);
        assertThat(result.sourceTime().toString()).isEqualTo("2026-08-21T01:00:00Z");
    }

    @Test
    void 空body五百與decode失敗均failSoft為typedUnavailable() {
        for (Reply r : new Reply[]{new Reply(200, ""), new Reply(500, "{}"), new Reply(200, "{bad")}) {
            reply.set(r);
            QuoteDetailDto.Response result = service.getQuoteDetail("2330", "台股");
            assertThat(result.supported()).isTrue();
            assertThat(result.available()).isFalse();
            assertThat(result.source()).isNull();
            assertThat(result.marketStatus()).isEqualTo("UNKNOWN");
            assertThat(result.levels()).isEmpty();
        }
    }

    @Test
    void completeYahooAvailablePayloadIsProxiedButUnknownSourceRemainsFailSoft() {
        reply.set(new Reply(200, completeSnapshot("YAHOO_TW")));

        QuoteDetailDto.Response result = service.getQuoteDetail("2330", "台股");

        assertThat(result.supported()).isTrue();
        assertThat(result.available()).isTrue();
        assertThat(result.source()).isEqualTo("YAHOO_TW");
        assertThat(result.marketStatus()).isEqualTo("OPEN");
        assertThat(result.levels()).hasSize(5);

        reply.set(new Reply(200, completeSnapshot("UNTRUSTED")));
        QuoteDetailDto.Response unknown = service.getQuoteDetail("2330", "台股");
        assertThat(unknown.available()).isFalse();
        assertThat(unknown.source()).isNull();
        assertThat(unknown.levels()).isEmpty();
    }

    private static String completeSnapshot(String source) {
        return """
                {"stockCode":"2330","stockName":"台積電","market":"台股","supported":true,"available":true,
                "source":"%s","message":null,"sourceTime":"2026-08-21T01:00:00Z","fetchedAt":"2026-08-21T01:02:00Z",
                "marketStatus":"OPEN","price":100,"previousClose":99,"bidTotalLots":15,"askTotalLots":65,"levels":[
                {"level":1,"bidPrice":100,"bidVolumeLots":1,"askPrice":101,"askVolumeLots":11},
                {"level":2,"bidPrice":99,"bidVolumeLots":2,"askPrice":102,"askVolumeLots":12},
                {"level":3,"bidPrice":98,"bidVolumeLots":3,"askPrice":103,"askVolumeLots":13},
                {"level":4,"bidPrice":97,"bidVolumeLots":4,"askPrice":104,"askVolumeLots":14},
                {"level":5,"bidPrice":96,"bidVolumeLots":5,"askPrice":105,"askVolumeLots":15}]}
                """.formatted(source);
    }

    private record Reply(int status, String body) {}
}
