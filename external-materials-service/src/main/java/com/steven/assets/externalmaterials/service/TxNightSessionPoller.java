package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Slf4j
@Component
@RequiredArgsConstructor
public class TxNightSessionPoller {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final LocalTime NIGHT_CLOSE = LocalTime.of(5, 0);
    private static final LocalTime OPEN = LocalTime.of(9, 0);
    private static final String KEY_PREFIX = "market-analysis:overnight:v1:tx:";
    private static final DateTimeFormatter REPORT_DATE = DateTimeFormatter.ofPattern("uuuu/MM/dd");
    private static final URI SOURCE = URI.create(
            "https://www.taifex.com.tw/cht/3/futDailyMarketExcel?marketCode=1&commodity_id=TX");
    private static final Pattern MONTH = Pattern.compile("[0-9]{4}(0[1-9]|1[0-2])");
    private static final Pattern NIGHT_HEADER = Pattern.compile(
            "([0-9]{4}/[0-9]{2}/[0-9]{2})\\s+15:00\\s*[~～]\\s*次日05:00\\s*盤後交易時段行情表");
    private static final Pattern PERCENT = Pattern.compile("([▲△▼▽+-]?)([0-9]+(?:\\.[0-9]+)?)%");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    private static final HttpRequest REQUEST = HttpRequest.newBuilder(SOURCE).timeout(Duration.ofSeconds(8)).build();

    private final MarketCalendar calendar;
    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;

    @Scheduled(cron = "0 */5 5-8 * * MON-FRI", zone = "Asia/Taipei")
    public void capture() {
        ZonedDateTime start = ZonedDateTime.now(TAIPEI);
        if (start.toLocalTime().isBefore(NIGHT_CLOSE.plusMinutes(5))) return;
        LocalDate date = start.toLocalDate();
        try {
            String key = KEY_PREFIX + date;
            if (!calendar.isTwTradingDayKnown(date).orElse(false) || Boolean.TRUE.equals(redis.hasKey(key))) return;
            HttpResponse<String> response = HTTP.send(REQUEST, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return;
            Optional<NightQuote> parsed = parseReport(response.body(), date);
            Instant observed = Instant.now();
            if (parsed.isEmpty() || !observed.isBefore(date.atTime(OPEN).atZone(TAIPEI).toInstant())) return;
            NightQuote quote = parsed.get();
            redis.opsForValue().setIfAbsent(key, mapper.writeValueAsString(Map.of(
                    "sessionDate", date.toString(),
                    "contractMonth", quote.contractMonth(),
                    "nightStartDate", quote.nightStartDate().toString(),
                    "changePct", quote.changePct(),
                    "observedAt", observed.toString())), Duration.ofDays(14));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException ex) {
            log.warn("台指期夜盤盤前證據擷取失敗，交易日 {}, 類型 {}", date, ex.getClass().getSimpleName());
        }
    }

    record NightQuote(String contractMonth, LocalDate nightStartDate, BigDecimal changePct) {}

    static Optional<NightQuote> parseReport(String html, LocalDate date) {
        var document = Jsoup.parse(html);
        String header = document.text();
        Pattern expectedDate = Pattern.compile("日期\\s*[：:]\\s*" + REPORT_DATE.format(date) + "(?![0-9])");
        var nightHeader = NIGHT_HEADER.matcher(header);
        if (!expectedDate.matcher(header).find() || !nightHeader.find()
                || header.contains("一般交易時段行情表")) return Optional.empty();
        LocalDate nightStartDate = LocalDate.parse(nightHeader.group(1), REPORT_DATE);
        if (!nightStartDate.isBefore(date)) return Optional.empty();

        return document.select("tr").stream()
                .map(row -> row.select("> td").stream().map(Element::text).toList())
                .filter(row -> row.size() >= 2 && "TX".equals(row.getFirst())
                        && MONTH.matcher(row.get(1)).matches())
                .min(Comparator.comparing(row -> row.get(1)))
                .flatMap(row -> quote(row, nightStartDate));
    }

    private static Optional<NightQuote> quote(List<String> row, LocalDate nightStartDate) {
        if (row.size() < 9) return Optional.empty();
        var percent = PERCENT.matcher(row.get(7).replaceAll("\\s+", ""));
        if (!percent.matches()) return Optional.empty();
        try {
            if (new BigDecimal(row.get(5).replace(",", "")).signum() <= 0
                    || new BigDecimal(row.get(8).replace(",", "")).signum() <= 0) return Optional.empty();
            BigDecimal change = new BigDecimal(percent.group(2));
            if (percent.group(1).matches("[▼▽-]")) change = change.negate();
            return Optional.of(new NightQuote(row.get(1), nightStartDate, change));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }
}
