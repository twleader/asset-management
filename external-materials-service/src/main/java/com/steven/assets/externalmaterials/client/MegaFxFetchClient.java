package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Optional;

/**
 * 兆豐銀行牌告匯率 API（https://www.megabank.com.tw/api/client/ExchangeRate/GetRateData）。
 *
 * 台銀被 WAF 反爬挑戰封鎖時的第一備援。公開 GET 端點，免登入免 token，回傳 JSON，
 * 涵蓋所有追蹤幣別、含真實即期買入/賣出（非中間價，資料品質同台銀）。用 curl 子程序 + 短 UA，
 * 同 BotFxFetchClient / YahooFxFetchClient 慣例（避開 HTTP client fingerprint 封鎖）。
 */
@Slf4j
@Component
public class MegaFxFetchClient {

    private static final String RATE_URL =
            "https://www.megabank.com.tw/api/client/ExchangeRate/GetRateData?sc_lang=zh-TW&sc_site=bank-zh-tw&dic_lang=zh-TW";
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final DateTimeFormatter UPDATE_FORMAT = DateTimeFormatter
            .ofPattern("uuuuMMddHHmmss")
            .withResolverStyle(ResolverStyle.STRICT);

    private final ObjectMapper mapper = new ObjectMapper();

    /** 取指定幣別當日即期買入/賣出；找不到該幣別或抓取失敗回 empty。 */
    public Optional<FxSpotQuote> fetchSpot(String currency) {
        try {
            Optional<String> response = CurlProcessSupport.get(RATE_URL);
            if (response.isEmpty()) {
                log.warn("兆豐銀行 API curl 失敗或逾時");
                return Optional.empty();
            }
            String body = response.get();

            if (body == null || body.isBlank()) {
                log.warn("兆豐銀行 API 回傳空白");
                return Optional.empty();
            }
            JsonNode rates = mapper.readTree(body).path("rates");
            for (JsonNode rate : rates) {
                String currKey = rate.path("currKey").asText("");
                int sep = currKey.indexOf('|');
                String code = sep >= 0 ? currKey.substring(0, sep) : currKey;
                if (!currency.equalsIgnoreCase(code)) continue;
                JsonNode spot = rate.path("spot");
                BigDecimal bid = decimal(spot.path("bid"));
                BigDecimal ask = decimal(spot.path("ask"));
                if (bid == null || ask == null) {
                    log.warn("兆豐銀行 {} 即期匯率解析失敗: bid=[{}], ask=[{}]",
                            currency, spot.path("bid"), spot.path("ask"));
                    return Optional.empty();
                }
                Instant sourceUpdatedAt = parseUpdate(rate.path("update").asText(""));
                if (sourceUpdatedAt == null) {
                    log.warn("兆豐銀行 {} 缺少有效 update timestamp", currency);
                    return Optional.empty();
                }
                return Optional.of(new FxSpotQuote(bid, ask, sourceUpdatedAt));
            }
            log.warn("兆豐銀行 API 找不到 {} 的匯率資料", currency);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("兆豐銀行匯率抓取失敗 ({}): {}", currency, e.getMessage());
            return Optional.empty();
        }
    }

    static Instant parseUpdate(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDateTime.parse(raw.trim(), UPDATE_FORMAT).atZone(TAIPEI).toInstant();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static BigDecimal decimal(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return null;
        String s = n.asText("");
        if (s.isBlank()) return null;
        try {
            return new BigDecimal(s).setScale(4, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
