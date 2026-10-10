package com.steven.assets.externalmaterials.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.springframework.stereotype.Service;

/** Side-effect-free reader. No SDK, cursor, refresh, Redis write or fact reconstruction. */
@Service
public class FubonIntradayCandleReadService {
    private final FubonIntradayCandleRepository repository;
    private final MarketClock clock;
    private final MarketCalendar calendar;
    public FubonIntradayCandleReadService(FubonIntradayCandleRepository repository,
                                         MarketClock clock, MarketCalendar calendar) {
        this.repository=repository; this.clock=clock; this.calendar=calendar;
    }
    public FubonIntradayCandleBatch read(String stockCodes, String tradingDate, String rawAsOf) {
        List<String> codes;
        LocalDate date;
        Instant asOf;
        Instant now=clock.instant();
        try {
            if (stockCodes == null || tradingDate == null || rawAsOf == null
                    || !tradingDate.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")
                    || !rawAsOf.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z"))
                throw new IllegalArgumentException();
            codes=Arrays.asList(stockCodes.split(",",-1));
            date=LocalDate.parse(tradingDate); asOf=Instant.parse(rawAsOf);
            if (codes.isEmpty() || codes.size()>30 || codes.stream().distinct().count()!=codes.size()
                    || codes.stream().anyMatch(c -> !FubonMarketData.validSymbol(c) || "0000".equals(c))
                    || asOf.isAfter(now) || !date.equals(asOf.atZone(MarketClock.TW_ZONE).toLocalDate()))
                throw new IllegalArgumentException();
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("INVALID_REQUEST"); }
        LocalDate floor=FubonMinuteRetentionFloor.at(now);
        if (date.isBefore(floor)) return unavailable(codes,date,asOf,"RETENTION_EXPIRED");
        try {
            var known=calendar.peekTwTradingDayKnown(date);
            if (known.isEmpty()) return unavailable(codes,date,asOf,"CALENDAR_UNKNOWN");
            if (!known.get()) return unavailable(codes,date,asOf,"MARKET_CLOSED");
        } catch (RuntimeException unknown) { return unavailable(codes,date,asOf,"CALENDAR_UNKNOWN"); }
        try {
            List<FubonIntradayCandleRepository.Snapshot> snapshots=repository.read(codes,date,asOf,floor);
            var byCode=new java.util.HashMap<String,FubonIntradayCandleRepository.Snapshot>();
            for (var snapshot:snapshots) byCode.put(snapshot.stockCode(),snapshot);
            return new FubonIntradayCandleBatch(1,date,asOf,codes.stream()
                    .map(code -> stock(code,date,asOf,byCode.get(code))).toList());
        } catch (RuntimeException unavailable) { return unavailable(codes,date,asOf,"SOURCE_UNAVAILABLE"); }
    }
    private static FubonIntradayCandleBatch.Stock stock(String code, LocalDate date, Instant asOf,
                                                       FubonIntradayCandleRepository.Snapshot snapshot) {
        if (snapshot==null || snapshot.status()==null)
            return FubonIntradayCandleBatch.Stock.unavailable(code,date,"CAPTURE_UNAVAILABLE");
        if (snapshot.requestStartedAt()==null || snapshot.capturedAt()==null
                || snapshot.requestStartedAt().isAfter(asOf) || snapshot.capturedAt().isAfter(asOf)
                || snapshot.capturedAt().isBefore(snapshot.requestStartedAt()))
            return FubonIntradayCandleBatch.Stock.unavailable(code,date,"CAPTURE_INVALID");
        String status=snapshot.status(),reason=snapshot.reason();
        var candles=List.<FubonIntradayCandleBatch.Candle>of();
        if ("AVAILABLE".equals(status)) {
            if (snapshot.lastCompletedAt()==null
                    || snapshot.lastCompletedAt().isAfter(snapshot.requestStartedAt().minusSeconds(60))
                    || snapshot.candles().isEmpty()
                    || !snapshot.candles().getLast().candleAt().plusSeconds(60).equals(snapshot.lastCompletedAt())) {
                status="UNAVAILABLE"; reason="COMPLETED_FACTS_UNAVAILABLE";
            } else if (Duration.between(snapshot.capturedAt(),asOf).compareTo(Duration.ofSeconds(420))>0
                    || Duration.between(snapshot.lastCompletedAt(),asOf).compareTo(Duration.ofSeconds(420))>0) {
                status="STALE"; reason="CAPTURE_STALE";
            } else candles=snapshot.candles();
        }
        return new FubonIntradayCandleBatch.Stock(code,"台股","FUBON_SDK",status,reason,date,
                snapshot.requestStartedAt(),snapshot.capturedAt(),snapshot.lastCompletedAt(),candles);
    }
    private static FubonIntradayCandleBatch unavailable(List<String> codes,LocalDate date,Instant asOf,String reason) {
        return new FubonIntradayCandleBatch(1,date,asOf,codes.stream()
                .map(code -> FubonIntradayCandleBatch.Stock.unavailable(code,date,reason)).toList());
    }
}
