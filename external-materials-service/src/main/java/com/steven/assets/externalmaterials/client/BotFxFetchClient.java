package com.steven.assets.externalmaterials.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * 台灣銀行牌告匯率 CSV (https://rate.bot.com.tw/xrt/flcsv/0/day)。
 *
 * CSV 回應為 UTF-8 with BOM + CRLF；用 curl 子程序避免 Java HttpClient 被 BOT 擋。
 *  - 買入區: 0=幣別, 1=匯率, 2=現金買入, 3=即期買入
 *  - 賣出區: 11=匯率, 12=現金賣出, 13=即期賣出
 */
@Slf4j
@Component
public class BotFxFetchClient {

    public Optional<FxSpotQuote> fetchSpot(String currency) {
        try {
            Optional<String> response = CurlProcessSupport.get(
                    "https://rate.bot.com.tw/xrt/flcsv/0/day");
            if (response.isEmpty()) {
                log.warn("台灣銀行 CSV curl 失敗或逾時");
                return Optional.empty();
            }
            String csvBody = response.get();

            if (csvBody == null || csvBody.trim().isEmpty()) {
                log.warn("台灣銀行 CSV 回傳空白");
                return Optional.empty();
            }
            csvBody = csvBody.replace("﻿", "");
            // 2026/06 起台銀全站套 Akamai SEC-CPT 反爬挑戰：HTTP 200 但 body 是 HTML 挑戰頁而非 CSV。
            // 明確辨識以免誤報為「找不到 USD」，並交由上游（ExchangeRatePoller）依序改用兆豐銀行／Yahoo 備援。
            if (csvBody.stripLeading().startsWith("<")) {
                log.warn("台灣銀行 {} 牌告回傳非 CSV（疑似 WAF 反爬挑戰頁，len={}），改由備援來源處理",
                        currency, csvBody.length());
                return Optional.empty();
            }
            for (String line : csvBody.split("\\r?\\n")) {
                String trimmed = line.trim();
                if (!trimmed.startsWith(currency + ",")) continue;
                String[] cols = trimmed.split(",");
                if (cols.length < 14) {
                    log.warn("台灣銀行 CSV {} 欄位不足: {} 欄", currency, cols.length);
                    return Optional.empty();
                }
                BigDecimal spotBuy = parse(cols[3]);
                BigDecimal spotSell = parse(cols[13]);
                if (spotBuy == null || spotSell == null) {
                    log.warn("台灣銀行 CSV {} 即期匯率解析失敗: buy=[{}], sell=[{}]", currency, cols[3], cols[13]);
                    return Optional.empty();
                }
                return Optional.of(new FxSpotQuote(spotBuy, spotSell));
            }
            log.warn("台灣銀行 CSV 找不到 {} 的匯率資料", currency);
            return Optional.empty();
        } catch (Exception e) {
            log.warn("台灣銀行匯率抓取失敗 ({}): {}", currency, e.getMessage());
            return Optional.empty();
        }
    }

    private static BigDecimal parse(String value) {
        if (value == null) return null;
        String trimmed = value.trim().replaceAll("[^0-9.]", "");
        if (trimmed.isEmpty()) return null;
        try {
            return new BigDecimal(trimmed).setScale(4, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
