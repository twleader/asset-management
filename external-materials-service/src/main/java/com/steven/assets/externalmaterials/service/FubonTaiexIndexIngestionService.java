package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.PriceFetchClient.PriceResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** DB-first consumer for one normalized Fubon TAIEX event. */
@Service
public class FubonTaiexIndexIngestionService {

    private static final String TAIEX_CODE = "0000";
    private static final String TW_MARKET = "台股";
    private static final String EXCHANGE = "TWSE";
    private static final String TYPE = "INDEX";
    private static final String SOURCE = "FUBON_INDICES";

    private final FubonTaiexIndexStore store;
    private final PriceCacheWriter writer;
    private final StockSourceQuery source;
    private final Clock clock;
    private final String configuredSymbol;
    /** Set only after a committed DB apply whose subsequent Redis projection failed. */
    private final Set<Instant> pendingCacheRepairAt = ConcurrentHashMap.newKeySet();

    @Autowired
    public FubonTaiexIndexIngestionService(
            FubonTaiexIndexStore store,
            PriceCacheWriter writer,
            StockSourceQuery source,
            @Value("${fubon.taiex-index-stream.symbol:}") String configuredSymbol) {
        this(store, writer, source, Clock.systemUTC(), configuredSymbol);
    }

    FubonTaiexIndexIngestionService(
            FubonTaiexIndexStore store,
            PriceCacheWriter writer,
            StockSourceQuery source,
            Clock clock,
            String configuredSymbol) {
        this.store = store;
        this.writer = writer;
        this.source = source;
        this.clock = clock;
        this.configuredSymbol = configuredSymbol == null ? "" : configuredSymbol.trim();
    }

    public enum IngestionOutcome {
        REJECTED,
        DB_FAILED,
        DB_APPLIED,
        DB_STALE_OR_EQUAL,
        CANONICAL_CACHE_REPAIR
    }

    /**
     * Persists first.  A raw stale/equal frame is never cached; only an exact-time reread of the
     * committed canonical row can repair a prior Redis failure.
     */
    public IngestionOutcome ingest(FubonTaiexIndexEvent event) {
        FubonTaiexIndexStore.Candidate candidate = validate(event);
        if (candidate == null) return IngestionOutcome.REJECTED;

        FubonTaiexIndexStore.PersistResult persisted = store.persist(candidate);
        if (persisted.status() == FubonTaiexIndexStore.PersistStatus.FAILED
                || persisted.canonical() == null) return IngestionOutcome.DB_FAILED;

        if (persisted.status() == FubonTaiexIndexStore.PersistStatus.APPLIED) {
            rememberProjectionOutcome(persisted.canonical(), projectCanonical(persisted.canonical()));
            return IngestionOutcome.DB_APPLIED;
        }
        if (persisted.canonical().providerUpdatedAt().equals(candidate.providerUpdatedAt())
                && pendingCacheRepairAt.contains(persisted.canonical().providerUpdatedAt())) {
            rememberProjectionOutcome(persisted.canonical(), projectCanonical(persisted.canonical()));
            return IngestionOutcome.CANONICAL_CACHE_REPAIR;
        }
        return IngestionOutcome.DB_STALE_OR_EQUAL;
    }

    private FubonTaiexIndexStore.Candidate validate(FubonTaiexIndexEvent event) {
        if (event == null || !FubonTaiexIndexContract.validSymbol(configuredSymbol)
                || !configuredSymbol.equals(event.symbol()) || !EXCHANGE.equals(event.exchange())
                || !TYPE.equals(event.type())) return null;
        BigDecimal point = FubonTaiexIndexContract.parsePositiveCanonicalIndex(event.index());
        Instant providerInstant = FubonTaiexIndexContract.instantFromMicros(event.timeMicros());
        if (point == null || providerInstant == null) return null;

        Instant now = clock.instant();
        LocalDate tradingDate = providerInstant.atZone(MarketClock.TW_ZONE).toLocalDate();
        if (!tradingDate.equals(LocalDate.now(clock.withZone(MarketClock.TW_ZONE)))
                || providerInstant.isAfter(now.plusSeconds(30))) return null;
        return new FubonTaiexIndexStore.Candidate(event.symbol(), tradingDate, providerInstant, point);
    }

    private PriceCacheWriter.CacheWriteOutcome projectCanonical(FubonTaiexIndexStore.CanonicalIndex canonical) {
        BigDecimal previousClose = previousCloseBefore(canonical.tradingDate());
        PriceResult result = new PriceResult(
                TAIEX_CODE, TW_MARKET, canonical.indexPoint(), null, null, SOURCE, "台股大盤",
                null, null, null, previousClose, null, null, null,
                canonical.tradingDate(), canonical.providerUpdatedAt());
        return writer.writeTaiwanIndexLive(result);
    }

    private void rememberProjectionOutcome(
            FubonTaiexIndexStore.CanonicalIndex canonical,
            PriceCacheWriter.CacheWriteOutcome outcome) {
        if (outcome == PriceCacheWriter.CacheWriteOutcome.FAILED) {
            pendingCacheRepairAt.add(canonical.providerUpdatedAt());
        } else {
            pendingCacheRepairAt.remove(canonical.providerUpdatedAt());
        }
    }

    private BigDecimal previousCloseBefore(LocalDate tradingDate) {
        List<StockSourceQuery.ClosePoint> rows = source.loadRecentTaiexCloses(2);
        for (int index = rows.size() - 1; index >= 0; index--) {
            StockSourceQuery.ClosePoint row = rows.get(index);
            if (row != null && row.date() != null && row.date().isBefore(tradingDate)) return row.close();
        }
        return null;
    }
}
