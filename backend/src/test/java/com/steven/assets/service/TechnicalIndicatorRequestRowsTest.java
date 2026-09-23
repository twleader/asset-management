package com.steven.assets.service;

import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.model.UsIndexDailyHistory;
import com.steven.assets.repository.StockPriceHistoryRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import com.steven.assets.repository.UsIndexDailyHistoryRepository;
import com.steven.assets.util.MarketZones;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TechnicalIndicatorRequestRowsTest {
    @Test void nasdaqReusesOriginalExactDecimalMathAnd240RowsIncludingItsHalfCentBoundary() {
        var history = mock(UsIndexDailyHistoryRepository.class);
        var prices = mock(PriceQueryService.class);
        var service = new TechnicalIndicatorService(mock(StockPriceHistoryRepository.class), prices,
                mock(TwseIndexDailyHistoryRepository.class), history);
        for (int size : List.of(0, 7, 240, 500)) {
            List<UsIndexDailyHistory> rows = new ArrayList<>();
            for (int i = 0; i < size; i++) {
                var row = new UsIndexDailyHistory();
                row.setIndexCode("IXIC"); row.setTradingDate(LocalDate.of(2026, 9, 22).minusDays(i));
                BigDecimal price = i >= 240 ? new BigDecimal("9999999") : new BigDecimal("100.005").add(BigDecimal.valueOf(i));
                row.setClosePoint(price); row.setHighPoint(price.add(BigDecimal.ONE)); row.setLowPoint(price.subtract(BigDecimal.ONE));
                rows.add(row);
            }
            when(history.findTopNByIndexCodeOrderByTradingDateDesc("IXIC", 240)).thenReturn(rows.stream().limit(240).toList());
            var old = service.computeAllForNasdaq();
            clearInvocations(history);
            assertThat(service.computeAllForNasdaqFromRows(rows)).isEqualTo(old);
            verifyNoInteractions(history, prices);
        }
    }

    @Test void taiexReusesOriginalDoubleAndKdMathWithLiveBlendAndRaw240Window() {
        var history = mock(TwseIndexDailyHistoryRepository.class);
        var prices = mock(PriceQueryService.class);
        var service = new TechnicalIndicatorService(mock(StockPriceHistoryRepository.class), prices,
                history, mock(UsIndexDailyHistoryRepository.class));
        LocalDate today = LocalDate.now(MarketZones.TW_ZONE);
        for (boolean currentRow : List.of(false, true)) {
            List<TwseIndexDailyHistory> rows = twRows(currentRow ? today : today.minusDays(1), 500);
            Optional<PriceQueryService.LivePrice> live = Optional.of(live(today));
            when(history.findTopNByOrderByTradingDateDesc(240)).thenReturn(rows.subList(0, 240));
            when(prices.getLive("0000", "台股")).thenReturn(live);
            var old = service.computeAll("0000", "台股");
            clearInvocations(history, prices);
            assertThat(service.computeAllForTaiexFromRows(rows, today, live)).isEqualTo(old);
            assertThat(old.ma10()).isNull();
            verifyNoInteractions(history, prices);
        }
    }

    @Test void taiexDisplayPureProjectionMatchesAllOriginalPhasesWithoutAnyIo() {
        var history = mock(TwseIndexDailyHistoryRepository.class);
        var prices = mock(PriceQueryService.class);
        var service = new TaiexDisplayPriceService(prices, history);
        LocalDate today = LocalDate.of(2026, 9, 23);
        for (var phase : PriceQueryService.DisplayPhase.values()) {
            for (boolean available : List.of(false, true)) {
                LocalDate target = phase == PriceQueryService.DisplayPhase.PREVIOUS_SESSION ? today.minusDays(1) : today;
                LocalDate latest = available ? target : target.minusDays(1);
                var rows = twRows(latest, 500);
                var live = available ? Optional.of(live(today)) : Optional.<PriceQueryService.LivePrice>empty();
                when(prices.displaySession("台股")).thenReturn(new PriceQueryService.DisplaySession(phase, target, today));
                when(prices.getLive("0000", "台股")).thenReturn(live);
                when(history.findTop60ByOrderByTradingDateDesc()).thenReturn(rows.subList(0, 60));
                when(history.findById(any())).thenAnswer(call -> rows.stream()
                        .filter(row -> row.getTradingDate().equals(call.getArgument(0))).findFirst());
                var old = service.resolve();
                clearInvocations(history, prices);
                Instant instant = Instant.parse(switch (phase) {
                    case OPEN -> "2026-09-23T02:00:00Z";
                    case AFTER_CLOSE -> "2026-09-23T08:00:00Z";
                    case PREVIOUS_SESSION -> "2026-09-23T00:00:00Z";
                });
                var sessions = new RadarObservationResolver.DecisionSessions(today,
                        phase == PriceQueryService.DisplayPhase.AFTER_CLOSE ? today : today.minusDays(1), true);
                assertThat(service.resolveFromRows(instant, sessions, rows, live)).isEqualTo(old);
                verifyNoInteractions(history, prices);
            }
        }
    }

    private static List<TwseIndexDailyHistory> twRows(LocalDate latest, int count) {
        List<TwseIndexDailyHistory> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            var row = new TwseIndexDailyHistory();
            row.setTradingDate(latest.minusDays(i));
            BigDecimal close = BigDecimal.valueOf(1000.005 + i * .11);
            row.setClosePoint(close); row.setOpenPoint(close); row.setHighPoint(close.add(BigDecimal.ONE));
            row.setLowPoint(close.subtract(BigDecimal.ONE)); rows.add(row);
        }
        return rows;
    }

    private static PriceQueryService.LivePrice live(LocalDate date) {
        return new PriceQueryService.LivePrice("0000", null, "台股", new BigDecimal("1001"),
                null, null, null, null, null, new BigDecimal("999"), new BigDecimal("1002"), new BigDecimal("998"),
                100L, date.toString(), date + "T10:00:00", false, "FUBON", "LIVE");
    }
}
