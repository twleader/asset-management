package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.client.EtfNavFetchClient.EtfNav;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * ETF 淨值／折溢價寫入 Redis（Task 214）。
 *
 * <pre>
 *   price:etfnav:{market}:{code}   String(JSON)  TTL 96h
 * </pre>
 *
 * <p><b>為什麼另開 key 而非塞進既有 {@code price:{market}:{code}}</b>：那支 payload 有三個寫入者
 * （{@code PriceCacheWriter.write} / {@code writeVerifiedClose} / {@code syncClosedFromDb}），
 * 多帶欄位會被彼此互相覆蓋，且會波及 {@code ClosePersister} 的收盤回填掃描與 {@code price-update}
 * pub/sub 的 SSE 契約。淨值更新頻率與來源都與成交價不同，獨立 key 最小侵入。
 *
 * <p><b>為什麼 TTL 是 96 小時而非比照即時價的 24 小時</b>：淨值一天只有一組有意義的值，而抓取
 * 只在交易時段進行。若只留 24h，週末與連假後的第一份匯出會整欄空白（週五最後一筆已過期）。
 * 96h 讓資料撐過週末＋一天連假；payload 內帶 {@code navAsOf}，取用端要判斷新舊時看那個欄位，
 * 不要以「Redis 裡有值」推論「是今天的值」。
 *
 * <p>抓不到時<b>不寫入</b>（保留上一輪的值），與 {@code PriceCacheWriter} 對 {@code z='-'} 的處置一致：
 * 寧可留著上一個成功 tick，也不要用推估值充數。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EtfNavCacheWriter {

    private final StringRedisTemplate redis;

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);

    /** 撐過週末＋一天連假；理由見 class javadoc。 */
    private static final Duration NAV_TTL = Duration.ofHours(96);

    public static String key(String market, String code) {
        return "price:etfnav:" + market + ":" + code;
    }

    /** 寫入單筆；失敗只記 log 不拋出（不得中斷整輪排程）。 */
    public void write(EtfNav nav) {
        if (nav == null || nav.stockCode() == null) return;
        Map<String, Object> payload = new HashMap<>();
        payload.put("stockCode", nav.stockCode());
        payload.put("market", nav.market());
        payload.put("nav", nav.nav());
        // 台股沿用證交所 all_etf.txt 已算好的 g 欄，百分比數值（1.2 = 溢價 1.2%）；
        // 美股恆為 null（Yahoo 未提供該欄，見 MarketDataFetchService.getUsEtfNav），
        // 而 MAPPER 設了 NON_NULL，故此欄根本不會出現在美股 payload 裡——
        // 美股折溢價由取用端 EtfLivePremiumCalculator 以「該列自己顯示的即時價」反推，不在此寫入。
        payload.put("premiumDiscountPct", nav.premiumDiscountPct());
        payload.put("navAsOf", nav.navAsOf());
        payload.put("source", nav.source());
        // Task 252：顯式台北牆鐘。此值前端直接顯示，且 StockPriceService 會跨 key 取 max——
        // 若跟著 JVM 預設時區跑，切換當下新舊 tick 會是兩種基準，max 恆被先覆寫的那一筆鎖住。
        payload.put("updatedAt", LocalDateTime.now(MarketClock.TW_ZONE).toString());
        try {
            redis.opsForValue().set(key(nav.market(), nav.stockCode()),
                    MAPPER.writeValueAsString(payload), NAV_TTL);
        } catch (Exception e) {
            log.warn("寫入 ETF 淨值 Redis 失敗 {} {}: {}", nav.market(), nav.stockCode(), e.getMessage());
        }
    }
}
