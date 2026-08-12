package com.steven.assets.bff.gdptwse;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 股市大盤日線／分時共用聚合服務。既有頁面 BFF 與公開 API 都只透過這裡抓取、對齊及計算圖表資料。
 */
@Service
@Slf4j
public class MarketIndexChartService {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_MAP =
            new ParameterizedTypeReference<>() {};

    private static final List<MarketDefinition> MARKET_CATALOG = List.of(
            new MarketDefinition("TWSE", "台股大盤"),
            new MarketDefinition("DJI", "道瓊工業"),
            new MarketDefinition("SPX", "標普 500"),
            new MarketDefinition("IXIC", "那斯達克綜合"),
            new MarketDefinition("SOX", "費城半導體"),
            new MarketDefinition("FTSE", "英國富時 100"),
            new MarketDefinition("DAX", "德國 DAX"),
            new MarketDefinition("KOSPI", "韓國 KOSPI"),
            new MarketDefinition("N225", "日經 225"));

    private static final List<RangeDefinition> RANGE_CATALOG = List.of(
            new RangeDefinition("d", "當日", null),
            new RangeDefinition("1m", "1 個月", 21),
            new RangeDefinition("3m", "3 個月", 63),
            new RangeDefinition("6m", "半年", 125),
            new RangeDefinition("1y", "1 年", 250),
            new RangeDefinition("2y", "2 年", 500),
            new RangeDefinition("5y", "5 年", 1250),
            new RangeDefinition("10y", "10 年", 2500));

    private static final List<MarketIndexChartDto.Option> SUPPORTED_MARKETS = MARKET_CATALOG.stream()
            .map(market -> new MarketIndexChartDto.Option(market.value(), market.label()))
            .toList();

    private static final List<MarketIndexChartDto.Option> SUPPORTED_RANGES = RANGE_CATALOG.stream()
            .map(range -> new MarketIndexChartDto.Option(range.value(), range.label()))
            .toList();

    private final WebClient businessServicesClient;

    public MarketIndexChartService(WebClient businessServicesClient) {
        this.businessServicesClient = businessServicesClient;
    }

    /** 既有頁面日線 endpoint 的共用實作；保留 market/years 與 fail-soft 語意。 */
    Mono<Map<String, Object>> getIndexDaily(String market, int years) {
        return fetchDailyChart(market, years)
                .map(DailyChart::toLegacyBody)
                .onErrorResume(MalformedMarketIndexPayloadException.class, ex -> {
                    log.error("股市大盤日線的下游成交金額格式錯誤，legacy endpoint 回傳完整空資料：{}",
                            ex.getMessage());
                    return Mono.just(DailyChart.empty().toLegacyBody());
                });
    }

    /** 既有頁面分時 endpoint 的共用實作；保留近 40 日日線昨收與 fail-soft 語意。 */
    Mono<Map<String, Object>> getIndexIntraday(String market) {
        return fetchIntradayChart(market).map(IntradayChart::toLegacyBody);
    }

    /** 公開 API：先正規化與驗證，再依 range 組裝唯一 schema。 */
    public Mono<MarketIndexChartDto.Response> getPublicChart(String rawMarket, String rawRange) {
        MarketDefinition market = resolveMarket(rawMarket);
        RangeDefinition range = resolveRange(rawRange);
        if (range.tradingDays() == null) {
            return Mono.zip(fetchDailyChart(market.value(), 10), fetchIntradayChart(market.value()))
                    .map(tuple -> toIntradayResponse(market, range, tuple.getT1(), tuple.getT2()));
        }
        return fetchDailyChart(market.value(), 10)
                .map(daily -> toDailyResponse(market, range, daily));
    }

    static List<MarketIndexChartDto.Option> supportedMarkets() {
        return SUPPORTED_MARKETS;
    }

    static List<MarketIndexChartDto.Option> supportedRanges() {
        return SUPPORTED_RANGES;
    }

    static String normalizeMarket(String rawMarket) {
        return resolveMarket(rawMarket).value();
    }

    static String normalizeRange(String rawRange) {
        return resolveRange(rawRange).value();
    }

    private Mono<DailyChart> fetchDailyChart(String market, int years) {
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusYears(years);
        boolean tw = "TWSE".equalsIgnoreCase(market);
        return fetchDailyRows(market, tw, from, today)
                .map(rows -> buildDailyChart(tw, rows));
    }

    private Mono<IntradayChart> fetchIntradayChart(String market) {
        boolean tw = "TWSE".equalsIgnoreCase(market);
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(40);

        Mono<List<Map<String, Object>>> intraday = businessServicesClient.get()
                .uri(uri -> uri.path("/api/index-intraday").queryParam("market", market).build())
                .retrieve()
                .bodyToMono(LIST_MAP)
                .defaultIfEmpty(Collections.emptyList())
                .onErrorReturn(Collections.emptyList());
        Mono<List<Map<String, Object>>> daily = fetchDailyRows(market, tw, from, today);

        return Mono.zip(intraday, daily)
                .map(tuple -> buildIntradayChart(tuple.getT1(), tuple.getT2()));
    }

    private Mono<List<Map<String, Object>>> fetchDailyRows(
            String market, boolean tw, LocalDate from, LocalDate to) {
        return businessServicesClient.get()
                .uri(uri -> tw
                        ? uri.path("/api/twse-daily-index")
                                .queryParam("from", from.toString())
                                .queryParam("to", to.toString())
                                .build()
                        : uri.path("/api/us-daily-index")
                                .queryParam("code", market)
                                .queryParam("from", from.toString())
                                .queryParam("to", to.toString())
                                .build())
                .retrieve()
                .bodyToMono(LIST_MAP)
                .defaultIfEmpty(Collections.emptyList())
                .onErrorReturn(Collections.emptyList());
    }

    /** 舊 endpoint 與公開 endpoint 共用的日線純函式。 */
    static Map<String, Object> buildIndexDailyBody(boolean tw, List<Map<String, Object>> rows) {
        return buildDailyChart(tw, rows).toLegacyBody();
    }

    private static DailyChart buildDailyChart(boolean tw, List<Map<String, Object>> rows) {
        int n = rows.size();
        List<String> dates = new ArrayList<>(n);
        List<BigDecimal> closes = new ArrayList<>(n);
        List<Object> volumes = new ArrayList<>(n);
        List<BigDecimal> turnovers = new ArrayList<>(n);
        boolean hasVolume = false;
        for (Map<String, Object> row : rows) {
            Object date = row.get("tradingDate");
            Object close = row.get("closePoint");
            if (date == null || close == null) {
                continue;
            }
            dates.add(date.toString());
            closes.add(new BigDecimal(close.toString()));

            Object volume = tw ? row.get("tradeVolume") : row.get("volume");
            BigDecimal turnover = tw ? toTurnover(row.get("tradeValue")) : null;
            volumes.add(volume);
            turnovers.add(turnover);
            if (isNonZeroValue(tw ? turnover : volume)) {
                hasVolume = true;
            }
        }
        return new DailyChart(
                dates,
                closes,
                movingAverage(closes, 5),
                movingAverage(closes, 20),
                movingAverage(closes, 60),
                movingAverage(closes, 240),
                volumes,
                turnovers,
                hasVolume);
    }

    /**
     * 將下游 tradeValue 固定轉成精確十進位。浮點 Number 取其十進位字面，不經
     * {@code new BigDecimal(double)}，避免把 IEEE-754 尾差帶進公開契約。
     */
    private static BigDecimal toTurnover(Object value) {
        if (value == null) {
            return null;
        }
        String literal = value instanceof CharSequence
                ? value.toString().trim()
                : value.toString();
        try {
            return new BigDecimal(literal);
        } catch (NumberFormatException ex) {
            throw new MalformedMarketIndexPayloadException(value, ex);
        }
    }

    /** 簡單移動平均：完整 BigDecimal 加總、視窗不足為 null、結果固定 2 位 HALF_UP。 */
    static List<BigDecimal> movingAverage(List<BigDecimal> values, int window) {
        int n = values.size();
        List<BigDecimal> out = new ArrayList<>(n);
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            sum = sum.add(values.get(i));
            if (i >= window) {
                sum = sum.subtract(values.get(i - window));
            }
            if (i >= window - 1) {
                out.add(sum.divide(BigDecimal.valueOf(window), 2, RoundingMode.HALF_UP));
            } else {
                out.add(null);
            }
        }
        return out;
    }

    private static boolean isNonZeroValue(Object value) {
        if (value == null) {
            return false;
        }
        try {
            return new BigDecimal(value.toString()).signum() != 0;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static IntradayChart buildIntradayChart(
            List<Map<String, Object>> rows, List<Map<String, Object>> daily) {
        List<String> times = new ArrayList<>(rows.size());
        List<BigDecimal> closes = new ArrayList<>(rows.size());
        LocalDate tradingDate = null;
        BigDecimal lastClose = null;
        for (Map<String, Object> row : rows) {
            Object time = row.get("time");
            if (time == null) {
                continue;
            }
            String timestamp = time.toString();
            if (tradingDate == null && timestamp.length() >= 10) {
                tradingDate = LocalDate.parse(timestamp.substring(0, 10));
            }
            times.add(timestamp.length() >= 16 ? timestamp.substring(11, 16) : timestamp);
            Object close = row.get("close");
            BigDecimal closeValue = close == null ? null : new BigDecimal(close.toString());
            closes.add(closeValue);
            if (closeValue != null) {
                lastClose = closeValue;
            }
        }

        BigDecimal previousClose = previousCloseBefore(
                daily, tradingDate == null ? null : tradingDate.toString());
        BigDecimal change = null;
        BigDecimal changePercent = null;
        if (lastClose != null && previousClose != null && previousClose.signum() != 0) {
            change = lastClose.subtract(previousClose).setScale(2, RoundingMode.HALF_UP);
            changePercent = lastClose.subtract(previousClose)
                    .divide(previousClose, 6, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100))
                    .setScale(2, RoundingMode.HALF_UP);
        }
        return new IntradayChart(
                tradingDate, times, closes, previousClose, lastClose, change, changePercent);
    }

    /** 日線（tradingDate asc）中嚴格早於 beforeDate 的最後一筆收盤。 */
    static BigDecimal previousCloseBefore(List<Map<String, Object>> daily, String beforeDate) {
        if (beforeDate == null) {
            return null;
        }
        BigDecimal previous = null;
        for (Map<String, Object> row : daily) {
            Object date = row.get("tradingDate");
            Object close = row.get("closePoint");
            if (date == null || close == null) {
                continue;
            }
            if (date.toString().compareTo(beforeDate) < 0) {
                previous = new BigDecimal(close.toString());
            } else {
                break;
            }
        }
        return previous;
    }

    private static MarketIndexChartDto.Response toDailyResponse(
            MarketDefinition market, RangeDefinition range, DailyChart daily) {
        int fromIndex = Math.max(0, daily.dates().size() - range.tradingDays());
        return new MarketIndexChartDto.Response(
                market.value(),
                market.label(),
                range.value(),
                range.label(),
                "DAILY",
                null,
                daily.dates().subList(fromIndex, daily.dates().size()),
                daily.closes().subList(fromIndex, daily.closes().size()),
                daily.ma5().subList(fromIndex, daily.ma5().size()),
                daily.ma20().subList(fromIndex, daily.ma20().size()),
                daily.ma60().subList(fromIndex, daily.ma60().size()),
                daily.ma240().subList(fromIndex, daily.ma240().size()),
                daily.volumes().subList(fromIndex, daily.volumes().size()),
                daily.turnovers().subList(fromIndex, daily.turnovers().size()),
                !daily.dates().isEmpty() && daily.hasVolume(),
                null,
                null,
                null,
                null,
                SUPPORTED_MARKETS,
                SUPPORTED_RANGES);
    }

    private static MarketIndexChartDto.Response toIntradayResponse(
            MarketDefinition market, RangeDefinition range, DailyChart daily, IntradayChart intraday) {
        int size = intraday.times().size();
        return new MarketIndexChartDto.Response(
                market.value(),
                market.label(),
                range.value(),
                range.label(),
                "INTRADAY",
                intraday.tradingDate(),
                intraday.times(),
                intraday.closes(),
                horizontalLine(size, lastNonNull(daily.ma5())),
                horizontalLine(size, lastNonNull(daily.ma20())),
                horizontalLine(size, lastNonNull(daily.ma60())),
                horizontalLine(size, lastNonNull(daily.ma240())),
                nullLine(size),
                nullLine(size),
                false,
                intraday.previousClose(),
                intraday.lastClose(),
                intraday.change(),
                intraday.changePercent(),
                SUPPORTED_MARKETS,
                SUPPORTED_RANGES);
    }

    private static BigDecimal lastNonNull(List<BigDecimal> values) {
        for (int i = values.size() - 1; i >= 0; i--) {
            if (values.get(i) != null) {
                return values.get(i);
            }
        }
        return null;
    }

    private static List<BigDecimal> horizontalLine(int size, BigDecimal value) {
        return new ArrayList<>(Collections.nCopies(size, value));
    }

    private static <T> List<T> nullLine(int size) {
        return new ArrayList<>(Collections.nCopies(size, null));
    }

    private static MarketDefinition resolveMarket(String rawMarket) {
        String normalized = rawMarket == null || rawMarket.isBlank()
                ? "TWSE"
                : rawMarket.trim().toUpperCase(Locale.ROOT);
        return MARKET_CATALOG.stream()
                .filter(market -> market.value().equals(normalized))
                .findFirst()
                .orElseThrow(() -> invalidValue("market", rawMarket, SUPPORTED_MARKETS));
    }

    private static RangeDefinition resolveRange(String rawRange) {
        String normalized = rawRange == null || rawRange.isBlank()
                ? "1y"
                : rawRange.trim().toLowerCase(Locale.ROOT);
        return RANGE_CATALOG.stream()
                .filter(range -> range.value().equals(normalized))
                .findFirst()
                .orElseThrow(() -> invalidValue("range", rawRange, SUPPORTED_RANGES));
    }

    private static PublicMarketIndexRequestException invalidValue(
            String parameter, String received, List<MarketIndexChartDto.Option> supported) {
        List<String> values = supported.stream().map(MarketIndexChartDto.Option::value).toList();
        return new PublicMarketIndexRequestException(
                "未知 " + parameter + " 值 '" + String.valueOf(received) + "'；合法 values: " + values);
    }

    private record MarketDefinition(String value, String label) {
    }

    private record RangeDefinition(String value, String label, Integer tradingDays) {
    }

    private record DailyChart(
            List<String> dates,
            List<BigDecimal> closes,
            List<BigDecimal> ma5,
            List<BigDecimal> ma20,
            List<BigDecimal> ma60,
            List<BigDecimal> ma240,
            List<Object> volumes,
            List<BigDecimal> turnovers,
            boolean hasVolume) {

        static DailyChart empty() {
            return new DailyChart(
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    false);
        }

        Map<String, Object> toLegacyBody() {
            Map<String, Object> body = new HashMap<>();
            body.put("dates", dates);
            body.put("closes", closes);
            body.put("ma5", ma5);
            body.put("ma20", ma20);
            body.put("ma60", ma60);
            body.put("ma240", ma240);
            body.put("volumes", volumes);
            body.put("turnovers", turnovers);
            body.put("hasVolume", hasVolume);
            return body;
        }
    }

    private record IntradayChart(
            LocalDate tradingDate,
            List<String> times,
            List<BigDecimal> closes,
            BigDecimal previousClose,
            BigDecimal lastClose,
            BigDecimal change,
            BigDecimal changePercent) {

        Map<String, Object> toLegacyBody() {
            Map<String, Object> body = new HashMap<>();
            body.put("tradingDate", tradingDate == null ? null : tradingDate.toString());
            body.put("times", times);
            body.put("closes", closes);
            body.put("previousClose", previousClose);
            body.put("lastClose", lastClose);
            body.put("change", change);
            body.put("changePercent", changePercent);
            return body;
        }
    }
}
