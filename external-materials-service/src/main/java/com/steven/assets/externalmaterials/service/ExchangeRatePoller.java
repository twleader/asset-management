package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.BotFxFetchClient;
import com.steven.assets.externalmaterials.client.FxSpotQuote;
import com.steven.assets.externalmaterials.client.MegaFxFetchClient;
import com.steven.assets.externalmaterials.client.YahooFxFetchClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

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
public class ExchangeRatePoller {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private final BotFxFetchClient botFx;
    private final MegaFxFetchClient megaFx;
    private final YahooFxFetchClient yahooFx;
    private final HistoricalBackfillService backfill;
    private final StockSourceQuery store;
    private final BankFxTradingSessionPolicy liveSessionPolicy;
    private final ExchangeRateSpotCacheWriter liveCacheWriter;
    private final Executor liveUpdateExecutor;
    private final Clock clock;
    private final AtomicBoolean liveUpdateInFlight = new AtomicBoolean(false);

    @Autowired
    public ExchangeRatePoller(
            BotFxFetchClient botFx,
            MegaFxFetchClient megaFx,
            YahooFxFetchClient yahooFx,
            HistoricalBackfillService backfill,
            StockSourceQuery store,
            BankFxTradingSessionPolicy liveSessionPolicy,
            ExchangeRateSpotCacheWriter liveCacheWriter,
            @Qualifier("usdTwdLiveUpdateExecutor") Executor liveUpdateExecutor) {
        this(botFx, megaFx, yahooFx, backfill, store, liveSessionPolicy, liveCacheWriter,
                liveUpdateExecutor, Clock.system(TAIPEI));
    }

    ExchangeRatePoller(
            BotFxFetchClient botFx,
            MegaFxFetchClient megaFx,
            YahooFxFetchClient yahooFx,
            HistoricalBackfillService backfill,
            StockSourceQuery store,
            BankFxTradingSessionPolicy liveSessionPolicy,
            ExchangeRateSpotCacheWriter liveCacheWriter,
            Executor liveUpdateExecutor,
            Clock clock) {
        this.botFx = botFx;
        this.megaFx = megaFx;
        this.yahooFx = yahooFx;
        this.backfill = backfill;
        this.store = store;
        this.liveSessionPolicy = liveSessionPolicy;
        this.liveCacheWriter = liveCacheWriter;
        this.liveUpdateExecutor = liveUpdateExecutor;
        this.clock = clock.withZone(TAIPEI);
    }

    /** 保留舊測試建構方式；當前 thread 執行 worker，正式 bean 一律注入專用 executor。 */
    ExchangeRatePoller(
            BotFxFetchClient botFx,
            MegaFxFetchClient megaFx,
            YahooFxFetchClient yahooFx,
            HistoricalBackfillService backfill,
            StockSourceQuery store,
            BankFxTradingSessionPolicy liveSessionPolicy,
            ExchangeRateSpotCacheWriter liveCacheWriter,
            Clock clock) {
        this(botFx, megaFx, yahooFx, backfill, store, liveSessionPolicy, liveCacheWriter,
                Runnable::run, clock);
    }

    /** 保留既有 Task 306 純單元測試建構方式；live job 不會由此建構子啟動。 */
    ExchangeRatePoller(
            BotFxFetchClient botFx,
            MegaFxFetchClient megaFx,
            YahooFxFetchClient yahooFx,
            HistoricalBackfillService backfill,
            StockSourceQuery store) {
        this(botFx, megaFx, yahooFx, backfill, store, null, null, Runnable::run,
                Clock.system(TAIPEI));
    }

    /** USD/TWD live：全天每 2 秒 tick，外呼與 Redis 寫入只存在 external service。 */
    @Scheduled(cron = "*/2 * * * * *", zone = "Asia/Taipei")
    public void usdTwdLiveUpdate() {
        if (liveSessionPolicy == null || liveCacheWriter == null) return;
        Set<UsdTwdSource> eligible = liveSessionPolicy.eligibleSources();
        if (eligible.isEmpty()) return;

        Instant heartbeatAt = clock.instant();
        liveCacheWriter.writeHeartbeat(heartbeatAt, eligible);
        if (!liveUpdateInFlight.compareAndSet(false, true)) {
            log.debug("USD/TWD live 前一輪仍在執行，本輪 skip");
            return;
        }
        try {
            Set<UsdTwdSource> workerSources = Set.copyOf(eligible);
            liveUpdateExecutor.execute(() -> runUsdTwdLiveUpdate(workerSources));
        } catch (RuntimeException ex) {
            liveUpdateInFlight.set(false);
            log.warn("USD/TWD live worker 送出失敗，保留上一筆: {}", ex.getMessage());
        }
    }

    private void runUsdTwdLiveUpdate(Set<UsdTwdSource> eligible) {
        try {
            if (eligible.contains(UsdTwdSource.BANK_OF_TAIWAN)
                    && fetchAndWriteBank(safeFetch(() -> botFx.fetchSpot("USD"), "台銀"),
                    UsdTwdSource.BANK_OF_TAIWAN)) {
                return;
            }
            if (eligible.contains(UsdTwdSource.MEGA_BANK)
                    && fetchAndWriteBank(safeFetch(() -> megaFx.fetchSpot("USD"), "兆豐"),
                    UsdTwdSource.MEGA_BANK)) {
                return;
            }
            Optional<FxSpotQuote> yahoo = safeFetch(yahooFx::fetchUsdTwdQuote, "Yahoo");
            if (yahoo.isPresent()) {
                writeLiveQuote(yahoo.get(), UsdTwdSource.YAHOO);
            }
        } catch (RuntimeException ex) {
            log.warn("USD/TWD live 輪詢失敗，保留上一筆: {}", ex.getMessage());
        } finally {
            liveUpdateInFlight.set(false);
        }
    }

    private Optional<FxSpotQuote> safeFetch(
            Supplier<Optional<FxSpotQuote>> fetch,
            String sourceName) {
        try {
            Optional<FxSpotQuote> result = fetch.get();
            return result == null ? Optional.empty() : result;
        } catch (RuntimeException ex) {
            log.warn("USD/TWD live {} 抓取例外，改用下一來源: {}", sourceName, ex.getMessage());
            return Optional.empty();
        }
    }

    private boolean fetchAndWriteBank(Optional<FxSpotQuote> quote, UsdTwdSource source) {
        return quote.isPresent() && writeLiveQuote(quote.get(), source);
    }

    private boolean writeLiveQuote(FxSpotQuote quote, UsdTwdSource source) {
        Instant polledAt = clock.instant();
        Instant sourceUpdatedAt = source == UsdTwdSource.BANK_OF_TAIWAN
                ? null : quote.sourceUpdatedAt();
        LocalDate rateDate = source == UsdTwdSource.BANK_OF_TAIWAN
                ? polledAt.atZone(TAIPEI).toLocalDate()
                : sourceUpdatedAt == null ? null : sourceUpdatedAt.atZone(TAIPEI).toLocalDate();
        return liveCacheWriter.writeSpot(new UsdTwdSpotQuote(
                rateDate, quote.spotBuy(), quote.spotSell(), source, polledAt, sourceUpdatedAt));
    }

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
