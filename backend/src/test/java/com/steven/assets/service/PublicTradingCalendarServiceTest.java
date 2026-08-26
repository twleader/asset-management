package com.steven.assets.service;

import com.steven.assets.dto.PublicTradingCalendarDto;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Requirement 113: selected-year authority reads are once-only, typed, defensive, and independently available. */
class PublicTradingCalendarServiceTest {

    @Test
    void leapYearUsesEachAuthorityOnceAndDefensivelyCopiesItsHolidayMap() {
        MarketDataService marketData = mock(MarketDataService.class);
        StockPriceService stockPrices = mock(StockPriceService.class);
        Map<String, String> tw = new LinkedHashMap<>(Map.of("2024-01-01", "元旦"));
        when(marketData.getTwHolidays(2024)).thenReturn(tw);
        when(marketData.getUsHolidays(2024)).thenReturn(Map.of("2024-01-01", "New Year"));
        when(marketData.getUkHolidays(2024)).thenReturn(Map.of("2024-12-25", "Christmas"));
        when(stockPrices.getMarketStatus()).thenReturn(Map.of(
                "twMarketOpen", true, "usMarketOpen", false, "ukMarketOpen", false,
                "twTime", "2024-02-29T10:00:00", "twTradingDate", "2024-02-29"));

        PublicTradingCalendarDto.PublicTradingCalendarResponse result =
                new PublicTradingCalendarService(marketData, stockPrices).current(List.of("2024"));
        tw.clear();

        assertThat(result.year()).isEqualTo(2024);
        assertThat(result.days()).hasSize(366);
        assertThat(result.days().getFirst().date()).isEqualTo(LocalDate.of(2024, 1, 1));
        assertThat(result.days().getLast().date()).isEqualTo(LocalDate.of(2024, 12, 31));
        assertThat(result.holidays().tw()).singleElement()
                .extracting(PublicTradingCalendarDto.CalendarHoliday::name).isEqualTo("元旦");
        assertThat(result.markets()).extracting(PublicTradingCalendarDto.CalendarMarketDefinition::code)
                .containsExactly("TW", "US", "UK");
        assertThat(result.markets()).extracting(PublicTradingCalendarDto.CalendarMarketDefinition::regularTradingHours)
                .containsExactly("09:00-13:30", "09:30-16:00", "08:00-16:30");
        assertThat(result.marketStatus().tw().timezone()).isEqualTo("Asia/Taipei");
        assertThat(result.marketStatus().tw().marketOpen()).isTrue();
        verify(marketData).getTwHolidays(2024);
        verify(marketData).getUsHolidays(2024);
        verify(marketData).getUkHolidays(2024);
        verify(stockPrices).getMarketStatus();
        verifyNoMoreInteractions(marketData, stockPrices);
    }

    @Test
    void unavailableTwLeavesOnlyTwFlagsAndCountNullWhileOtherAuthoritiesRemainUsable() {
        MarketDataService marketData = mock(MarketDataService.class);
        StockPriceService stockPrices = mock(StockPriceService.class);
        when(marketData.getTwHolidays(2025)).thenReturn(Map.of());
        when(marketData.getUsHolidays(2025)).thenReturn(Map.of("2025-01-01", "New Year"));
        when(marketData.getUkHolidays(2025)).thenReturn(Map.of());
        when(stockPrices.getMarketStatus()).thenReturn(Map.of());

        PublicTradingCalendarDto.PublicTradingCalendarResponse result =
                new PublicTradingCalendarService(marketData, stockPrices).current(List.of("2025"));

        assertThat(result.days()).hasSize(365);
        assertThat(result.availability().tw().status())
                .isEqualTo(PublicTradingCalendarDto.CalendarAuthorityStatus.UNAVAILABLE);
        assertThat(result.availability().us().status())
                .isEqualTo(PublicTradingCalendarDto.CalendarAuthorityStatus.AVAILABLE);
        assertThat(result.tradingDayCount().tw()).isNull();
        assertThat(result.tradingDayCount().us()).isNotNull();
        assertThat(result.days()).allSatisfy(day -> {
            assertThat(day.twTrading()).isNull();
            assertThat(day.twHoliday()).isNull();
        });
        assertThat(result.holidays().tw()).isEmpty();
        verify(marketData).getTwHolidays(2025);
        verify(marketData).getUsHolidays(2025);
        verify(marketData).getUkHolidays(2025);
        verify(stockPrices).getMarketStatus();
        verifyNoMoreInteractions(marketData, stockPrices);
    }
}
