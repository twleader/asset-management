package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.StockFundamentalFetchClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 個股基本面每日抓取（Requirement 46 / Task 292；美股輪次見 Task 293）。
 *
 * <p>每分鐘節拍只讀 {@code crawler_schedule[crawler_key=fundamental]}；命中後在背景執行，
 * warmup、排程、手動端點共用 {@link #run(String)}。先寫台股官方整批來源，再只對使用者持股／觀察個股
 * 依 Yahoo → 玩股網 → FinMind 補足形成因子所需的歷史；同一輪次跑完後接著跑美股輪次，依
 * SEC EDGAR → Yahoo 補 EPS／ROE／PE。雷達頁面請求不會觸發本 poller。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockFundamentalPoller {

    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");
    private static final String CRAWLER_KEY = "fundamental";
    private static final int DEFAULT_HOUR = 15;
    private static final int DEFAULT_MINUTE = 30;
    private static final String TW_MARKET = "台股";
    private static final String US_MARKET = "美股";

    private final StockFundamentalFetchClient client;
    private final FundamentalObservationStore store;
    private final StockSourceQuery stockSource;
    private final CrawlerScheduleQuery scheduleQuery;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public record RefreshSummary(
            String status,
            String trigger,
            int targetStocks,
            int fallbackStocks,
            int valuationsWritten,
            int financialsWritten,
            int revenuesWritten,
            int industriesWritten,
            int unresolvedStocks,
            int sourceAttempts,
            int sourceSuccesses,
            int failures,
            String finishedAt) {}

    @EventListener(ApplicationReadyEvent.class)
    public void warmup() {
        new Thread(() -> {
            try {
                Thread.sleep(7000);
                if (!store.hasObservedToday(LocalDate.now(TAIPEI))) runGuarded("warmup");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("基本面 warmup 失敗：{}", e.getMessage());
            }
        }, "fundamental-warmup").start();
    }

    @Scheduled(cron = "0 * * * * *", zone = "Asia/Taipei")
    public void tick() {
        LocalTime now = LocalTime.now(TAIPEI);
        if (!matchesConfiguredTime(now.getHour(), now.getMinute())) return;
        new Thread(() -> runGuarded("scheduled"), "fundamental-scheduled").start();
    }

    public RefreshSummary refreshNow() {
        if (!running.compareAndSet(false, true)) return busy("manual");
        try { return run("manual"); }
        finally { running.set(false); }
    }

    private RefreshSummary runGuarded(String trigger) {
        if (!running.compareAndSet(false, true)) return busy(trigger);
        try { return run(trigger); }
        finally { running.set(false); }
    }

    private RefreshSummary run(String trigger) {
        Instant observedAt = Instant.now();
        Set<String> targetCodes = new LinkedHashSet<>();
        stockSource.collectTwRadarCodes(targetCodes);
        targetCodes.removeIf(code -> store.isEtf(code, TW_MARKET));

        int failures = 0;
        int fallbackStocks = 0;
        int unresolvedStocks = 0;
        SourceHealth sourceHealth = new SourceHealth();
        FundamentalObservationStore.WriteCount total = new FundamentalObservationStore.WriteCount(0, 0, 0, 0);

        StockFundamentalFetchClient.Bundle official;
        try {
            official = client.fetchOfficial();
            sourceHealth.add(official);
        } catch (Exception e) {
            official = StockFundamentalFetchClient.Bundle.EMPTY;
            sourceHealth.fail("EXCHANGE:uncaught:" + message(e));
            log.warn("官方基本面抓取失敗：{}", e.getMessage());
        }
        StockFundamentalFetchClient.Bundle targetOfficial = filter(official, targetCodes);
        try {
            total = total.plus(store.append(targetOfficial));
        } catch (Exception e) {
            failures++;
            log.warn("官方基本面 observation 寫入失敗：{}", e.getMessage());
        }

        try {
            int industries = appendIndustryAggregates(official.revenues());
            total = total.plus(new FundamentalObservationStore.WriteCount(0, 0, 0, industries));
        } catch (Exception e) {
            failures++;
            log.warn("產業營收彙總寫入失敗：{}", e.getMessage());
        }

        for (String code : targetCodes) {
            try {
                FundamentalObservationStore.FallbackNeed need = currentNeed(code, TW_MARKET);
                if (!need.any()) continue;
                fallbackStocks++;

                // 固定順位：Yahoo → WantGoo → FinMind；每一段落庫後都依四個因子重新判斷。
                // Yahoo 只有估值能力，EPS／ROE／月營收已完整時不得為它們發出無效請求。
                if (need.valuation()) {
                    StockFundamentalFetchClient.Bundle yahoo = client.fetchYahoo(code, TW_MARKET, observedAt);
                    sourceHealth.add(yahoo);
                    total = total.plus(store.append(yahoo));
                    need = currentNeed(code, TW_MARKET);
                }
                if (need.any()) {
                    StockFundamentalFetchClient.Bundle wantGoo = client.fetchWantGoo(code, observedAt);
                    sourceHealth.add(wantGoo);
                    total = total.plus(store.append(wantGoo));
                    need = currentNeed(code, TW_MARKET);
                }
                if (need.any()) {
                    StockFundamentalFetchClient.Bundle finMind = client.fetchFinMind(code, observedAt);
                    sourceHealth.add(finMind);
                    total = total.plus(store.append(finMind));
                    need = currentNeed(code, TW_MARKET);
                }
                if (need.any()) unresolvedStocks++;
            } catch (Exception e) {
                failures++;
                unresolvedStocks++;
                log.warn("基本面 fallback {} 失敗：{}", code, e.getMessage());
            }
        }

        // ---- 美股輪次（Task 293）：與台股共用同一次觸發，抓取邏輯完全分開，互不拖垮對方 ----
        Set<String> usCodes = new LinkedHashSet<>();
        try {
            Set<String> heldTw = new LinkedHashSet<>();
            Set<String> heldUk = new LinkedHashSet<>();
            // 只取 usCodes；twCodes／ukCodes 刻意丟棄不用——台股輪次沿用上方既有 collectTwRadarCodes，
            // 兩者口徑歷史上刻意不同，不得混用（見 StockSourceQuery 既有註解）。
            stockSource.collectHeldStockCodes(heldTw, usCodes, heldUk);
            usCodes.removeIf(code -> store.isEtf(code, US_MARKET));

            for (String code : usCodes) {
                try {
                    FundamentalObservationStore.FallbackNeed need = currentNeed(code, US_MARKET);
                    // 美股不產生月營收列，revenue 恆需要 fallback、不得納入本輪的完成度判斷。
                    if (!need.eps() && !need.roe() && !need.valuation()) continue;
                    fallbackStocks++;

                    if (need.eps() || need.roe()) {
                        StockFundamentalFetchClient.Bundle secEdgar = client.fetchSecEdgarFacts(code);
                        sourceHealth.add(secEdgar);
                        total = total.plus(store.append(secEdgar));
                        need = currentNeed(code, US_MARKET);
                    }
                    if (need.valuation()) {
                        StockFundamentalFetchClient.Bundle yahoo = client.fetchYahoo(code, US_MARKET, observedAt);
                        sourceHealth.add(yahoo);
                        total = total.plus(store.append(yahoo));
                        need = currentNeed(code, US_MARKET);
                    }
                    if (need.eps() || need.roe() || need.valuation()) unresolvedStocks++;
                } catch (Exception e) {
                    failures++;
                    unresolvedStocks++;
                    log.warn("美股基本面 fallback {} 失敗：{}", code, e.getMessage());
                }
            }
        } catch (Exception e) {
            failures++;
            log.warn("美股基本面輪次整體失敗：{}", e.getMessage());
        }

        failures += sourceHealth.failures.size();
        String status = sourceHealth.attempts > 0 && sourceHealth.successes == 0 ? "FAILED"
                : failures > 0 || unresolvedStocks > 0 ? "PARTIAL" : "OK";
        RefreshSummary summary = new RefreshSummary(status, trigger, targetCodes.size() + usCodes.size(),
                fallbackStocks, total.valuations(), total.financials(), total.revenues(), total.industries(),
                unresolvedStocks, sourceHealth.attempts, sourceHealth.successes, failures,
                Instant.now().toString());
        log.info("基本面抓取完成：{}", summary);
        return summary;
    }

    private int appendIndustryAggregates(List<StockFundamentalFetchClient.Revenue> rows) {
        Map<IndustryKey, IndustryAggregate> grouped = new HashMap<>();
        for (StockFundamentalFetchClient.Revenue row : rows == null ? List.<StockFundamentalFetchClient.Revenue>of() : rows) {
            if (!StockFundamentalFetchClient.EXCHANGE.equals(row.provider())
                    || row.industryName() == null || row.industryName().isBlank()
                    || row.revenue() == null || row.priorYearRevenue() == null
                    || row.revenue() < 0 || row.priorYearRevenue() <= 0) continue;
            IndustryKey key = new IndustryKey(row.industryName(), row.revenueYear(), row.revenueMonth());
            grouped.computeIfAbsent(key, ignored -> new IndustryAggregate()).add(row);
        }
        int written = 0;
        for (Map.Entry<IndustryKey, IndustryAggregate> entry : grouped.entrySet()) {
            IndustryAggregate aggregate = entry.getValue();
            if (aggregate.companyCount < 3 || aggregate.prior.signum() <= 0) continue;
            BigDecimal yoy = aggregate.revenue.subtract(aggregate.prior)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(aggregate.prior, 4, RoundingMode.HALF_UP);
            IndustryKey key = entry.getKey();
            written += store.appendIndustry(key.industry, key.year, key.month,
                    aggregate.revenue, aggregate.prior, yoy, aggregate.companyCount,
                    StockFundamentalFetchClient.EXCHANGE, List.copyOf(aggregate.urls),
                    aggregate.availableAt, "PUBLISHED");
        }
        return written;
    }

    private StockFundamentalFetchClient.Bundle filter(
            StockFundamentalFetchClient.Bundle bundle, Set<String> codes) {
        return new StockFundamentalFetchClient.Bundle(
                bundle.valuations().stream().filter(v -> codes.contains(v.stockCode())).toList(),
                bundle.financials().stream().filter(v -> codes.contains(v.stockCode())).toList(),
                bundle.revenues().stream().filter(v -> codes.contains(v.stockCode())).toList());
    }

    private boolean matchesConfiguredTime(int hour, int minute) {
        try {
            List<int[]> times = scheduleQuery.enabledTimes(CRAWLER_KEY);
            for (int[] time : times) if (time[0] == hour && time[1] == minute) return true;
            return false;
        } catch (Exception e) {
            log.warn("讀基本面排程失敗，以預設 15:30 判斷：{}", e.getMessage());
            return hour == DEFAULT_HOUR && minute == DEFAULT_MINUTE;
        }
    }

    /** append 使用資料庫 now() 記 observed_at；重算必須晚於該次 INSERT，才看得到本輪剛寫入的 revision。 */
    private FundamentalObservationStore.FallbackNeed currentNeed(String code, String market) {
        return store.fallbackNeed(code, market, Instant.now());
    }

    private RefreshSummary busy(String trigger) {
        return new RefreshSummary("BUSY", trigger, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, Instant.now().toString());
    }

    private static String message(Exception e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    private static final class SourceHealth {
        private int attempts;
        private int successes;
        private final List<String> failures = new java.util.ArrayList<>();

        private void add(StockFundamentalFetchClient.Bundle bundle) {
            if (bundle == null) {
                fail("UNKNOWN:null bundle");
                return;
            }
            attempts += bundle.attempts();
            successes += bundle.successes();
            failures.addAll(bundle.failures() == null ? List.of() : bundle.failures());
        }

        private void fail(String failure) {
            attempts++;
            failures.add(failure);
        }
    }

    private record IndustryKey(String industry, int year, int month) {}

    private static final class IndustryAggregate {
        private BigDecimal revenue = BigDecimal.ZERO;
        private BigDecimal prior = BigDecimal.ZERO;
        private int companyCount;
        private Instant availableAt = Instant.EPOCH;
        private final Set<String> urls = new LinkedHashSet<>();

        private void add(StockFundamentalFetchClient.Revenue row) {
            revenue = revenue.add(BigDecimal.valueOf(row.revenue()));
            prior = prior.add(BigDecimal.valueOf(row.priorYearRevenue()));
            companyCount++;
            urls.addAll(row.sourceUrls());
            if (row.sourceAvailableAt().isAfter(availableAt)) availableAt = row.sourceAvailableAt();
        }
    }
}
