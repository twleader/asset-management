package com.steven.assets.service;

import com.steven.assets.model.StockPriceHistory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RadarObservationResolverTest {

    private static final Instant TW_DECISION = Instant.parse("2026-08-07T04:00:00Z");

    @Test
    void preCloseDecisionFallsBackToLatestCompletedSessionNotFutureUntrustedRow() {
        List<StockPriceHistory> rows = List.of(
                row("2026-08-07", "999", null),
                row("2026-08-06", "100", "TWSE_MI_INDEX"));

        RadarObservationResolver.AcceptedPrice accepted =
                RadarObservationResolver.resolveAcceptedPrice(
                        rows, live("2026-08-06", "1"), "台股", TW_DECISION);

        assertEquals(new BigDecimal("100"), accepted.value());
        assertEquals(LocalDate.of(2026, 8, 6), accepted.tradingDate());
        assertEquals(RadarObservationResolver.Quality.COMPLETED_CLOSE, accepted.quality());
        assertFalse(accepted.liveAccepted());
        assertEquals(1, accepted.trustedCompletedRows().size());
    }

    @Test
    void futureLiveCannotBecomeAcceptedPrice() {
        RadarObservationResolver.AcceptedPrice accepted =
                RadarObservationResolver.resolveAcceptedPrice(
                        List.of(), live("2026-08-09", "200"), "台股", TW_DECISION);

        assertNull(accepted.value());
        assertEquals(RadarObservationResolver.Quality.INVALID, accepted.quality());
        assertEquals("CLOSE_PENDING", accepted.quoteStatus());
    }

    @Test
    void sameMarketDateLiveIsAcceptedAndSharedWithTechnicalSequence() {
        List<StockPriceHistory> rows = List.of(row("2026-08-06", "100", "TWSE_MI_INDEX"));

        RadarObservationResolver.AcceptedPrice accepted =
                RadarObservationResolver.resolveAcceptedPrice(
                        rows, live("2026-08-07", "101"), "台股", TW_DECISION);

        assertTrue(accepted.liveAccepted());
        assertEquals(new BigDecimal("101"), accepted.value());
        assertEquals(LocalDate.of(2026, 8, 7), accepted.tradingDate());
        assertEquals(1, accepted.trustedCompletedRows().size());
        assertEquals(LocalDate.of(2026, 8, 6), accepted.trustedCompletedRows().get(0).getTradingDate());
    }

    @Test
    void twCloseSourceMissingIsNotTrustedFallback() {
        RadarObservationResolver.AcceptedPrice accepted =
                RadarObservationResolver.resolveAcceptedPrice(
                        List.of(row("2026-08-06", "100", null)),
                        null, "台股", TW_DECISION);

        assertNull(accepted.value());
        assertEquals(RadarObservationResolver.Quality.MISSING, accepted.quality());
        assertEquals("CLOSE_PENDING", accepted.quoteStatus());
    }

    @Test
    void monthOldCompletedCloseIsMissingAndCannotDriveRules() {
        RadarObservationResolver.AcceptedPrice accepted =
                RadarObservationResolver.resolveAcceptedPrice(
                        List.of(row("2026-07-01", "100", "TWSE_MI_INDEX")),
                        null, "台股", TW_DECISION);

        assertNull(accepted.value());
        assertEquals(RadarObservationResolver.Quality.MISSING, accepted.quality());
        assertEquals("CLOSE_PENDING", accepted.quoteStatus());
    }

    @Test
    void weekendDecisionFallsBackToLatestCompletedSessionAndRejectsWeekendLive() {
        Instant saturday = Instant.parse("2026-08-08T04:00:00Z");
        RadarObservationResolver.AcceptedPrice accepted =
                RadarObservationResolver.resolveAcceptedPrice(
                        List.of(row("2026-08-07", "100", "TWSE_MI_INDEX")),
                        live("2026-08-08", "101"), "台股", saturday);

        assertEquals(new BigDecimal("100"), accepted.value());
        assertEquals(LocalDate.of(2026, 8, 7), accepted.tradingDate());
        assertEquals(RadarObservationResolver.Quality.COMPLETED_CLOSE, accepted.quality());
        assertEquals("VERIFIED_CLOSE", accepted.quoteStatus());
    }

    @Test
    void mondayPreOpenUsesFridayCompletedSessionButAcceptsCurrentLive() {
        Instant mondayPreOpen = Instant.parse("2026-08-10T00:30:00Z"); // 08:30 Taipei
        List<StockPriceHistory> rows = List.of(row("2026-08-07", "100", "TWSE_MI_INDEX"));
        RadarObservationResolver.AcceptedPrice fallback =
                RadarObservationResolver.resolveAcceptedPrice(
                        rows, null, "台股", mondayPreOpen,
                        date -> date.getDayOfWeek().getValue() <= 5);
        assertEquals(LocalDate.of(2026, 8, 7), fallback.tradingDate());
        assertEquals(new BigDecimal("100"), fallback.value());

        RadarObservationResolver.AcceptedPrice live =
                RadarObservationResolver.resolveAcceptedPrice(
                        rows, live("2026-08-10", "101"), "台股", mondayPreOpen,
                        date -> date.getDayOfWeek().getValue() <= 5);
        assertTrue(live.liveAccepted());
        assertEquals(LocalDate.of(2026, 8, 10), live.tradingDate());
    }

    @Test
    void marketCalendarHolidayFallsBackToPriorSessionAndRejectsHolidayLive() {
        Instant holiday = Instant.parse("2026-08-10T04:00:00Z"); // Monday, custom holiday
        Set<LocalDate> holidays = Set.of(LocalDate.of(2026, 8, 10));
        List<StockPriceHistory> rows = List.of(row("2026-08-07", "100", "TWSE_MI_INDEX"));
        RadarObservationResolver.AcceptedPrice accepted =
                RadarObservationResolver.resolveAcceptedPrice(
                        rows, live("2026-08-10", "101"), "台股", holiday,
                        date -> date.getDayOfWeek().getValue() <= 5 && !holidays.contains(date));
        assertFalse(accepted.liveAccepted());
        assertEquals(LocalDate.of(2026, 8, 7), accepted.tradingDate());
        assertEquals(new BigDecimal("100"), accepted.value());
    }

    private static StockPriceHistory row(String date, String close, String source) {
        return StockPriceHistory.builder()
                .stockCode("2330")
                .market("台股")
                .tradingDate(LocalDate.parse(date))
                .closePrice(new BigDecimal(close))
                .closeSource(source)
                .build();
    }

    private static PriceQueryService.LivePrice live(String date, String price) {
        return new PriceQueryService.LivePrice(
                "2330", null, "台股", new BigDecimal(price), null, null, null,
                null, null, null, null, null, null, date, "2026-08-08T04:00:00Z",
                false, "REDIS", "LIVE");
    }
}
