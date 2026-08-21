package com.steven.assets.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.dto.TradingCalendarExportDto;
import com.steven.assets.service.export.DualFormatExportWriter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

class TradingCalendarExportYearPairTest {
    @TempDir Path output;

    @Test
    void illegalSubpathFailsBeforeAnyAuthorityRead() {
        MarketDataService market = mock(MarketDataService.class);
        TradingCalendarExportService service = service(market);

        assertThatThrownBy(() -> service.exportYearPairToDir(2026, "../escape"))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(market);
    }

    @Test
    void knownBothYearsProduceSortedTwoResultFourFiles() {
        MarketDataService market = mock(MarketDataService.class);
        givenKnownMarkets(market);

        TradingCalendarExportDto.RangeRunResponse result = service(market).exportYearPairToDir(2024, "out");

        assertThat(result.years()).containsExactly(2024, 2025);
        assertThat(result.results()).extracting(TradingCalendarExportDto.RunResponse::year)
                .containsExactly(2024, 2025);
        assertThat(result.results()).allSatisfy(r -> {
            assertThat(r.path()).endsWith(".xlsx");
            assertThat(r.jsonPath()).endsWith(".json");
        });
        assertThat(result.results()).extracting(TradingCalendarExportDto.RunResponse::totalDays)
                .containsExactly(366, 365);
        assertThat(output.resolve("out/交易日曆_2024.xlsx")).exists();
        assertThat(output.resolve("out/交易日曆_2024.json")).exists();
        assertThat(output.resolve("out/交易日曆_2025.xlsx")).exists();
        assertThat(output.resolve("out/交易日曆_2025.json")).exists();
        for (int year : List.of(2024, 2025)) {
            verify(market, times(1)).getTwHolidays(year);
            verify(market, times(1)).getUsHolidays(year);
            verify(market, times(1)).getUkHolidays(year);
        }
    }

    @Test
    void unavailableFirstYearDoesNotBlockSecondYear() {
        MarketDataService market = mock(MarketDataService.class);
        givenKnownMarkets(market);
        when(market.getTwHolidays(2024)).thenReturn(Map.of());

        TradingCalendarExportDto.RangeRunResponse result = service(market).exportYearPairToDir(2024, "out");

        assertThat(result.results().get(0).path()).isNull();
        assertThat(result.results().get(0).jsonPath()).isNull();
        assertThat(result.results().get(0).totalDays()).isZero();
        assertThat(result.results().get(0).localStatus()).contains("2024", "xlsx 略過", "json 略過");
        assertThat(result.results().get(1).path()).isNotNull();
        assertThat(output.resolve("out/交易日曆_2024.xlsx")).doesNotExist();
        assertThat(output.resolve("out/交易日曆_2025.xlsx")).exists();
    }

    @Test
    void secondYearExceptionDoesNotEraseFirstYearResult() {
        MarketDataService market = mock(MarketDataService.class);
        givenKnownMarkets(market);
        when(market.getTwHolidays(2025)).thenThrow(new RuntimeException("authority timeout"));

        TradingCalendarExportDto.RangeRunResponse result = service(market).exportYearPairToDir(2024, "out");

        assertThat(result.results().get(0).path()).isNotNull();
        assertThat(result.results().get(1).path()).isNull();
        assertThat(result.results().get(1).localStatus()).contains("2025", "authority timeout");
        assertThat(output.resolve("out/交易日曆_2024.json")).exists();
        assertThat(output.resolve("out/交易日曆_2025.json")).doesNotExist();
    }

    @Test
    void aggregateStatusKeepsBothYearsAndBothFormatsWithinColumnLimit() {
        String longPath = "/very/" + "long/".repeat(200);
        List<TradingCalendarExportDto.RunResponse> rows = List.of(
                row(2026, "xlsx 成功：" + longPath + "a.xlsx／json 成功：" + longPath + "a.json"),
                row(2027, "xlsx 失敗：" + longPath + "b.xlsx／json 略過：" + longPath + "b.json"));

        String status = TradingCalendarExportService.aggregateStatus(
                rows, TradingCalendarExportDto.RunResponse::localStatus, 500);

        assertThat(status).hasSizeLessThanOrEqualTo(500)
                .contains("2026 年", "2027 年", "xlsx", "json");
    }

    @Test
    void generatedJsonRetainsEachRequestedYearAndDayCount() throws Exception {
        MarketDataService market = mock(MarketDataService.class);
        givenKnownMarkets(market);
        service(market).exportYearPairToDir(2024, "out");

        ObjectMapper mapper = new ObjectMapper();
        var leap = mapper.readTree(Files.readString(output.resolve("out/交易日曆_2024.json")));
        var common = mapper.readTree(Files.readString(output.resolve("out/交易日曆_2025.json")));
        assertThat(leap.path("year").asInt()).isEqualTo(2024);
        assertThat(leap.path("days")).hasSize(366);
        assertThat(common.path("year").asInt()).isEqualTo(2025);
        assertThat(common.path("days")).hasSize(365);
    }

    private static TradingCalendarExportDto.RunResponse row(int year, String localStatus) {
        return TradingCalendarExportDto.RunResponse.builder()
                .year(year).localStatus(localStatus).build();
    }

    private static void givenKnownMarkets(MarketDataService market) {
        when(market.getTwHolidays(anyInt())).thenAnswer(inv -> {
            int year = inv.getArgument(0);
            return Map.of(year + "-01-01", "元旦");
        });
        when(market.getUsHolidays(anyInt())).thenAnswer(inv -> {
            int year = inv.getArgument(0);
            return Map.of(year + "-01-01", "New Year");
        });
        when(market.getUkHolidays(anyInt())).thenAnswer(inv -> {
            int year = inv.getArgument(0);
            return Map.of(year + "-01-01", "New Year");
        });
        when(market.isTwTradingDay(any())).thenReturn(true);
        when(market.isUsTradingDay(any())).thenReturn(true);
        when(market.isUkTradingDay(any())).thenReturn(true);
    }

    private TradingCalendarExportService service(MarketDataService market) {
        return new TradingCalendarExportService(market, new ObjectMapper(),
                new DualFormatExportWriter(mock(GdriveOutputSupport.class)), output.toString());
    }
}
