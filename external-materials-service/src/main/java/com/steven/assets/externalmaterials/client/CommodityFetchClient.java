package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 原物料（油價／金價）每日收盤價抓取：Yahoo Finance chart API。
 *
 * 標的皆為國際盤、美元計價（Requirement 40）：
 * <ul>
 *   <li>{@code WTI}   西德州原油 {@code CL=F}（USD / 桶）</li>
 *   <li>{@code BRENT} 布蘭特原油 {@code BZ=F}（USD / 桶）</li>
 *   <li>{@code GOLD}  COMEX 黃金 {@code GC=F}（USD / 盎司）</li>
 * </ul>
 *
 * 不選中油零售油價／台銀黃金存摺牌價，因兩者公開歷史多半不足十年，無法滿足「最近 10 年每日」。
 *
 * 走 curl 子程序 ＋ 短 UA（{@code Mozilla/5.0}），避開 Yahoo 對 Java HttpClient HTTP/2
 * fingerprint 的封鎖；長 Chrome UA 反而會被 WAF 回 429（同 {@link YahooFxFetchClient}／
 * {@link BotFxFetchClient} 慣例）。純抓取，不碰 DB；任何失敗一律吞掉回空 List 並 log.warn，
 * 不讓排程因外部來源不穩而中斷。
 */
@Slf4j
@Component
public class CommodityFetchClient {

    public static final String PROVIDER = "YAHOO_FINANCE_CHART";

    /** commodity_code → Yahoo symbol。新增標的只改這裡。 */
    public static final Map<String, String> SYMBOLS = Map.of(
            "WTI", "CL=F",
            "BRENT", "BZ=F",
            "GOLD", "GC=F");

    /** 期貨掛牌於紐約（NYMEX／COMEX），epoch 換算與日期歸屬皆以此時區為準。 */
    private static final ZoneId NY = ZoneId.of("America/New_York");

    private final ObjectMapper mapper = new ObjectMapper();

    /** 單日收盤價與實際抓取 provenance。 */
    public record CommodityBar(
            LocalDate priceDate,
            BigDecimal closePrice,
            String provider,
            String sourceUrl,
            Instant sourceAvailableAt,
            Instant fetchedAt) {

        /** Compatibility constructor for isolated tests/callers that intentionally lack provenance. */
        public CommodityBar(LocalDate priceDate, BigDecimal closePrice) {
            this(priceDate, closePrice, null, null, null, null);
        }
    }

    /**
     * 輕量即時報價（Requirement 77）：{@code range=1d&interval=1d} 的 {@code meta} 區塊。
     * {@code quoteTime} 一律取來源自帶的 {@code meta.regularMarketTime}，不得由呼叫端另行填入
     * 抓取當下的時刻——這是「盤中最後成交價 vs 交易所結算價」語意成立的前提（見 Task 337 背景段）。
     */
    public record LiveQuote(
            String commodityCode,
            BigDecimal price,
            BigDecimal sourcePreviousClose,
            BigDecimal dayHigh,
            BigDecimal dayLow,
            LocalDate sessionDate,
            Instant quoteTime,
            String provider,
            String sourceUrl) {
    }

    /**
     * 抓單一標的的輕量即時報價（{@code range=1d&interval=1d}，1,164 bytes 量級），供每分鐘 tick
     * 與 17:05 收盤校正共用。逾時 10 秒（既有區間抓取用 30 秒；每分鐘一輪時 30 秒會讓單一標的
     * 吃掉半個週期）。{@code regularMarketPrice} 缺或 &lt;= 0、{@code regularMarketTime} 缺、
     * 或找不到任何非 null close 的 bar 一律回空，不得以任何方式補值。
     */
    public Optional<LiveQuote> fetchLiveQuote(String code) {
        String symbol = SYMBOLS.get(code);
        if (symbol == null) {
            log.warn("未知原物料代碼 {}", code);
            return Optional.empty();
        }
        try {
            // symbol 含 '='，需 URL-encode 才不會被 Yahoo 誤解析成 query 分隔
            String url = "https://query1.finance.yahoo.com/v8/finance/chart/"
                    + symbol.replace("=", "%3D") + "?range=1d&interval=1d";

            ProcessBuilder pb = new ProcessBuilder("curl", "-s", "--max-time", "10",
                    "-H", "User-Agent: Mozilla/5.0", url);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String body = new String(proc.getInputStream().readAllBytes());
            proc.waitFor();

            if (body == null || body.isBlank()) {
                log.warn("Yahoo {} ({}) 即時報價回傳空白", code, symbol);
                return Optional.empty();
            }

            JsonNode root = mapper.readTree(body);
            JsonNode result = root.path("chart").path("result").path(0);
            JsonNode meta = result.path("meta");

            BigDecimal price = decimal(meta.path("regularMarketPrice"));
            if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("Yahoo {} ({}) 即時報價缺 regularMarketPrice", code, symbol);
                return Optional.empty();
            }
            if (!meta.hasNonNull("regularMarketTime")) {
                log.warn("Yahoo {} ({}) 即時報價缺 regularMarketTime", code, symbol);
                return Optional.empty();
            }
            Instant quoteTime = Instant.ofEpochSecond(meta.path("regularMarketTime").asLong());

            // sessionDate：取陣列中最後一根有非 null close 的 bar，不得寫死 timestamp[0]——
            // 本任務沒有可證明「陣列恆長度為 1」的實測，逐根迭代與既有 fetchRange 寫法一致，
            // 成本為零、對單 bar 情境行為完全相同。
            JsonNode timestamps = result.path("timestamp");
            JsonNode closes = result.path("indicators").path("quote").path(0).path("close");
            LocalDate sessionDate = null;
            if (timestamps.isArray() && closes.isArray()) {
                for (int i = timestamps.size() - 1; i >= 0; i--) {
                    JsonNode close = closes.path(i);
                    if (close.isNull() || close.isMissingNode()) continue;
                    sessionDate = Instant.ofEpochSecond(timestamps.get(i).asLong())
                            .atZone(NY).toLocalDate();
                    break;
                }
            }
            if (sessionDate == null) {
                log.warn("Yahoo {} ({}) 即時報價找不到非 null close 的 bar 以判定 sessionDate", code, symbol);
                return Optional.empty();
            }

            BigDecimal previousClose = decimal(meta.path("chartPreviousClose"));
            BigDecimal dayHigh = decimal(meta.path("regularMarketDayHigh"));
            BigDecimal dayLow = decimal(meta.path("regularMarketDayLow"));

            return Optional.of(new LiveQuote(
                    code,
                    price.setScale(4, RoundingMode.HALF_UP),
                    scaleOrNull(previousClose),
                    scaleOrNull(dayHigh),
                    scaleOrNull(dayLow),
                    sessionDate,
                    quoteTime,
                    PROVIDER,
                    url));
        } catch (Exception e) {
            log.warn("Yahoo {} 即時報價抓取失敗: {}", code, e.getMessage());
            return Optional.empty();
        }
    }

    private static BigDecimal scaleOrNull(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * 抓指定標的 {@code [start, end]} 區間的每日收盤價（含頭尾）。
     * 未知 code、HTTP 失敗、Yahoo 無資料一律回空 List。
     */
    public List<CommodityBar> fetchRange(String code, LocalDate start, LocalDate end) {
        String symbol = SYMBOLS.get(code);
        if (symbol == null) {
            log.warn("未知原物料代碼 {}", code);
            return List.of();
        }
        try {
            long period1 = start.atStartOfDay(NY).toEpochSecond();
            long period2 = end.plusDays(1).atStartOfDay(NY).toEpochSecond();
            // symbol 含 '='，需 URL-encode 才不會被 Yahoo 誤解析成 query 分隔
            String url = "https://query1.finance.yahoo.com/v8/finance/chart/"
                    + symbol.replace("=", "%3D")
                    + "?period1=" + period1 + "&period2=" + period2 + "&interval=1d";

            ProcessBuilder pb = new ProcessBuilder("curl", "-s", "--max-time", "30",
                    "-H", "User-Agent: Mozilla/5.0", url);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String body = new String(proc.getInputStream().readAllBytes());
            proc.waitFor();
            Instant fetchedAt = Instant.now();

            if (body == null || body.isBlank()) {
                log.warn("Yahoo {} ({}) 回傳空白", code, symbol);
                return List.of();
            }

            JsonNode root = mapper.readTree(body);
            JsonNode result = root.path("chart").path("result").path(0);
            JsonNode timestamps = result.path("timestamp");
            JsonNode closes = result.path("indicators").path("quote").path(0).path("close");
            if (!timestamps.isArray() || !closes.isArray()) {
                String err = root.path("chart").path("error").path("description").asText("");
                log.warn("Yahoo {} ({}) 無資料: {}", code, symbol, err.isEmpty() ? "no timestamps" : err);
                return List.of();
            }

            List<CommodityBar> bars = new ArrayList<>();
            for (int i = 0; i < timestamps.size(); i++) {
                JsonNode close = closes.path(i);
                // 未成交日 close 為 null：略過而非補前值，避免捏造不存在的報價
                if (close.isNull() || close.isMissingNode()) continue;
                BigDecimal price = decimal(close);
                if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) continue;

                LocalDate date = Instant.ofEpochSecond(timestamps.get(i).asLong())
                        .atZone(NY).toLocalDate();
                if (date.isBefore(start) || date.isAfter(end)) continue;

                bars.add(new CommodityBar(
                        date,
                        price.setScale(4, RoundingMode.HALF_UP),
                        PROVIDER,
                        url,
                        fetchedAt,
                        fetchedAt));
            }
            return bars;
        } catch (Exception e) {
            log.warn("Yahoo {} 區間抓取失敗: {}", code, e.getMessage());
            return List.of();
        }
    }

    private static BigDecimal decimal(JsonNode n) {
        String s = n.asText("");
        if (s.isBlank()) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
