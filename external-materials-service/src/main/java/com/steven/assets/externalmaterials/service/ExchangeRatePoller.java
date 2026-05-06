package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.BotFxFetchClient;
import com.steven.assets.externalmaterials.client.BotFxFetchClient.SpotQuote;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * 匯率排程：盤中每 5 分鐘 BOT、收盤後 17:00 FinMind。
 *
 * 從 business-services HistoricalDataService 搬遷至此。所有對外行情 API 集中在 external-materials-service。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeRatePoller {

    private final BotFxFetchClient botFx;
    private final HistoricalBackfillService backfill;
    private final StockSourceQuery store;

    /**
     * 盤中匯率：每 5 分鐘從台銀牌告抓即期匯率，寫入今日 exchange_rate_history（同 currency+rate_date 為覆寫）。
     * 台灣外匯交易：週一~五 09:00~16:00 Asia/Taipei；09:00 整點跳過避開市場未開盤。
     */
    @Scheduled(cron = "0 0/5 9-15 * * MON-FRI", zone = "Asia/Taipei")
    public void intradayExchangeRateUpdate() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Taipei"));
        if (now.getHour() == 9 && now.getMinute() < 5) return;
        log.info("排程：盤中更新匯率（台灣銀行） ({}:{})", now.getHour(),
                String.format("%02d", now.getMinute()));
        LocalDate today = now.toLocalDate();
        for (String currency : store.collectTrackedCurrencies()) {
            botFx.fetchSpot(currency).ifPresent(q ->
                    upsertSpot(currency, today, q));
        }
    }

    /**
     * 收盤後匯率：17:00 走 FinMind TaiwanExchangeRate 增量補（涵蓋 BOT 沒抓到 / 開盤前缺漏）。
     */
    @Scheduled(cron = "0 0 17 * * MON-FRI", zone = "Asia/Taipei")
    public void dailyExchangeRateUpdate() {
        log.info("排程：收盤後 FinMind 增量補匯率");
        LocalDate since = LocalDate.now().minusDays(5);
        for (String currency : store.collectTrackedCurrencies()) {
            backfill.backfillExchangeRate(currency, since);
        }
    }

    /** 手動觸發（business-services /api/market-data/exchange-rate/refresh proxy）。 */
    public boolean refreshBotNow(String currency) {
        return botFx.fetchSpot(currency)
                .map(q -> {
                    upsertSpot(currency, LocalDate.now(ZoneId.of("Asia/Taipei")), q);
                    return true;
                })
                .orElse(false);
    }

    private void upsertSpot(String currency, LocalDate today, SpotQuote q) {
        store.upsertExchangeRate(currency, today, q.spotBuy(), q.spotSell());
        log.info("台灣銀行 {} 匯率: buy={}, sell={} ({})", currency, q.spotBuy(), q.spotSell(), today);
    }
}
