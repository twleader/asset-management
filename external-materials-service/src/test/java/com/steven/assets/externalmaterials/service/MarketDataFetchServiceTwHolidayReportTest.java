package com.steven.assets.externalmaterials.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class MarketDataFetchServiceTwHolidayReportTest {

    private final MarketDataFetchService service = new MarketDataFetchService(
            mock(StockSourceQuery.class), mock(TwTyphoonClosureService.class), "");

    @Test
    void parsesGregorianRowsForRequestedHistoricalYearAndSkipsTradingMarkers() {
        Map<String, String> holidays = service.parseTwseHolidayReport(2025, report(
                2025, 114,
                "[\n"
                        + " [\"2025-01-01\",\"中華民國開國紀念日\",\"放假\"],\n"
                        + " [\"2025-01-02\",\"國曆新年開始交易日\",\"開始交易\"],\n"
                        + " [\"2025-01-22\",\"農曆春節前最後交易日\",\"最後交易\"],\n"
                        + " [\"2025-01-23\",\"市場無交易，僅辦理結算交割作業\",\"\"]\n"
                        + "]"));

        assertThat(holidays).containsExactlyInAnyOrderEntriesOf(Map.of(
                "2025-01-01", "中華民國開國紀念日",
                "2025-01-23", "市場無交易，僅辦理結算交割作業"));
    }

    @Test
    void parsesCurrentYearWhenReportIdentityMatches() {
        assertThat(service.parseTwseHolidayReport(2026, report(
                2026, 115, "[[\"2026-01-01\",\"中華民國開國紀念日\",\"放假\"]]")))
                .containsEntry("2026-01-01", "中華民國開國紀念日");
    }

    @Test
    void rejectsWrongYearTitleDateRowsAndEmptyFutureReport() {
        assertThat(service.parseTwseHolidayReport(2025, report(
                2025, 115, "[[\"2025-01-01\",\"元旦\",\"\"]]"))).isEmpty();
        assertThat(service.parseTwseHolidayReport(2025, report(
                2026, 114, "[[\"2025-01-01\",\"元旦\",\"\"]]"))).isEmpty();
        assertThat(service.parseTwseHolidayReport(2025, report(
                2025, 114, "[[\"2026-01-01\",\"元旦\",\"\"]]"))).isEmpty();
        assertThat(service.parseTwseHolidayReport(2027, report(2027, 116, "[]"))).isEmpty();
    }

    @Test
    void rejectsFailedOrMalformedPayload() {
        assertThat(service.parseTwseHolidayReport(2025, "{\"stat\":\"error\"}")).isEmpty();
        assertThat(service.parseTwseHolidayReport(2025, "not-json")).isEmpty();
        assertThat(service.parseTwseHolidayReport(2025,
                "{\"stat\":\"ok\",\"date\":\"20250101\",\"title\":\"114 年市場開休市日期\","
                        + "\"fields\":[\"名稱\"],\"data\":[[\"元旦\"]]}"))
                .isEmpty();
    }

    private static String report(int gregorianYear, int rocYear, String rows) {
        return "{"
                + "\"stat\":\"ok\","
                + "\"date\":\"" + gregorianYear + "0101\","
                + "\"title\":\"" + rocYear + " 年市場開休市日期\","
                + "\"fields\":[\"日期\",\"名稱\",\"說明\"],"
                + "\"data\":" + rows
                + "}";
    }
}
