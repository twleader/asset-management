package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 匯率歷史資料抓取（FinMind TaiwanExchangeRate）。
 * 從 backend HistoricalDataService 抽出，集中外部 API 呼叫於本服務。
 */
@Slf4j
@Component
public class ExchangeRateFetchClient {

    private static final String UA =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String finmindToken;

    public ExchangeRateFetchClient(@Value("${finmind.token:${FINMIND_TOKEN:}}") String finmindToken) {
        this.finmindToken = finmindToken == null ? "" : finmindToken.trim();
    }

    public record RateBar(LocalDate rateDate, BigDecimal buyRate, BigDecimal sellRate, FxSourceEvidence evidence) {
        public RateBar(LocalDate date,BigDecimal buy,BigDecimal sell){this(date,buy,sell,null);}
    }

    /**
     * 從 FinMind TaiwanExchangeRate 抓 currency 從 since 至今的匯率區間。
     * cash_buy/cash_sell 對非現金幣別（如 ZAR / EUR）為 0 或 -1，fallback 至 spot_buy/spot_sell。
     */
    public List<RateBar> fetchRange(String currency, LocalDate since) {
        try {
            String url = "https://api.finmindtrade.com/api/v4/data?dataset=TaiwanExchangeRate"
                    + "&data_id=" + currency + "&start_date=" + since + ("USD".equals(currency) ? "&end_date=" + LocalDate.now(java.time.ZoneId.of("Asia/Taipei")) : "");
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", UA)
                    .header("Accept", "application/json")
                    .header("Accept-Encoding", "identity");
            if (!finmindToken.isEmpty()) {
                b.header("Authorization", "Bearer " + finmindToken);
            }
            HttpResponse<String> resp = httpClient.send(b.GET().build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("FinMind TaiwanExchangeRate {} 回應 {}", currency, resp.statusCode());
                return List.of();
            }
            return parseRates(currency,resp.body(),url,java.time.Instant.now());
        } catch (Exception e) {
            log.warn("FinMind TaiwanExchangeRate {} 抓取失敗: {}", currency, e.getMessage());
            return List.of();
        }
    }

    static List<RateBar> parseRates(String currency,String raw,String url,java.time.Instant capturedAt) throws java.io.IOException {
        JsonNode root=new ObjectMapper().readTree(raw);if(root.path("status").asInt()!=200||!root.path("data").isArray())return List.of();
        FxSourceEvidence evidence="USD".equals(currency)?new FxSourceEvidence("FINMIND_TAIWAN_EXCHANGE_RATE",url,raw,capturedAt):null;
        List<RateBar> out=new ArrayList<>();
        for(JsonNode row:root.path("data")) {
            if(!currency.equals(row.path("currency").asText()))continue;
            LocalDate date=LocalDate.parse(row.path("date").asText());
            BigDecimal buy=decimal(row,"USD".equals(currency)?"spot_buy":"cash_buy");
            BigDecimal sell=decimal(row,"USD".equals(currency)?"spot_sell":"cash_sell");
            if("USD".equals(currency)) {
                if(!evidence.matches(currency,date,buy,sell))continue;
            }else {
                if(buy==null||buy.signum()<=0)buy=decimal(row,"spot_buy");
                if(sell==null||sell.signum()<=0)sell=decimal(row,"spot_sell");
                if(buy==null&&sell==null)continue;
            }
            out.add(new RateBar(date,buy,sell,evidence));
        }
        return List.copyOf(out);
    }

    private static BigDecimal decimal(JsonNode row, String field) {
        JsonNode n = row.path(field);
        if (n.isMissingNode() || n.isNull()) return null;
        String s = n.asText("");
        if (s.isBlank() || "null".equalsIgnoreCase(s)) return null;
        try {
            return new BigDecimal(s).setScale(4, java.math.RoundingMode.HALF_UP);
        } catch (Exception e) {
            return null;
        }
    }
}
