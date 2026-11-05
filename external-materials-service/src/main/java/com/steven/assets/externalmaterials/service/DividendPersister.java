package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.DividendFetchClient;
import com.steven.assets.externalmaterials.model.marketdata.DividendData;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 股利 evidence 抓取排程。只 append snapshot/event/observation；不維護
 * {@code stock_dividend_history} current-state，也不做 ACTIVE/CANCELLED 決策。
 */
@Slf4j
@Service
public class DividendPersister {

    private static final int RETAIN_YEARS = 10;

    private final DividendFetchClient client;
    private final StockSourceQuery source;
    private final DividendSnapshotStore snapshots;

    @EventListener(ApplicationReadyEvent.class)
    public void warmupOnStartup() {
        new Thread(() -> {
            Set<String> tw = new LinkedHashSet<>(), us = new LinkedHashSet<>(), uk = new LinkedHashSet<>();
            source.collectAllStockCodes(tw, us, uk);
            // 英股 UCITS ETF 配息由 MarketDataFetchService.getDividendRate 即時走 Yahoo chart?events=div，
            // 不在這裡寫 stock_dividend_history（Yahoo TTM 計算每次查詢都精確，無需快取）
            int count = 0;
            for (String code : tw) {
                try { syncOne(code, "台股"); count++; Thread.sleep(300); }
                catch (Exception e) { log.warn("dividend warmup TW {}: {}", code, e.getMessage()); }
            }
            for (String code : us) {
                try { syncOne(code, "美股"); count++; Thread.sleep(500); }
                catch (Exception e) { log.warn("dividend warmup US {}: {}", code, e.getMessage()); }
            }
            log.info("dividend 啟動 warmup 完成：{} 檔", count);
        }, "dividend-warmup").start();
    }

    @Scheduled(cron = "0 0 17 * * MON-FRI", zone = "Asia/Taipei")
    public void scheduledSyncAll() {
        log.info("排程：同步股利歷史");
        Set<String> tw = new LinkedHashSet<>(), us = new LinkedHashSet<>(), uk = new LinkedHashSet<>();
        source.collectAllStockCodes(tw, us, uk);
        int ok = 0, fail = 0;
        for (String code : tw) {
            try { syncOne(code, "台股"); ok++; Thread.sleep(300); }
            catch (Exception e) { fail++; log.warn("dividend sync TW {}: {}", code, e.getMessage()); }
        }
        for (String code : us) {
            try { syncOne(code, "美股"); ok++; Thread.sleep(500); }
            catch (Exception e) { fail++; log.warn("dividend sync US {}: {}", code, e.getMessage()); }
        }
        log.info("股利歷史同步完成：已處理 {} 檔、呼叫例外 {} 檔；金融品質依各股來源觀測判定", ok, fail);
    }

    /** Compatibility entry point; detailed source status is available on the internal sync response. */
    public int syncOne(String code, String market) { return syncOne(code, market, RETAIN_YEARS); }
    public int syncOne(String code, String market, int years) { return syncOneDetailed(code, market, years).written(); }

    public record SourceStatus(String provider, DividendData.FetchStatus status, java.time.LocalDate scopeFrom,
                               java.time.LocalDate scopeTo, int eventCount, boolean dateGroupsComplete,
                               DividendData.SourceFailureKind failureKind, Instant capturedAt, Instant observedAt, String reason) {}
    public record SyncResult(int written, List<SourceStatus> sourceStatuses, boolean quotaRelated,
                             boolean quotaRetryPending, int pendingYears, String queueStatus, Instant retryNotBefore) {
        public SyncResult { sourceStatuses = List.copyOf(sourceStatuses); }
    }
    private record StockKey(String code, String market) {}
    private record Pending(int years, Instant firstQueuedAt, long generation) {}
    private static final int MAX_PENDING = 2000, MAX_RETRY_PER_ROUND = 10;
    private static final Duration MAX_PENDING_AGE = Duration.ofHours(24);
    private final Supplier<Instant> now;
    private final Map<StockKey, Pending> pendingQuota = new LinkedHashMap<>();
    private final AtomicBoolean retryRunning = new AtomicBoolean();
    private long generation;

    @Autowired
    public DividendPersister(DividendFetchClient client, StockSourceQuery source, DividendSnapshotStore snapshots,
                             MarketClock clock) { this(client, source, snapshots, clock::instant); }
    public DividendPersister(DividendFetchClient client, StockSourceQuery source, DividendSnapshotStore snapshots) {
        this(client, source, snapshots, Instant::now);
    }
    DividendPersister(DividendFetchClient client, StockSourceQuery source, DividendSnapshotStore snapshots, Clock clock) {
        this(client, source, snapshots, clock::instant);
    }
    private DividendPersister(DividendFetchClient client, StockSourceQuery source, DividendSnapshotStore snapshots,
                              Supplier<Instant> now) {
        this.client = client; this.source = source; this.snapshots = snapshots; this.now = now;
    }
    public SyncResult syncOneDetailed(String code, String market, int years) { return sync(code, market, years, true); }

    private SyncResult sync(String code, String market, int years, boolean updateQueue) {
        if (years < 1 || years > 20) throw new IllegalArgumentException("years must be 1..20");
        if (code == null || code.isBlank() || !Set.of("台股", "美股").contains(market))
            throw new IllegalArgumentException("Invalid dividend sync scope");
        List<DividendData.DividendFetchResult> observations = client.fetchObservations(code, market, years);
        if (observations == null) observations = List.of();
        Instant seenNow = now.get();
        for (var fetched : observations) if (fetched != null && fetched.capturedAt() != null && fetched.capturedAt().isAfter(seenNow))
            throw new IllegalArgumentException("DIVIDEND_CAPTURE_IN_FUTURE");
        int eventCount = 0;
        boolean quotaRelated = false;
        List<SourceStatus> statuses = new ArrayList<>();
        for (var fetched : observations) {
            if (fetched == null) continue;
            Instant observed = fetched.capturedAt() == null ? seenNow : fetched.capturedAt();
            quotaRelated |= fetched.failureKind().quotaRelated();
            var persisted = snapshots.record(code, market, fetched, observed);
            if (persisted != null && persisted.snapshotId() > 0) eventCount += fetched.events().size();
            else log.warn("股利 evidence 未落地：{} {} status={} reason={}", market, code, fetched.status(), fetched.errorReason());
            statuses.add(new SourceStatus(fetched.source(), fetched.status(), fetched.scopeFrom(), fetched.scopeTo(),
                    fetched.events().size(), fetched.dateGroupsComplete(), fetched.failureKind(), fetched.capturedAt(), observed,
                    fetched.errorReason()));
        }
        if (statuses.isEmpty()) log.warn("股利來源未回任何觀測：{} {} years={}", market, code, years);
        StockKey key = new StockKey(code, market);
        String queueStatus = "NOT_QUOTA_RELATED";
        Pending entry;
        synchronized (pendingQuota) {
            if (updateQueue) {
                if (quotaRelated) queueStatus = enqueue(key, years, seenNow);
                else {
                    Pending existing = pendingQuota.get(key);
                    if (existing != null && existing.years() <= years) pendingQuota.remove(key);
                    queueStatus = "QUOTA_RETRY_NOT_NEEDED";
                }
            }
            entry = pendingQuota.get(key);
        }
        // Stopping a quota retry is not financial success: the actual source statuses remain explicit.
        log.info("股利來源同步：{} {} years={} events={} quotaRelated={} queue={} statuses={}",
                market, code, years, eventCount, quotaRelated, queueStatus,
                statuses.stream().map(x -> x.provider() + ":" + x.status() + ":" + x.failureKind()).toList());
        return new SyncResult(eventCount, statuses, quotaRelated, entry != null,
                entry == null ? 0 : entry.years(), queueStatus, client.nextQuotaRetryAt());
    }
    /** Caller holds pendingQuota. Expiration never extends on a repeated authorization. */
    private String enqueue(StockKey key, int years, Instant at) {
        expire(at);
        Pending old = pendingQuota.get(key);
        if (old != null) {
            if (years > old.years()) pendingQuota.put(key, new Pending(years, old.firstQueuedAt(), ++generation));
            return "PENDING_QUOTA";
        }
        if (pendingQuota.size() >= MAX_PENDING) {
            log.error("股利quota補抓佇列已滿：上限={}，拒絕入隊 {} {} years={}，來源缺口仍未修復", MAX_PENDING, key.market(), key.code(), years);
            return "CAPACITY_REJECTED";
        }
        pendingQuota.put(key, new Pending(years, at, ++generation));
        return "PENDING_QUOTA";
    }
    /** Caller holds pendingQuota. Local expiration does not issue HTTP or touch financial tables. */
    private void expire(Instant at) {
        var entries = pendingQuota.entrySet().iterator();
        while (entries.hasNext()) {
            var entry = entries.next();
            if (!at.isBefore(entry.getValue().firstQueuedAt().plus(MAX_PENDING_AGE))) {
                log.error("股利quota補抓逾24小時終止：{} {} years={} firstQueuedAt={}，來源缺口仍未修復",
                        entry.getKey().market(), entry.getKey().code(), entry.getValue().years(), entry.getValue().firstQueuedAt());
                entries.remove();
            }
        }
    }
    @Scheduled(fixedDelay = 60_000)
    public void retryPendingQuota() {
        if (!retryRunning.compareAndSet(false, true)) return;
        try {
            synchronized (pendingQuota) { expire(now.get()); if (pendingQuota.isEmpty()) return; }
            if (!client.quotaRetryReady()) return;
            Set<StockKey> attempted = new HashSet<>();
            for (int count = 0; count < MAX_RETRY_PER_ROUND; count++) {
                StockKey key = null; Pending selected = null;
                synchronized (pendingQuota) {
                    expire(now.get());
                    for (var entry : pendingQuota.entrySet()) if (!attempted.contains(entry.getKey())) {
                        key = entry.getKey(); selected = entry.getValue(); break;
                    }
                }
                if (key == null || !client.quotaRetryReady()) return;
                attempted.add(key);
                SyncResult result;
                try { result = sync(key.code(), key.market(), selected.years(), false); }
                catch (RuntimeException exception) {
                    log.error("股利quota重試轉非quota錯誤，停止自動重試：{} {} years={} failure={}",
                            key.market(), key.code(), selected.years(), exception.getClass().getSimpleName());
                    result = new SyncResult(0, List.of(), false, false, 0, "NON_QUOTA_FAILURE", null);
                }
                if (!result.quotaRelated()) {
                    synchronized (pendingQuota) {
                        Pending current = pendingQuota.get(key);
                        if (current != null && current.generation() == selected.generation()) pendingQuota.remove(key);
                    }
                    log.info("股利quota重試已停止（不代表金融完整）：{} {} years={} statuses={}",
                            key.market(), key.code(), selected.years(), result.sourceStatuses());
                } else if (!client.quotaRetryReady()) return;
            }
        } finally { retryRunning.set(false); }
    }
    int pendingQuotaCount() { synchronized (pendingQuota) { return pendingQuota.size(); } }
}
