package com.steven.assets.service;

import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 台股大盤可見報價的單一解析器，避免觀察清單與交易雷達各自決定盤後顯示來源。 */
@Service
@RequiredArgsConstructor
public class TaiexDisplayPriceService {

    private static final String CODE = "0000";
    private static final String MARKET = "台股";

    private final PriceQueryService priceQuery;
    private final TwseIndexDailyHistoryRepository historyRepo;

    public record DisplayQuote(
            BigDecimal price,
            BigDecimal changePercent,
            BigDecimal open,
            BigDecimal high,
            BigDecimal low,
            BigDecimal previousClose,
            String tradingDate,
            String updatedAt,
            boolean closed,
            String quoteStatus
    ) {}

    public DisplayQuote resolve() {
        PriceQueryService.DisplaySession session = priceQuery.displaySession(MARKET);
        List<TwseIndexDailyHistory> recent = historyRepo.findTop60ByOrderByTradingDateDesc();

        if (session.phase() == PriceQueryService.DisplayPhase.OPEN) {
            Optional<PriceQueryService.LivePrice> live = priceQuery.getLive(CODE, MARKET)
                    .filter(row -> session.marketToday().toString().equals(row.tradingDate()));
            if (live.isPresent()) {
                PriceQueryService.LivePrice row = live.get();
                BigDecimal previous = previousClose(recent, session.marketToday());
                return new DisplayQuote(
                        row.price(), changePercent(row.price(), previous),
                        row.openPrice(), row.highPrice(), row.lowPrice(), previous,
                        row.tradingDate(), row.updatedAt(), false, "LIVE");
            }
            Optional<TwseIndexDailyHistory> previous = recent.stream()
                    .filter(row -> row.getTradingDate().isBefore(session.marketToday()))
                    .findFirst();
            if (previous.isPresent()) return fromHistory(previous.get(), recent, "PREVIOUS_CLOSE");
            return pending(session.marketToday());
        }

        Optional<TwseIndexDailyHistory> exact = historyRepo.findById(session.targetTradingDate());
        if (exact.isEmpty()) return pending(session.targetTradingDate());
        String status = session.phase() == PriceQueryService.DisplayPhase.AFTER_CLOSE
                ? "VERIFIED_CLOSE" : "PREVIOUS_CLOSE";
        return fromHistory(exact.get(), recent, status);
    }

    private static DisplayQuote fromHistory(
            TwseIndexDailyHistory row, List<TwseIndexDailyHistory> recent, String status) {
        BigDecimal previous = previousClose(recent, row.getTradingDate());
        return new DisplayQuote(
                row.getClosePoint(), changePercent(row.getClosePoint(), previous),
                row.getOpenPoint(), row.getHighPoint(), row.getLowPoint(), previous,
                row.getTradingDate().toString(), null, true, status);
    }

    private static DisplayQuote pending(LocalDate target) {
        return new DisplayQuote(
                null, null, null, null, null, null,
                target.toString(), null, false, "CLOSE_PENDING");
    }

    private static BigDecimal previousClose(List<TwseIndexDailyHistory> recent, LocalDate date) {
        return recent.stream()
                .filter(row -> row.getTradingDate().isBefore(date))
                .findFirst()
                .map(TwseIndexDailyHistory::getClosePoint)
                .orElse(null);
    }

    private static BigDecimal changePercent(BigDecimal price, BigDecimal previous) {
        if (price == null || previous == null || previous.signum() == 0) return null;
        return price.subtract(previous)
                .multiply(BigDecimal.valueOf(100))
                .divide(previous, 6, RoundingMode.HALF_UP);
    }
}
