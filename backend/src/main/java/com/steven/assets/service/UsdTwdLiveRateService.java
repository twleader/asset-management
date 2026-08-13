package com.steven.assets.service;

import com.steven.assets.model.ExchangeRateHistory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/** 固定 USD/TWD live query；只依賴 cache port，必要時唯讀歷史 DB。 */
@Service
public class UsdTwdLiveRateService {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final Set<String> CACHE_SOURCES =
            Set.of("BANK_OF_TAIWAN", "MEGA_BANK", "YAHOO");
    private static final Set<String> TIMESTAMPED_SOURCES = Set.of("MEGA_BANK", "YAHOO");
    private static final Set<String> BANK_SOURCES = Set.of("BANK_OF_TAIWAN", "MEGA_BANK");

    private final UsdTwdLiveRateCachePort cache;
    private final HistoricalDataService historicalData;
    private final Clock clock;

    @Autowired
    public UsdTwdLiveRateService(
            UsdTwdLiveRateCachePort cache,
            HistoricalDataService historicalData) {
        this(cache, historicalData, Clock.systemUTC());
    }

    UsdTwdLiveRateService(
            UsdTwdLiveRateCachePort cache,
            HistoricalDataService historicalData,
            Clock clock) {
        this.cache = cache;
        this.historicalData = historicalData;
        this.clock = clock;
    }

    public LiveRate getLiveRate() {
        Instant now = clock.instant();
        final UsdTwdLiveRateCachePort.Snapshot snapshot;
        try {
            snapshot = cache.readSnapshot();
        } catch (MalformedUsdTwdRateException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            return fallback("UNAVAILABLE", "STALE");
        }

        if (snapshot == null) throw new MalformedUsdTwdRateException("USD/TWD cache snapshot 為 null");
        String liveStatus = snapshot.heartbeat()
                .map(heartbeat -> heartbeatStatus(heartbeat, now))
                .orElse("INACTIVE");
        if (snapshot.spot().isEmpty()) {
            return fallback(liveStatus, "INACTIVE".equals(liveStatus) ? "LAST_AVAILABLE" : "STALE");
        }

        UsdTwdLiveRateCachePort.Spot spot = validateSpot(snapshot.spot().orElseThrow());
        boolean fresh = isFresh(spot.polledAt(), now);
        String quoteStatus;
        if ("INACTIVE".equals(liveStatus)) {
            quoteStatus = "LAST_AVAILABLE";
        } else if (!fresh) {
            quoteStatus = "STALE";
        } else if (BANK_SOURCES.contains(spot.source())) {
            quoteStatus = "LIVE";
        } else {
            quoteStatus = "INDICATIVE";
        }
        return new LiveRate(
                "USD/TWD", "USD", "TWD", spot.rateDate(), spot.buyRate(), spot.sellRate(),
                spot.source(), spot.polledAt(), spot.sourceUpdatedAt(), liveStatus, quoteStatus);
    }

    private String heartbeatStatus(UsdTwdLiveRateCachePort.Heartbeat heartbeat, Instant now) {
        if (heartbeat.heartbeatAt() == null || heartbeat.eligibleSources() == null
                || heartbeat.eligibleSources().isEmpty()) {
            malformed("heartbeat 欄位缺漏");
        }
        Set<String> seen = new HashSet<>();
        for (String source : heartbeat.eligibleSources()) {
            if (!BANK_SOURCES.contains(source) || !seen.add(source)) {
                malformed("heartbeat eligibleSources 非法");
            }
        }
        if (heartbeat.heartbeatAt().isAfter(now.plusSeconds(120))) {
            malformed("heartbeat 時間超出容許範圍");
        }
        return isFresh(heartbeat.heartbeatAt(), now) ? "ACTIVE" : "INACTIVE";
    }

    private UsdTwdLiveRateCachePort.Spot validateSpot(UsdTwdLiveRateCachePort.Spot spot) {
        if (spot.rateDate() == null || spot.buyRate() == null || spot.sellRate() == null
                || spot.source() == null || spot.polledAt() == null
                || !CACHE_SOURCES.contains(spot.source())
                || spot.buyRate().signum() <= 0 || spot.sellRate().signum() <= 0
                || spot.buyRate().compareTo(spot.sellRate()) > 0) {
            malformed("spot rate/source 非法");
        }
        if ("BANK_OF_TAIWAN".equals(spot.source())) {
            if (spot.sourceUpdatedAt() != null
                    || !spot.rateDate().equals(spot.polledAt().atZone(TAIPEI).toLocalDate())) {
                malformed("台銀 spot timestamp/date 組合非法");
            }
        } else if (spot.sourceUpdatedAt() == null
                || spot.sourceUpdatedAt().isAfter(spot.polledAt().plusSeconds(120))
                || !spot.rateDate().equals(spot.sourceUpdatedAt().atZone(TAIPEI).toLocalDate())) {
            malformed("有 timestamp 來源的 spot date/time 組合非法");
        }
        validateHighWatermarks(spot);
        return spot;
    }

    private void validateHighWatermarks(UsdTwdLiveRateCachePort.Spot spot) {
        Map<String, Instant> watermarks = spot.sourceUpdatedAtHighWatermarks();
        if (watermarks == null) malformed("spot high-watermark map 缺漏");
        for (Map.Entry<String, Instant> entry : watermarks.entrySet()) {
            if (!TIMESTAMPED_SOURCES.contains(entry.getKey()) || entry.getValue() == null) {
                malformed("spot high-watermark entry 非法");
            }
        }
        if (TIMESTAMPED_SOURCES.contains(spot.source())
                && !spot.sourceUpdatedAt().equals(watermarks.get(spot.source()))) {
            malformed("spot current source 與 high-watermark 不一致");
        }
    }

    private LiveRate fallback(String liveStatus, String quoteStatus) {
        ExchangeRateHistory row = historicalData.getLatestExchangeRate("USD")
                .orElseThrow(() -> new NoSuchElementException("查無 USD/TWD 即期或歷史匯率"));
        if (row.getRateDate() == null || row.getBuyRate() == null || row.getSellRate() == null
                || row.getBuyRate().signum() <= 0 || row.getSellRate().signum() <= 0
                || row.getBuyRate().compareTo(row.getSellRate()) > 0) {
            throw new MalformedUsdTwdRateException("USD/TWD 歷史 fallback 非法");
        }
        return new LiveRate(
                "USD/TWD", "USD", "TWD", row.getRateDate(), row.getBuyRate(), row.getSellRate(),
                "HISTORY", null, null, liveStatus, quoteStatus);
    }

    private static boolean isFresh(Instant value, Instant now) {
        Duration age = Duration.between(value, now);
        return age.compareTo(Duration.ofSeconds(6)) <= 0
                && age.compareTo(Duration.ofSeconds(-120)) >= 0;
    }

    private static void malformed(String message) {
        throw new MalformedUsdTwdRateException(message);
    }

    /** application/domain result；Jackson 屬性只存在 controller transport DTO。 */
    public record LiveRate(
            String pair,
            String baseCurrency,
            String quoteCurrency,
            LocalDate date,
            BigDecimal buyRate,
            BigDecimal sellRate,
            String source,
            Instant polledAt,
            Instant sourceUpdatedAt,
            String liveUpdateStatus,
            String quoteStatus) {
    }
}
