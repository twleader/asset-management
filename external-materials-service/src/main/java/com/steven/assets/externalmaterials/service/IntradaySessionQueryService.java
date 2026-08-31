package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.steven.assets.externalmaterials.client.PriceFetchClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Owns the non-public intraday session path used by the authenticated popup and server-side
 * renderer. Tick buckets are cache-only reads; the sole bounded request-time self-heal is an
 * exact TWSE MIS {@code d/y} session-reference fact. Public Task372 quote paths use separate
 * pure readers and never enter this service.
 */
@Service
public class IntradaySessionQueryService {
    private static final int ATTEMPT_LIMIT = 256;
    private static final Pattern STOCK_CODE = Pattern.compile("^[0-9A-Z]{2,10}$");
    private static final ExecutorService REFERENCE_EXECUTOR = Executors.newVirtualThreadPerTaskExecutor();

    private final MarketClock clock;
    private final IntradayTickStore tickStore;
    private final StockSourceQuery stockSource;
    private final SessionReferencePriceStore referenceStore;
    private final PriceFetchClient priceFetch;
    private final Clock wallClock;
    private final Duration waitTimeout;
    private final Duration cooldown;
    private final Executor referenceExecutor;
    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final Object attemptLock = new Object();

    public IntradaySessionQueryService(MarketClock clock, IntradayTickStore tickStore,
                                       StockSourceQuery stockSource,
                                       SessionReferencePriceStore referenceStore, PriceFetchClient priceFetch) {
        this(clock, tickStore, stockSource, referenceStore, priceFetch,
                Clock.systemUTC(), Duration.ofSeconds(8), Duration.ofSeconds(60), REFERENCE_EXECUTOR);
    }

    /** Package-private seam permits deterministic cooldown, timeout, and source-time tests. */
    IntradaySessionQueryService(MarketClock clock, IntradayTickStore tickStore,
                                StockSourceQuery stockSource,
                                SessionReferencePriceStore referenceStore, PriceFetchClient priceFetch,
                                Clock wallClock, Duration waitTimeout, Duration cooldown, Executor referenceExecutor) {
        this.clock = clock;
        this.tickStore = tickStore;
        this.stockSource = stockSource;
        this.referenceStore = referenceStore;
        this.priceFetch = priceFetch;
        this.wallClock = wallClock;
        this.waitTimeout = waitTimeout;
        this.cooldown = cooldown;
        this.referenceExecutor = referenceExecutor;
    }

    public record IntradaySourceSession(
            @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate tradingDate,
            List<IntradayTickStore.TickPoint> ticks,
            BigDecimal sessionReferencePrice,
            @JsonFormat(shape = JsonFormat.Shape.STRING) LocalDate sessionReferenceDate,
            String sessionReferenceSource) {
        public IntradaySourceSession {
            if (tradingDate == null) throw new IllegalArgumentException("tradingDate is required");
            ticks = ticks == null ? List.of() : List.copyOf(ticks);
            if (sessionReferencePrice != null && (sessionReferencePrice.signum() <= 0
                    || !tradingDate.equals(sessionReferenceDate)
                    || !SessionReferencePriceStore.TWSE_MIS_Y.equals(sessionReferenceSource))) {
                throw new IllegalArgumentException("invalid session reference");
            }
            if (sessionReferencePrice == null) {
                sessionReferenceDate = null;
                sessionReferenceSource = null;
            }
        }
    }

    public IntradaySourceSession query(String code, String market, LocalDate requestedDate) {
        LocalDate today = LocalDate.now(wallClock.withZone(MarketClock.zoneOf(market)));
        LocalDate target;
        List<IntradayTickStore.TickPoint> ticks;
        if (requestedDate != null) {
            target = requestedDate;
            ticks = readTicks(code, market, requestedDate);
        } else if (clock.isTradingDay(market, today)) {
            ticks = readTicks(code, market, today);
            if (!ticks.isEmpty()) {
                target = today;
            } else {
                target = stockSource.findMaxTradingDate(code, market).orElse(today);
                ticks = readTicks(code, market, target);
            }
        } else {
            target = stockSource.findMaxTradingDate(code, market).orElse(today);
            ticks = readTicks(code, market, target);
        }

        Optional<SessionReferencePriceStore.SessionReferencePrice> evidence = referenceFor(code, market, target);
        if (evidence.isEmpty()) return new IntradaySourceSession(target, ticks, null, null, null);
        SessionReferencePriceStore.SessionReferencePrice reference = evidence.get();
        return new IntradaySourceSession(target, ticks, reference.price(), target, reference.source());
    }

    /**
     * A request may select a cache bucket but never refresh it. In particular, it must not reach
     * {@link IntradayTickRefresher}, FinMind, Yahoo, tick replacement, DB persistence, or publish.
     */
    private List<IntradayTickStore.TickPoint> readTicks(String code, String market, LocalDate target) {
        List<IntradayTickStore.TickPoint> ticks = tickStore.getTicks(code, market, target);
        return ticks == null ? List.of() : ticks;
    }

    /**
     * Only an exact-date, positive TWSE MIS {@code d/y} fact qualifies. Fubon previousClose is not
     * an independently verified same-session {@code d/y} value, and Yahoo cannot substitute. The
     * global source priority therefore has no competing qualified Fubon/Yahoo candidate here.
     */
    private Optional<SessionReferencePriceStore.SessionReferencePrice> referenceFor(
            String code, String market, LocalDate target) {
        if (!"台股".equals(market) || target == null || code == null
                || !STOCK_CODE.matcher(code).matches() || "0000".equals(code)) {
            return Optional.empty();
        }
        Optional<SessionReferencePriceStore.SessionReferencePrice> cached = referenceStore.get(code, market, target);
        if (cached.isPresent()) return cached;
        // Tick reads stay cache-only. Any current-session reference-cache miss may launch the
        // separate exact TWSE MIS lookup, even when ticks are already complete; history never does.
        LocalDate today = LocalDate.now(wallClock.withZone(MarketClock.zoneOf(market)));
        if (!target.equals(today) || !clock.isTradingDay(market, target)) {
            return Optional.empty();
        }
        String key = market + ':' + code + ':' + target;
        Attempt attempt;
        synchronized (attemptLock) {
            cleanupAttemptsLocked();
            attempt = attempts.get(key);
            if (attempt == null) {
                if (attempts.size() >= ATTEMPT_LIMIT) return Optional.empty();
                attempt = new Attempt();
                Attempt created = attempt;
                CompletableFuture<SessionReferencePriceStore.SessionReferencePrice> future =
                        CompletableFuture.supplyAsync(() -> priceFetch.fetchTwSessionReference(code, target)
                                .filter(reference -> target.equals(reference.tradingDate())
                                        && SessionReferencePriceStore.TWSE_MIS_Y.equals(reference.source())
                                        && reference.price().signum() > 0)
                                .map(reference -> {
                                    SessionReferencePriceStore.SessionReferencePrice evidence =
                                            new SessionReferencePriceStore.SessionReferencePrice(
                                                    reference.price(), reference.source(), Instant.now(wallClock));
                                    referenceStore.put(code, market, target, evidence);
                                    return evidence;
                                }).orElse(null), referenceExecutor);
                created.future = future.whenComplete((ignored, failure) -> created.completedAtMillis = wallClock.millis());
                attempts.put(key, created);
            }
        }
        try {
            return Optional.ofNullable(attempt.future.get(waitTimeout.toMillis(), TimeUnit.MILLISECONDS));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private void cleanupAttemptsLocked() {
        long now = wallClock.millis();
        attempts.entrySet().removeIf(entry -> entry.getValue().future.isDone()
                && entry.getValue().completedAtMillis >= 0
                && now - entry.getValue().completedAtMillis >= cooldown.toMillis());
    }

    private static final class Attempt {
        private volatile long completedAtMillis = -1;
        private CompletableFuture<SessionReferencePriceStore.SessionReferencePrice> future;
    }
}
