package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TwQuoteDetailFetchClientTest {
    private static final Instant NOW = Instant.parse("2026-08-21T01:02:03Z");

    @Test
    void fetch釘住Yahoo請求TAI映射算術padding總量與固定時間() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stub(http, 200, html(data("TAI", "open", "2026-08-21T01:00:00Z", "0", "2.0", "3", "3")));
        TwQuoteDetailFetchClient.QuoteDetailResult result = client(http).fetch("2330", "台股");
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().uri().toString()).isEqualTo("https://tw.stock.yahoo.com/quote/2330.TW");
        // HTTP/1.1 是 client builder 設定，HttpRequest 本身不會回填 version；以下釘住可觀測的 request 合約。
        assertThat(request.getValue().timeout()).contains(java.time.Duration.ofSeconds(12));
        assertThat(request.getValue().headers().firstValue("User-Agent")).hasValueSatisfying(v -> assertThat(v).contains("AssetManagementQuoteDetail/1.0"));
        assertThat(request.getValue().headers().firstValue("Accept-Encoding")).contains("identity");
        assertThat(result.available()).isTrue();
        assertThat(result.stockName()).isEqualTo("台積電");
        assertThat(result.fetchedAt()).isEqualTo(NOW);
        assertThat(result.sourceTime()).isEqualTo(Instant.parse("2026-08-21T01:00:00Z"));
        assertThat(result.marketStatus()).isEqualTo("OPEN");
        assertThat(result.price()).isEqualByComparingTo("100");
        assertThat(result.previousClose()).isEqualByComparingTo("100");
        assertThat(result.openPrice()).isEqualByComparingTo("99");
        assertThat(result.highPrice()).isEqualByComparingTo("110");
        assertThat(result.lowPrice()).isEqualByComparingTo("90");
        assertThat(result.averagePrice()).isEqualByComparingTo("100");
        assertThat(result.change()).isZero();
        assertThat(result.changePercent()).isZero();
        assertThat(result.turnoverYi()).isEqualByComparingTo("0.00");
        assertThat(result.volumeLots()).isZero();
        assertThat(result.previousVolumeLots()).isZero();
        assertThat(result.innerVolumeLots()).isEqualTo(3L);
        assertThat(result.outerVolumeLots()).isEqualTo(4L);
        assertThat(result.amplitudePercent()).isEqualByComparingTo("20.00");
        assertThat(result.innerPercent()).isEqualByComparingTo("42.86");
        assertThat(result.outerPercent()).isEqualByComparingTo("57.14");
        assertThat(result.levels()).hasSize(5);
        assertThat(result.levels().get(0).bidVolumeLots()).isEqualTo(2L);
        assertThat(result.levels().get(1).bidVolumeLots()).isNull();
        assertThat(result.bidTotalLots()).isEqualTo(2L);
        assertThat(result.askTotalLots()).isEqualTo(3L);
    }

    @Test
    void TWOcloseClosedUnknown與無regularMarketTime均依規格映射() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stub(http, 200, html(data("TWO", "close", null, "1", "0", "0", "0")));
        assertThat(client(http).fetch("2330", "台股").marketStatus()).isEqualTo("CLOSED");
        stub(http, 200, html(data("TWO", "closed", null, "1", "0", "0", "0")));
        assertThat(client(http).fetch("2330", "台股").marketStatus()).isEqualTo("CLOSED");
        stub(http, 200, html(data("TWO", "auction", "not-an-instant", "1", "0", "0", "0")));
        TwQuoteDetailFetchClient.QuoteDetailResult unknown = client(http).fetch("2330", "台股");
        assertThat(unknown.marketStatus()).isEqualTo("UNKNOWN");
        assertThat(unknown.sourceTime()).isNull();
    }

    @Test
    void totals全null局部null與合法零必須保留() throws Exception {
        HttpClient http = mock(HttpClient.class);
        stub(http, 200, html(data("TAI", "open", null, "1", "null", "null", "null")));
        TwQuoteDetailFetchClient.QuoteDetailResult empty = client(http).fetch("2330", "台股");
        assertThat(empty.bidTotalLots()).isNull();
        assertThat(empty.askTotalLots()).isNull();
        stub(http, 200, html(data("TAI", "open", null, "1", "0.0", "null", "0")));
        TwQuoteDetailFetchClient.QuoteDetailResult partial = client(http).fetch("2330", "台股");
        assertThat(partial.bidTotalLots()).isZero();
        assertThat(partial.askTotalLots()).isNull();
    }

    @Test
    void identityCoreSchema和傳輸失敗一律typedUnavailable() throws Exception {
        HttpClient http = mock(HttpClient.class);
        for (String broken : new String[]{
                data("NAS", "open", null, "1", "1", "1", "1"),
                data("TAI", "open", null, "1", "1", "1", "1").replace("\"systexId\":\"2330\"", "\"systexId\":\"9999\""),
                data("TAI", "open", null, "1", "1", "1", "1").replace("\"currency\":\"TWD\"", "\"currency\":\"USD\""),
                data("TAI", "open", null, "1", "1", "1", "1").replace("\"price\":{\"raw\":100}", "\"price\":null"),
                data("TAI", "open", null, "1", "1", "1", "1").replace("\"regularMarketPreviousClose\":{\"raw\":100}", "\"regularMarketPreviousClose\":{}"),
                data("TAI", "open", null, "1", "1", "1", "1").replace("\"orderbook\":[", "\"orderbook\":{")}) {
            stub(http, 200, html(broken));
            assertThat(client(http).fetch("2330", "台股").available()).isFalse();
        }
        stub(http, 503, "nope");
        assertThat(client(http).fetch("2330", "台股").available()).isFalse();
        stub(http, 200, "x".repeat(2 * 1024 * 1024 + 1));
        assertThat(client(http).fetch("2330", "台股").available()).isFalse();
        stub(http, 200, "\"quote\":{\"data\":{\"x\":1");
        assertThat(client(http).fetch("2330", "台股").available()).isFalse();
        stub(http, 200, "\"quote\":{\"data\":{} \"quote\":{\"data\":{}");
        assertThat(client(http).fetch("2330", "台股").available()).isFalse();
    }

    @Test
    void optional摘要與單格壞資料僅為null不降級核心完整snapshot() throws Exception {
        HttpClient http = mock(HttpClient.class);
        String optionalBroken = data("TAI", "open", null, "x", "not-a-number", "4", "x")
                .replace("\"regularMarketOpen\":{\"raw\":99}", "\"regularMarketOpen\":{\"raw\":0}")
                .replace("\"avgPrice\":100", "\"avgPrice\":null");
        stub(http, 200, html(optionalBroken));
        TwQuoteDetailFetchClient.QuoteDetailResult result = client(http).fetch("2330", "台股");
        assertThat(result.available()).isTrue();
        assertThat(result.openPrice()).isNull();
        assertThat(result.averagePrice()).isNull();
        assertThat(result.turnoverYi()).isNull();
        assertThat(result.levels().getFirst().bidVolumeLots()).isNull();
        assertThat(result.levels().getFirst().askVolumeLots()).isEqualTo(4L);
        assertThat(result.bidTotalLots()).isNull();
        assertThat(result.askTotalLots()).isEqualTo(4L);
    }

    @Test
    void 非台股或0000零外呼且scanner拒絕duplicateMarker() {
        HttpClient http = mock(HttpClient.class);
        TwQuoteDetailFetchClient c = client(http);
        assertThat(c.fetch("AAPL", "美股").supported()).isFalse();
        assertThat(c.fetch("0000", "台股").supported()).isFalse();
        verifyNoInteractions(http);
        assertThat(TwQuoteDetailFetchClient.extractQuoteData("root.App={x:undefined,\"quote\":{\"data\":{\"x\":\"{ }\\\"\"}}}")).isEqualTo("{\"x\":\"{ }\\\"\"}");
        assertThat(TwQuoteDetailFetchClient.extractQuoteData("\"quote\":{\"data\":{} \"quote\":{\"data\":{}")).isNull();
    }

    private static TwQuoteDetailFetchClient client(HttpClient http) {
        return new TwQuoteDetailFetchClient(http, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void stub(HttpClient http, int status, String body) throws Exception {
        HttpResponse response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
    }

    private static String html(String json) { return "root.App={undefined,\"quote\":{\"data\":" + json + "},tail:undefined}"; }

    private static String data(String exchange, String status, String time, String turnover, String bid, String ask, String in) {
        String regularTime = time == null ? "" : ",\"regularMarketTime\":\"" + time + "\"";
        return "{\"systexId\":\"2330\",\"symbolName\":\"台積電\",\"currency\":\"TWD\",\"exchange\":\"" + exchange + "\",\"marketStatus\":\"" + status + "\"" + regularTime
                + ",\"price\":{\"raw\":100},\"regularMarketPreviousClose\":{\"raw\":100},\"regularMarketOpen\":{\"raw\":99},\"regularMarketDayHigh\":{\"raw\":110},\"regularMarketDayLow\":{\"raw\":90},\"avgPrice\":100,\"change\":{\"raw\":0},\"changePercent\":\"0%\",\"turnoverM\":\"" + turnover + "\",\"volumeK\":\"0.0\",\"previousVolumeK\":\"0\",\"inMarket\":\"" + in + "\",\"outMarket\":\"4\",\"orderbook\":[{\"bid\":100,\"bidVolK\":\"" + bid + "\",\"ask\":101,\"askVolK\":\"" + ask + "\"}]}";
    }
}
