package com.steven.assets.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.model.UsIndexDailyHistory;
import com.steven.assets.repository.UsIndexDailyHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
class MarketAnalysisOvernightEvidence {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final DateTimeFormatter OBSERVATION_TIME =
            DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withZone(TAIPEI);
    private static final LocalTime FIRST_CAPTURE = LocalTime.of(5, 5);
    private static final LocalTime CASH_OPEN = LocalTime.of(9, 0);
    private static final String TX_KEY = "market-analysis:overnight:v1:tx:";
    private static final String US_KEY = "market-analysis:overnight:v1:us:";
    private static final BigDecimal TX_THRESHOLD = BigDecimal.valueOf(2);
    private static final BigDecimal SOX_THRESHOLD = BigDecimal.valueOf(3);
    private static final BigDecimal IXIC_THRESHOLD = BigDecimal.valueOf(2);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final UsIndexDailyHistoryRepository usRepo;
    private final MarketDataService calendar;

    @Scheduled(cron = "0 */5 5-8 * * MON-FRI", zone = "Asia/Taipei")
    public void captureUsCloses() {
        captureUsCloses(Clock.systemUTC());
    }

    void captureUsCloses(Clock clock) {
        var start = clock.instant().atZone(TAIPEI);
        LocalDate date = start.toLocalDate();
        String key = US_KEY + date;
        if (start.toLocalTime().isBefore(FIRST_CAPTURE) || !start.toLocalTime().isBefore(CASH_OPEN)) return;
        try {
            if (!calendar.isTradingDayKnown("台股", date).orElse(false) || Boolean.TRUE.equals(redis.hasKey(key))) return;
            // Use the expected sessions as of the report cutoff. During winter the current US session
            // may only just have closed when the producer first starts looking for its daily bars.
            LocalDate latest = calendar.mostRecentCompletedUsTradingDay(cutoff(date));
            LocalDate previous = calendar.mostRecentCompletedUsTradingDay(
                    latest.atStartOfDay(NEW_YORK).toInstant());
            if (!latest.equals(calendar.mostRecentCompletedUsTradingDay(start.toInstant()))) return;
            Optional<ClosingPair> sox = closes("SOX", previous, latest);
            Optional<ClosingPair> ixic = closes("IXIC", previous, latest);
            Instant observed = clock.instant();
            if (sox.isEmpty() || ixic.isEmpty() || !observed.isBefore(cutoff(date))) return;
            redis.opsForValue().setIfAbsent(key, mapper.writeValueAsString(Map.of(
                    "sessionDate", date.toString(),
                    "usPreviousDate", previous.toString(),
                    "usLatestDate", latest.toString(),
                    "soxPrevious", sox.get().previous(),
                    "soxLatest", sox.get().latest(),
                    "ixicPrevious", ixic.get().previous(),
                    "ixicLatest", ixic.get().latest(),
                    "observedAt", observed.toString())), Duration.ofDays(14));
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("美股盤前證據封存失敗，交易日 {}, 類型 {}", date, ex.getClass().getSimpleName());
        }
    }

    private Optional<ClosingPair> closes(String code, LocalDate previous, LocalDate latest) {
        List<UsIndexDailyHistory> rows = usRepo.findByIndexCodeAndTradingDateBetweenOrderByTradingDateAsc(
                code, previous, latest);
        if (rows.size() != 2 || !previous.equals(rows.getFirst().getTradingDate())
                || !latest.equals(rows.getLast().getTradingDate())) return Optional.empty();
        return closingPair(rows.getFirst().getClosePoint(), rows.getLast().getClosePoint());
    }

    String describe(LocalDate date, Instant analysisTime) {
        Optional<TxEvidence> tx = read(TX_KEY, date, analysisTime).flatMap(snapshot -> {
            String month = snapshot.data().path("contractMonth").asText();
            BigDecimal change = decimal(snapshot.data(), "changePct");
            if (!month.matches("[0-9]{4}(0[1-9]|1[0-2])") || change == null) return Optional.empty();
            try {
                LocalDate nightStart = LocalDate.parse(snapshot.data().path("nightStartDate").asText());
                if (!nightStart.isBefore(date)) return Optional.empty();
                return Optional.of(new TxEvidence(month, nightStart, change, snapshot.observedAt()));
            } catch (DateTimeParseException invalid) {
                return Optional.empty();
            }
        });
        Optional<UsEvidence> us = readUs(date, analysisTime);
        String direction = "開盤方向未確認";
        if (tx.isPresent() && us.isPresent()) {
            direction = openingDirection(tx.get().change(), us.get().soxChange(), us.get().ixicChange());
        }
        return direction + "。"
                + tx.map(quote -> "期交所 " + date + " TX " + quote.contractMonth() + " 夜盤 "
                        + pct(quote.change()) + "（" + quote.nightStartDate() + " 15:00 至次日 05:00）"
                        + observed(quote.observedAt())).orElse("期交所台指期近月夜盤盤前資料未取得")
                + "；"
                + us.map(quote -> "美股 " + quote.latestDate() + " 收盤費半 " + pct(quote.soxChange())
                        + "、那斯達克 " + pct(quote.ixicChange()) + observed(quote.observedAt()))
                        .orElse("費半、那斯達克完整盤前收盤資料未取得")
                + "。";
    }

    private Optional<UsEvidence> readUs(LocalDate date, Instant analysisTime) {
        Optional<Snapshot> stored = read(US_KEY, date, analysisTime);
        if (stored.isEmpty()) return Optional.empty();
        try {
            Snapshot snapshot = stored.get();
            JsonNode data = snapshot.data();
            LocalDate latest = calendar.mostRecentCompletedUsTradingDay(cutoff(date));
            LocalDate previous = calendar.mostRecentCompletedUsTradingDay(
                    latest.atStartOfDay(NEW_YORK).toInstant());
            if (!latest.toString().equals(data.path("usLatestDate").asText())
                    || !previous.toString().equals(data.path("usPreviousDate").asText())
                    || !latest.equals(calendar.mostRecentCompletedUsTradingDay(snapshot.observedAt()))) {
                return Optional.empty();
            }
            Optional<ClosingPair> sox = closingPair(decimal(data, "soxPrevious"), decimal(data, "soxLatest"));
            Optional<ClosingPair> ixic = closingPair(decimal(data, "ixicPrevious"), decimal(data, "ixicLatest"));
            return sox.flatMap(s -> ixic.map(i ->
                    new UsEvidence(latest, s.change(), i.change(), snapshot.observedAt())));
        } catch (RuntimeException ex) {
            log.warn("美股盤前證據驗證失敗，交易日 {}, 類型 {}", date, ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private Optional<Snapshot> read(String prefix, LocalDate date, Instant analysisTime) {
        try {
            String payload = redis.opsForValue().get(prefix + date);
            if (payload == null) return Optional.empty();
            JsonNode data = mapper.readTree(payload);
            if (data == null || !date.toString().equals(data.path("sessionDate").asText())) return Optional.empty();
            Instant observed = Instant.parse(data.path("observedAt").asText());
            Instant firstCapture = date.atTime(FIRST_CAPTURE).atZone(TAIPEI).toInstant();
            if (observed.isBefore(firstCapture) || !observed.isBefore(cutoff(date))
                    || observed.isAfter(analysisTime)) return Optional.empty();
            return Optional.of(new Snapshot(data, observed));
        } catch (RuntimeException | JsonProcessingException ex) {
            log.warn("盤前證據讀取失敗，交易日 {}, 類型 {}", date, ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static Instant cutoff(LocalDate date) {
        return date.atTime(CASH_OPEN).atZone(TAIPEI).toInstant();
    }

    static String openingDirection(BigDecimal tx, BigDecimal sox, BigDecimal ixic) {
        int direction = tx.signum();
        if (tx.abs().compareTo(TX_THRESHOLD) < 0 || sox.abs().compareTo(SOX_THRESHOLD) < 0
                || ixic.abs().compareTo(IXIC_THRESHOLD) < 0 || sox.signum() != direction
                || ixic.signum() != direction) return "開盤方向未確認";
        return direction > 0 ? "開盤偏多" : "開盤偏空";
    }

    private static BigDecimal decimal(JsonNode object, String field) {
        JsonNode node = object.path(field);
        return node.isNumber() ? node.decimalValue() : null;
    }

    private static Optional<ClosingPair> closingPair(BigDecimal previous, BigDecimal latest) {
        return previous != null && previous.signum() > 0 && latest != null && latest.signum() > 0
                ? Optional.of(new ClosingPair(previous, latest)) : Optional.empty();
    }

    private static String pct(BigDecimal change) {
        return (change.signum() > 0 ? "+" : "") + change.setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    private static String observed(Instant at) {
        return "（台北 " + OBSERVATION_TIME.format(at) + " 觀察）";
    }

    private record Snapshot(JsonNode data, Instant observedAt) {}
    private record TxEvidence(String contractMonth, LocalDate nightStartDate, BigDecimal change, Instant observedAt) {}
    private record UsEvidence(LocalDate latestDate, BigDecimal soxChange, BigDecimal ixicChange, Instant observedAt) {}
    private record ClosingPair(BigDecimal previous, BigDecimal latest) {
        BigDecimal change() {
            return latest.subtract(previous).movePointRight(2).divide(previous, 12, RoundingMode.HALF_UP);
        }
    }
}
