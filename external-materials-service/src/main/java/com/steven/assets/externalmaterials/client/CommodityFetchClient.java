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

    /** commodity_code → Yahoo symbol。新增標的只改這裡。 */
    public static final Map<String, String> SYMBOLS = Map.of(
            "WTI", "CL=F",
            "BRENT", "BZ=F",
            "GOLD", "GC=F");

    /** 期貨掛牌於紐約（NYMEX／COMEX），epoch 換算與日期歸屬皆以此時區為準。 */
    private static final ZoneId NY = ZoneId.of("America/New_York");

    private final ObjectMapper mapper = new ObjectMapper();

    /** 單日收盤價。 */
    public record CommodityBar(LocalDate priceDate, BigDecimal closePrice) {}

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

                bars.add(new CommodityBar(date, price.setScale(4, RoundingMode.HALF_UP)));
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
