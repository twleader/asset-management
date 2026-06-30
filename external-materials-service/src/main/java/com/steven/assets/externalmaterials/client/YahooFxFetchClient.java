package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Yahoo Finance 即時匯率備援（當台銀牌告被 WAF 反爬挑戰封鎖時）。
 *
 * 台銀 rate.bot.com.tw 全站自 2026/06 套上 Akamai SEC-CPT 主動式 JS PoW 挑戰，
 * curl 子程序無 JS runtime 拿不到牌告 CSV（{@link BotFxFetchClient} 全幣別回 empty）。
 * 本 client 從 Yahoo Finance chart endpoint 取 USD/TWD「當日中間價」作為當日（T-0）暫定值；
 * 隔日 17:00 FinMind（{@link ExchangeRateFetchClient}）會以真實即期買入/賣出價覆寫同一
 * {@code (currency, rate_date)} 列，將暫定中間價升級為正式買賣盤。
 *
 * 走 curl 子程序 + 短 UA（{@code Mozilla/5.0}），避開 Yahoo 對 Java HttpClient HTTP/2
 * fingerprint 的封鎖（同 BotFxFetchClient / PriceFetchClient 慣例）。Yahoo {@code TWD=X} 即 USD/TWD。
 */
@Slf4j
@Component
public class YahooFxFetchClient {

    private static final String USD_TWD_URL =
            "https://query1.finance.yahoo.com/v8/finance/chart/TWD=X?interval=1d&range=5d";

    private final ObjectMapper mapper = new ObjectMapper();

    /** 取 USD/TWD 當日中間價（regularMarketPrice）；失敗回 empty。 */
    public Optional<BigDecimal> fetchUsdTwdMid() {
        try {
            ProcessBuilder pb = new ProcessBuilder("curl", "-s",
                    "-H", "User-Agent: Mozilla/5.0",
                    USD_TWD_URL);
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String body = new String(proc.getInputStream().readAllBytes());
            proc.waitFor();

            if (body == null || body.isBlank()) {
                log.warn("Yahoo TWD=X 回傳空白");
                return Optional.empty();
            }
            JsonNode meta = mapper.readTree(body)
                    .path("chart").path("result").path(0).path("meta");
            BigDecimal mid = decimal(meta.path("regularMarketPrice"));
            if (mid == null || mid.compareTo(BigDecimal.ZERO) <= 0) {
                log.warn("Yahoo TWD=X 無有效 regularMarketPrice");
                return Optional.empty();
            }
            return Optional.of(mid.setScale(4, RoundingMode.HALF_UP));
        } catch (Exception e) {
            log.warn("Yahoo TWD=X 匯率抓取失敗: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static BigDecimal decimal(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        String s = n.asText("");
        if (s.isBlank()) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
