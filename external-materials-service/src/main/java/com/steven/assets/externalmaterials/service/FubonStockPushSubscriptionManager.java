package com.steven.assets.externalmaterials.service;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Set;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import static com.steven.assets.externalmaterials.service.FubonMarketData.*;

/** Owns only stock desired-set renewal and the external stock consumer, never the index stream. */
@Service
public class FubonStockPushSubscriptionManager {
    private final String enabled;
    private final FubonMarketAccess access;
    private final MarketClock clock;
    private final FubonRadarScope radar;
    private final FubonMarketDataPort client;
    private final FubonStockPushStream stream;
    private final FubonStockPushConsumer consumer;
    private final AtomicBoolean inFlight = new AtomicBoolean();
    private boolean remotePossiblyActive;
    private volatile Scope authorized = new Scope(Set.of(), Instant.EPOCH);
    private volatile State lastState = new State("DISABLED", 0);
    public FubonStockPushSubscriptionManager(@Value("${fubon.stock-push-enabled:false}") String enabled,
            FubonMarketAccess access, MarketClock clock, FubonRadarScope radar, FubonMarketDataPort client,
            FubonStockPushStream stream, FubonStockPushConsumer consumer) {
        this.enabled = enabled; this.access = access; this.clock = clock; this.radar = radar;
        this.client = client; this.stream = stream; this.consumer = consumer;
    }
    @Scheduled(fixedDelay = 30000)
    public void refreshSubscriptions() {
        if (!inFlight.compareAndSet(false, true)) return;
        try { refresh(); }
        finally { inFlight.set(false); }
    }
    private synchronized void refresh() {
        String reason = featureReason(enabled, "STOCK_PUSH_DISABLED");
        if (reason == null) reason = access.unavailableReason();
        if (reason != null) { clear(reason); return; }
        try {
            var known = clock.isTwMarketOpenKnown();
            if (known.isEmpty()) { clear("CALENDAR_UNKNOWN"); return; }
            if (!known.get()) { clear("MARKET_CLOSED"); return; }
            List<String> codes = radar.current(300);
            if (codes.isEmpty()) { clear("NO_SYMBOLS"); return; }
            remotePossiblyActive = true;
            client.subscriptions(codes);
            // Local authorization is shorter than the adapter's 120s lease, so a stalled manager fails closed.
            Instant validUntil = clock.instant().plusSeconds(45);
            consumer.authorize(codes, validUntil);
            authorized = new Scope(Set.copyOf(codes), validUntil);
            consumer.repairPending();
            if (!stream.isRunning()) stream.start(consumer::accept);
            lastState = new State(stream.isReadyFor(Set.copyOf(codes)) ? "SUBSCRIBED" : "RECONNECTING", codes.size());
        } catch (Unavailable unavailable) { clear(unavailable.reason()); }
        catch (RuntimeException failure) { clear("FAILED"); }
    }
    private void clear(String reason) {
        authorized = new Scope(Set.of(), Instant.EPOCH);
        consumer.revoke();
        stream.stop();
        boolean cleanup = remotePossiblyActive;
        remotePossiblyActive = false;
        if (cleanup) {
            try { if (access.unavailableReason() == null) client.subscriptions(List.of()); }
            catch (RuntimeException ignored) { /* Local scope is already revoked; remote lease still bounds lifetime. */ }
        }
        lastState = new State(reason, 0);
    }
    @PreDestroy public synchronized void shutdown() { clear("DISABLED"); }
    public boolean pushEnabled() { return featureReason(enabled, "STOCK_PUSH_DISABLED") == null; }
    public String recoveryIdentity() { return stream.recoveryIdentity(); }
    public boolean isPricePushHealthy(String symbol) {
        Scope scope = authorized;
        try {
            return pushEnabled() && scope.symbols().contains(symbol) && clock.instant().isBefore(scope.validUntil())
                    && clock.isTwMarketOpenKnown().orElse(false) && stream.isReadyFor(scope.symbols());
        } catch (RuntimeException failure) { return false; }
    }
    public State state() { return lastState; }
    private record Scope(Set<String> symbols, Instant validUntil) {}
    public record State(String outcome, int symbolCount) {}
}
