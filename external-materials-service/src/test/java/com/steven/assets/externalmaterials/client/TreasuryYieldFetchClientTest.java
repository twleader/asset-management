package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TreasuryYieldFetchClientTest {

    private static final Instant FETCHED_AT = Instant.parse("2026-08-09T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(FETCHED_AT, ZoneOffset.UTC);

    @Test
    void 官方Csv保留合法零與小數量級並產生完整batch() {
        TreasuryYieldFetchClient client = client(url -> "");
        String csv = """
                Date,3 Mo,5 Yr,10 Yr,30 Yr
                08/07/2026,0,4.745,4.125,4.500
                """;

        var batch = client.parseOfficialCsv(csv, 2026, "https://official.test/2026.csv", FETCHED_AT)
                .getFirst();

        assertThat(batch.complete()).isTrue();
        assertThat(batch.provider()).isEqualTo("US_TREASURY");
        assertThat(batch.tenors()).extracting(TreasuryYieldFetchClient.TenorQuote::tenor)
                .containsExactly("M3", "Y5", "Y10", "Y30");
        assertThat(value(batch, "M3")).isEqualByComparingTo("0.0000");
        assertThat(value(batch, "Y5")).isEqualByComparingTo("4.7450");
        assertThat(value(batch, "Y5")).isLessThan(new BigDecimal("10"));
        assertThat(batch.availableAt()).isEqualTo(Instant.parse("2026-08-08T04:00:00Z"));
        assertThat(batch.availabilityBasis()).isEqualTo("CONSERVATIVE_NEXT_MIDNIGHT_ET");
        assertThat(batch.contentHash()).hasSize(64);
    }

    @Test
    void partial官方只觸發一次Yahoo四ticker並保留每tenor來源manifest() {
        LocalDate date = LocalDate.of(2025, 1, 2);
        long timestamp = date.atTime(12, 0).atZone(ZoneId.of("America/New_York")).toEpochSecond();
        Map<String, String> closes = Map.of(
                "%5EIRX", "4.100", "%5EFVX", "4.200", "%5ETNX", "4.300", "%5ETYX", "4.400");
        var calls = new java.util.ArrayList<String>();
        TreasuryYieldFetchClient client = client(url -> {
            calls.add(url);
            if (url.contains("daily-treasury-rates.csv")) {
                return "Date,3 Mo,5 Yr,10 Yr,30 Yr\n01/02/2025,4.1,,4.3,4.4\n";
            }
            for (Map.Entry<String, String> entry : closes.entrySet()) {
                if (url.contains(entry.getKey())) return yahoo(timestamp, entry.getValue());
            }
            throw new IllegalStateException("unexpected URL " + url);
        });

        List<TreasuryYieldFetchClient.CurveBatch> batches = client.fetchYear(2025);

        assertThat(calls).hasSize(5); // 一次 official + 四條 Yahoo historical；不是逐 date 重抓
        assertThat(batches).hasSize(2);
        var official = batches.stream().filter(b -> "US_TREASURY".equals(b.provider())).findFirst().orElseThrow();
        var fallback = batches.stream().filter(b -> "YAHOO_PROXY".equals(b.provider())).findFirst().orElseThrow();
        assertThat(official.complete()).isFalse();
        assertThat(official.tenors()).hasSize(3);
        assertThat(fallback.complete()).isTrue();
        assertThat(fallback.tenors()).hasSize(4);
        assertThat(fallback.tenors()).extracting(TreasuryYieldFetchClient.TenorQuote::sourceUrl)
                .allMatch(url -> url.contains("query2.finance.yahoo.com/v8/finance/chart/%5E"));
        assertThat(new HashSet<>(fallback.tenors().stream()
                .map(TreasuryYieldFetchClient.TenorQuote::sourceUrl).toList())).hasSize(4);
        assertThat(fallback.tenors()).allMatch(q -> q.sourceUrl().contains(switch (q.tenor()) {
            case "M3" -> "IRX";
            case "Y5" -> "FVX";
            case "Y10" -> "TNX";
            case "Y30" -> "TYX";
            default -> "missing";
        }));
        assertThat(fallback.availableAt()).isEqualTo(FETCHED_AT); // historic proxy 不可倒灌至 2025 close
        assertThat(fallback.availabilityBasis()).isEqualTo("PROXY_CLOSE_CONSERVATIVE");
    }

    @Test
    void currentXml解析完整curve且currentAvailableAt不早於觀測時間() {
        TreasuryYieldFetchClient client = client(url -> "");
        String xml = """
                <feed xmlns:m="urn:m" xmlns:d="urn:d">
                  <entry><content><m:properties>
                    <d:NEW_DATE>2026-08-08T00:00:00</d:NEW_DATE>
                    <d:BC_3MONTH>0</d:BC_3MONTH><d:BC_5YEAR>4.1</d:BC_5YEAR>
                    <d:BC_10YEAR>4.2</d:BC_10YEAR><d:BC_30YEAR>4.3</d:BC_30YEAR>
                  </m:properties></content></entry>
                </feed>
                """;

        var batch = client.parseOfficialXml(xml, 2026, "https://official.test/yield.xml", FETCHED_AT)
                .getFirst();

        assertThat(batch.complete()).isTrue();
        assertThat(batch.availableAt()).isEqualTo(FETCHED_AT);
        assertThat(batch.availabilityBasis()).isEqualTo("OBSERVED_CURRENT_FETCH");
        assertThat(value(batch, "M3")).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void canonicalHash固定順序且missing有明確token() {
        String a = TreasuryYieldFetchClient.canonicalHash(Map.of("Y5", new BigDecimal("4.2000")));
        String b = TreasuryYieldFetchClient.canonicalHash(Map.of("Y5", new BigDecimal("4.2")));
        String complete = TreasuryYieldFetchClient.canonicalHash(Map.of(
                "M3", BigDecimal.ZERO, "Y5", new BigDecimal("4.2"),
                "Y10", new BigDecimal("4.3"), "Y30", new BigDecimal("4.4")));
        assertThat(a).isEqualTo(b).isNotEqualTo(complete);
    }

    private static TreasuryYieldFetchClient client(TreasuryYieldFetchClient.TextTransport transport) {
        return new TreasuryYieldFetchClient(new ObjectMapper(), CLOCK, transport);
    }

    private static BigDecimal value(TreasuryYieldFetchClient.CurveBatch batch, String tenor) {
        return batch.tenors().stream().filter(q -> tenor.equals(q.tenor())).findFirst().orElseThrow().yieldPercent();
    }

    private static String yahoo(long timestamp, String close) {
        return """
                {"chart":{"result":[{"meta":{"exchangeTimezoneName":"America/New_York"},
                "timestamp":[%d],"indicators":{"quote":[{"close":[%s]}]}}],"error":null}}
                """.formatted(timestamp, close);
    }
}
