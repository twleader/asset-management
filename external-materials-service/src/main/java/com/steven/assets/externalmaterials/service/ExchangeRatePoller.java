package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.BotFxFetchClient;
import com.steven.assets.externalmaterials.client.FxSpotQuote;
import com.steven.assets.externalmaterials.client.MegaFxFetchClient;
import com.steven.assets.externalmaterials.client.YahooFxFetchClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * 匯率排程：盤中每 5 分鐘 台銀 → 兆豐銀行 → USD 才退 Yahoo 當日中間價；收盤後 17:00 FinMind（T-1 對帳回補）。
 *
 * 從 business-services HistoricalDataService 搬遷至此。所有對外行情 API 集中在 external-materials-service。
 *
 * 來源鏈（見 design.md「匯率來源鏈」、Task 142、Task 306）：台銀牌告 {@link BotFxFetchClient} 為當日主來源
 * （真實即期買賣）；台銀失敗時改抓兆豐銀行牌告 API {@link MegaFxFetchClient}（同樣真實即期買賣、涵蓋所有
 * 追蹤幣別）；兩者皆失敗時，USD 改用 {@link YahooFxFetchClient} 當日中間價暫定（buy=sell=mid），隔日由
 * 17:00 FinMind 以真實買賣價覆寫同一 (currency, rate_date) 列。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeRatePoller {

    private final BotFxFetchClient botFx;
    private final MegaFxFetchClient megaFx;
    private final YahooFxFetchClient yahooFx;
    private final HistoricalBackfillService backfill;
    private final StockSourceQuery store;

    /**
     * 盤中匯率：每 5 分鐘從台銀牌告抓即期匯率（失敗改兆豐銀行），寫入今日 exchange_rate_history（同 currency+rate_date 為覆寫）。
     * 台灣外匯交易：週一~五 09:00~16:00 Asia/Taipei；09:00 整點跳過避開市場未開盤。
     */
    @Scheduled(cron = "0 0/5 9-15 * * MON-FRI", zone = "Asia/Taipei")
    public void intradayExchangeRateUpdate() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Taipei"));
        if (now.getHour() == 9 && now.getMinute() < 5) return;
        log.info("排程：盤中更新匯率（台灣銀行 → 兆豐銀行） ({}:{})", now.getHour(),
                String.format("%02d", now.getMinute()));
        LocalDate today = now.toLocalDate();
        for (String currency : store.collectTrackedCurrencies()) {
            updateOne(currency, today);
        }
    }

    /**
     * 收盤後匯率：17:00 走 FinMind TaiwanExchangeRate 增量補（涵蓋 BOT/兆豐沒抓到 / 開盤前缺漏）。
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
        return updateOne(currency, LocalDate.now(ZoneId.of("Asia/Taipei")));
    }

    /**
     * 抓單一幣別當日即期匯率寫入今日列。
     * 優先台銀牌告（含真實即期買入/賣出）；台銀失敗時改抓兆豐銀行牌告（同樣含真實即期買入/賣出，
     * 涵蓋所有追蹤幣別）；兩者皆失敗時，USD 才退到 Yahoo 當日中間價暫定（buy=sell=mid），隔日 17:00
     * FinMind 會以真實買賣價覆寫同一 (currency, rate_date) 列。USD 以外幣別若台銀與兆豐皆缺值，
     * 不另尋備援，接受沿用 FinMind 的 T-1 值。
     *
     * package-private（非 private）供同套件測試直接呼叫、注入固定日期。
     *
     * @return 是否成功寫入今日列（台銀、兆豐、Yahoo 任一）
     */
    boolean updateOne(String currency, LocalDate today) {
        Optional<FxSpotQuote> bot = botFx.fetchSpot(currency);
        if (bot.isPresent()) {
            upsertSpot("台灣銀行", currency, today, bot.get());
            return true;
        }
        Optional<FxSpotQuote> mega = megaFx.fetchSpot(currency);
        if (mega.isPresent()) {
            upsertSpot("兆豐銀行", currency, today, mega.get());
            return true;
        }
        if ("USD".equals(currency)) {
            Optional<BigDecimal> mid = yahooFx.fetchUsdTwdMid();
            if (mid.isPresent()) {
                store.upsertExchangeRate(currency, today, mid.get(), mid.get());
                log.info("Yahoo 備援 {} 當日中間價（買=賣=中間價，待 FinMind 隔日覆寫）: {} ({})",
                        currency, mid.get(), today);
                return true;
            }
        }
        return false;
    }

    private void upsertSpot(String sourceName, String currency, LocalDate today, FxSpotQuote q) {
        store.upsertExchangeRate(currency, today, q.spotBuy(), q.spotSell());
        log.info("{} {} 匯率: buy={}, sell={} ({})", sourceName, currency, q.spotBuy(), q.spotSell(), today);
    }
}
