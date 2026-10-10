package com.steven.assets.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.integration.fubon.FubonConfigState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class ExternalRadarIntradayCandleClientTest {
    static final Instant NOW = Instant.parse("2026-10-08T01:14:00Z");
    static final LocalDate DAY = LocalDate.parse("2026-10-08");
    static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path secret;

    static ObjectNode fixture() throws Exception {
        return (ObjectNode) JSON.readTree("""
                {"schemaVersion":1,"tradingDate":"2026-10-08","asOf":"2026-10-08T01:14:00Z","stocks":[
                 {"stockCode":"2330","market":"台股","provider":"FUBON_SDK","status":"AVAILABLE","reason":null,
                  "sourceDate":"2026-10-08","requestStartedAt":"2026-10-08T01:13:50Z",
                  "capturedAt":"2026-10-08T01:13:59Z","lastCompletedAt":"2026-10-08T01:12:00Z",
                  "candles":[{"candleAt":"2026-10-08T01:11:00Z","open":"100.1","high":"102",
                   "low":"99","close":"101","volume":9223372036854775807}]}]}
                """);
    }
    @Test void strictDecodeRetainsFactsAndRejectsUnknownDuplicateNumericNoncanonicalFutureAndIncomplete() throws Exception {
        var good = fixture();
        assertThat(ExternalRadarIntradayCandleClient.decode(good.toString(), List.of("2330"), DAY, NOW))
                .containsKey("2330");
        List<ObjectNode> invalid = new ArrayList<>();
        var unknown = good.deepCopy(); unknown.put("extra", true); invalid.add(unknown);
        var canonical = good.deepCopy(); ((ObjectNode) canonical.at("/stocks/0/candles/0")).put("open", "100.10"); invalid.add(canonical);
        var numeric = good.deepCopy(); ((ObjectNode) numeric.at("/stocks/0/candles/0")).put("open", 100.1); invalid.add(numeric);
        var overflow = good.deepCopy(); ((ObjectNode) overflow.at("/stocks/0/candles/0")).put("volume", new java.math.BigInteger("9223372036854775808")); invalid.add(overflow);
        var future = good.deepCopy(); ((ObjectNode) future.at("/stocks/0")).put("capturedAt", NOW.plusSeconds(1).toString()); invalid.add(future);
        var incomplete = good.deepCopy(); ((ObjectNode) incomplete.at("/stocks/0/candles/0")).put("candleAt", "2026-10-08T01:13:00Z"); invalid.add(incomplete);
        var version = good.deepCopy(); version.put("schemaVersion", new java.math.BigInteger("4294967297")); invalid.add(version);
        var missing = good.deepCopy(); missing.putArray("stocks"); invalid.add(missing);
        for (var input : invalid)
            assertThatThrownBy(() -> ExternalRadarIntradayCandleClient.decode(input.toString(), List.of("2330"), DAY, NOW))
                    .isInstanceOf(IllegalArgumentException.class).hasMessage("INTRADAY_CANDLE_UPSTREAM_INVALID");
        String duplicate = good.toString().replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1");
        assertThatThrownBy(() -> ExternalRadarIntradayCandleClient.decode(duplicate, List.of("2330"), DAY, NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void clientChunksOnlyInternalGetsWithCredentialAndDropsFailedChunkWithoutRefreshOrRetry() throws Exception {
        Files.writeString(secret.resolve("internal-service-token"), "test-credential");
        List<String> seen = new ArrayList<>();
        WebClient client = WebClient.builder().baseUrl("http://external-materials-service:8080").exchangeFunction(request -> {
            assertThat(request.method().name()).isEqualTo("GET");
            assertThat(request.url().getPath()).isEqualTo("/internal/market-data/intraday-candles/batch-read");
            assertThat(request.headers().getFirst("X-Internal-Service-Token")).isEqualTo("test-credential");
            seen.add(request.url().getQuery());
            String codePart = request.url().getQuery().split("&")[0].substring("stockCodes=".length());
            List<String> codes = Arrays.asList(codePart.split(","));
            assertThat(codes).hasSizeLessThanOrEqualTo(30);
            if (seen.size() == 2) return Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build());
            ObjectNode root = JSON.createObjectNode(); root.put("schemaVersion", 1); root.put("tradingDate", DAY.toString()); root.put("asOf", NOW.toString());
            var stocks = root.putArray("stocks");
            for (String code : codes) { var stock = stocks.addObject(); stock.put("stockCode", code); stock.put("market", "台股"); stock.put("provider", "FUBON_SDK"); stock.put("status", "UNAVAILABLE"); stock.put("reason", "CAPTURE_MISSING"); stock.put("sourceDate", DAY.toString()); stock.putNull("requestStartedAt"); stock.putNull("capturedAt"); stock.putNull("lastCompletedAt"); stock.putArray("candles"); }
            return Mono.just(ClientResponse.create(HttpStatus.OK).header("Content-Type", MediaType.APPLICATION_JSON_VALUE).body(root.toString()).build());
        }).build();
        var adapter = new ExternalRadarIntradayCandleClient(client, new FubonConfigState("true", secret));
        var codes = java.util.stream.IntStream.range(1000, 1031).mapToObj(Integer::toString).toList();
        assertThat(adapter.read(codes, DAY, NOW)).hasSize(30);
        assertThat(seen).hasSize(2);
        assertThat(adapter.read(List.of("0000"), DAY, NOW)).isEmpty();
        assertThat(adapter.read(List.of("2330"), DAY.minusDays(1), NOW)).isEmpty();
        assertThat(seen).hasSize(2);
    }
    @Test void disabledOrMissingCredentialPerformsNoHttpWork() {
        WebClient client = WebClient.builder().exchangeFunction(request -> { throw new AssertionError("unexpected IO"); }).build();
        assertThat(new ExternalRadarIntradayCandleClient(client, new FubonConfigState("false", secret))
                .read(List.of("2330"), DAY, NOW)).isEmpty();
        assertThat(new ExternalRadarIntradayCandleClient(client, new FubonConfigState("true", secret))
                .read(List.of("2330"), DAY, NOW)).isEmpty();
    }
}
