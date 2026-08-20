package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MacroDataFetchClientTpexTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void parsesNestedOfficialOhlcAndBothVolumeSchemasInRocDates() throws Exception {
        JsonNode ohlc = JSON.readTree("""
                {"stat":"ok","tables":[{"fields":["說明"],"data":[]},{"fields":["日期","開市","最高","最低","收市","漲/跌"],"data":[["2026/07/01","250.1","252.0","249.0","251.5","1.4"]]}]}
                """);
        List<MacroDataFetchClient.DailyOhlc> rows = MacroDataFetchClient.parseTpexMonthlyOhlc(ohlc, YearMonth.of(2026, 7));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.tradingDate()).isEqualTo(LocalDate.of(2026, 7, 1));
            assertThat(row.close()).isEqualByComparingTo("251.50");
            assertThat(row.volume()).isNull();
        });

        Map<LocalDate, BigDecimal> closes = Map.of(LocalDate.of(2026, 7, 1), new BigDecimal("251.50"));
        for (String field : List.of("成交張數", "成交股數（仟股）")) {
            JsonNode volume = JSON.readTree("""
                    {"stat":"ok","tables":[{"fields":["備註"],"data":[]},{"fields":["日期","%s","金額（仟元）","筆數","櫃買指數","漲/跌"],"data":[["115/07/01","1,234","100","3","251.50","1.4"]]}]}
                    """.formatted(field));
            assertThat(MacroDataFetchClient.parseTpexMonthlyVolumes(volume, YearMonth.of(2026, 7), closes))
                    .containsExactly(Map.entry(LocalDate.of(2026, 7, 1), 1_234_000L));
        }
    }

    @Test
    void rejectsIllegalOhlcAndKeepsVolumeFailureSoft() throws Exception {
        JsonNode illegal = JSON.readTree("""
                {"stat":"ok","tables":[{"fields":["日期","開市","最高","最低","收市"],"data":[["2026/07/01","250","249","251","250"]]}]}
                """);
        assertThat(MacroDataFetchClient.parseTpexMonthlyOhlc(illegal, YearMonth.of(2026, 7))).isNull();

        JsonNode failedVolume = JSON.readTree("""
                {"stat":"ok","tables":[{"fields":["日期","成交張數","櫃買指數"],"data":[["115/07/01"]]}]}
                """);
        assertThat(MacroDataFetchClient.parseTpexMonthlyVolumes(failedVolume, YearMonth.of(2026, 7),
                Map.of(LocalDate.of(2026, 7, 1), new BigDecimal("250")))).isEmpty();
    }

    @Test
    void parsesMisEpochMillisecondsWithoutTsAndFloors1333To1330() throws Exception {
        long nine = LocalDate.of(2026, 7, 1).atTime(9, 1).atZone(java.time.ZoneId.of("Asia/Taipei")).toInstant().toEpochMilli();
        long close = LocalDate.of(2026, 7, 1).atTime(13, 33).atZone(java.time.ZoneId.of("Asia/Taipei")).toInstant().toEpochMilli();
        JsonNode root = JSON.readTree("""
                {"rtmessage":"OK","staticObj":{"key":"otc_20260701"},"ohlcArray":[{"t":"%d","c":"251.1","ts":"090100"},{"t":"%d","c":"252.2"}]}
                """.formatted(nine, close));

        List<MacroDataFetchClient.IndexIntradayPoint> points = MacroDataFetchClient.parseTpexIndexIntraday(root);
        assertThat(points).hasSize(55);
        assertThat(points.getFirst().time()).isEqualTo("2026-07-01T09:00");
        assertThat(points.getFirst().close()).isEqualByComparingTo(new BigDecimal("251.10"));
        assertThat(points.getLast().time()).isEqualTo("2026-07-01T13:30");
        assertThat(points.getLast().close()).isEqualByComparingTo(new BigDecimal("252.20"));
    }

    @Test
    void tpexNeverRoutesThroughYahooMaps() throws Exception {
        for (String name : List.of("US_INDEX_YAHOO", "INDEX_INTRADAY_YAHOO")) {
            java.lang.reflect.Field field = MacroDataFetchClient.class.getDeclaredField(name);
            field.setAccessible(true);
            @SuppressWarnings("unchecked") Map<String, String> map = (Map<String, String>) field.get(null);
            assertThat(map).doesNotContainKey("TPEX");
        }
    }

    @Test
    void tpexOfficialRequestUsesLivePathsEncodedDatesAndRedirectFriendlyHeaders() {
        java.net.http.HttpRequest ohlc = MacroDataFetchClient.tpexOfficialRequest(
                "https://www.tpex.org.tw/www/zh-tw/indexInfo/inx?date=2026%2F08%2F01&response=json");
        java.net.http.HttpRequest volume = MacroDataFetchClient.tpexOfficialRequest(
                "https://www.tpex.org.tw/web/stock/aftertrading/daily_trading_index/st41_result.php?l=zh-tw&d=115%2F08&o=json");
        assertThat(ohlc.uri().getRawQuery()).isEqualTo("date=2026%2F08%2F01&response=json");
        assertThat(volume.uri().getRawQuery()).isEqualTo("l=zh-tw&d=115%2F08&o=json");
        assertThat(ohlc.headers().firstValue("Referer")).hasValue("https://www.tpex.org.tw/");
        assertThat(ohlc.headers().firstValue("Accept-Language")).hasValue("zh-TW,zh;q=0.9");
        assertThat(ohlc.headers().firstValue("User-Agent")).hasValueSatisfying(value ->
                assertThat(value).contains("Chrome/124.0.0.0"));
    }

    @Test
    void productionHttpClientFollowsOfficialRedirects() throws Exception {
        MacroDataFetchClient client = new MacroDataFetchClient();
        java.lang.reflect.Field field = MacroDataFetchClient.class.getDeclaredField("http");
        field.setAccessible(true);
        java.net.http.HttpClient http = (java.net.http.HttpClient) field.get(client);
        assertThat(http.followRedirects()).isEqualTo(java.net.http.HttpClient.Redirect.NORMAL);
    }

    @Test
    void volumeCloseMustMatchOhlcOrThatDayRemainsNull() throws Exception {
        JsonNode volume = JSON.readTree("""
                {"stat":"ok","tables":[{"fields":["日期","成交張數","櫃買指數"],"data":[["115/07/01","123","251.51"]]}]}
                """);
        assertThat(MacroDataFetchClient.parseTpexMonthlyVolumes(volume, YearMonth.of(2026, 7),
                Map.of(LocalDate.of(2026, 7, 1), new BigDecimal("251.50")))).isEmpty();
    }
}
