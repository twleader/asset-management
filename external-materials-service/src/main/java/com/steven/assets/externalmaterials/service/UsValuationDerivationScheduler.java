package com.steven.assets.externalmaterials.service;

import com.steven.assets.externalmaterials.client.StockFundamentalFetchClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 美股歷史估值序列的每日排程與開機自癒（Requirement 74 / Task 334.4）。
 *
 * <p>一輪固定兩個步驟，<b>順序不可顛倒</b>：</p>
 * <ol>
 *   <li><b>確保 EDGAR 季報歷史足夠</b>——對範圍內每個標的直接呼叫
 *       {@link StockFundamentalFetchClient#fetchSecEdgarFacts(String)} 並 append。</li>
 *   <li><b>逐日推導並落地</b>——{@link UsValuationDerivationService#deriveAll()}。</li>
 * </ol>
 *
 * <h2>步驟一為何必須繞過既有 coverage 短路</h2>
 * <p>{@link StockFundamentalPoller} 只在 {@link FundamentalObservationStore#fallbackNeed} 判定「還缺」時
 * 才呼叫 EDGAR，<b>已覆蓋的標的不會自己去補更早的歷史</b>：EPS／ROE 的 coverage 只看最近 8／4 季是否
 * 連續且新鮮，六檔美股早已滿足，於是 Task 334.2 把 {@code SEC_LOOKBACK_YEARS} 由 3 放寬到 11 之後，
 * 舊季別永遠不會被抓進來（既有列不會回填）。本排程因此<b>不查 coverage、無條件重抓</b>；重抓到的舊季別
 * 由 append-on-change 去重，值相同就不寫。</p>
 *
 * <h2>07:30 這個時點是硬約束</h2>
 * <p>必須晚於 {@link ClosePersister} 在 <b>18:00 ET</b> 的 FinMind 收盤校正（「FinMind 有回值即覆寫 16:02
 * dump 值」）——夏令時＝台北 06:00、<b>冬令時＝台北 07:00</b>。早於它會把未校正的收盤寫進推導序列，而在
 * 「只補缺口」的預設下，超出 30 日重算窗的錯值會永久留存。<b>冬令時只剩 30 分鐘餘裕，不得再往前調。</b>
 * 次要考量是排在 business-services {@code IndexDailyRefreshScheduler} 的 07:00 之後（不同服務、不同外部
 * 主機，只是次要考量、不是理由本身）。</p>
 *
 * <h2>缺口／重算／整段重寫的分工</h2>
 * <p>這三件事全部在 {@link UsValuationDerivationService#deriveForCode(String)} 內：預設只補「尚無
 * {@code SEC_DERIVED} 列」的交易日（首次啟動即完成整段歷史回補、之後每日只增補新的一天、中斷後重跑只
 * 處理缺口），但一律重算最近
 * {@value UsValuationDerivationService#RECOMPUTE_TRADING_DAYS} 個交易日（收盤價會被事後校正），
 * 且可用區段起點後移時整段刪除重寫（未來的股票分割會讓已寫入的列變成舊基準）。本類別只負責觸發與節流。</p>
 *
 * <h2>執行緒與 fail-soft</h2>
 * <p>兩個觸發點都把工作丟到 virtual thread（比照 {@code HistoricalBackfillService.dailyLookthroughBackfill}）：
 * 本服務沒有設定 {@code spring.task.scheduling.pool.size}，預設<b>只有一條</b>排程執行緒，首次整段回補
 * 動輒數千個交易日，直接跑在排程執行緒上會餓死同服務的 2 秒匯率 tick。{@link AtomicBoolean} 保證開機
 * 自癒與每日排程不會同時跑同一輪。單一標的失敗只記 WARN 並繼續下一檔，例外不擲出執行緒外。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UsValuationDerivationScheduler {

    /** SEC 要求具名 User-Agent（{@code SEC_USER_AGENT} 已符合）；標的之間的節流間隔比照既有慣例。 */
    static final long EDGAR_THROTTLE_MILLIS = 500L;
    /** 開機自癒的延遲，比照 {@code IndexDailyRefreshScheduler.selfHealStaleOnStartup()} 等依賴就緒。 */
    static final long STARTUP_DELAY_MILLIS = 30_000L;

    private final StockFundamentalFetchClient client;
    private final FundamentalObservationStore store;
    private final UsValuationDerivationService derivation;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 一輪的結果；{@code derivation} 為 null 代表該輪被另一輪佔用而略過。 */
    public record Result(
            String trigger,
            int edgarTargets,
            int edgarWritten,
            int edgarFailures,
            UsValuationDerivationService.Summary derivation) {}

    @Scheduled(cron = "0 30 7 * * TUE-SAT", zone = "Asia/Taipei")
    public void dailyDerivation() {
        startAsync("scheduled", 0L);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void selfHealOnStartup() {
        startAsync("startup", STARTUP_DELAY_MILLIS);
    }

    private void startAsync(String trigger, long delayMillis) {
        Thread.ofVirtual().name("us-valuation-derivation-" + trigger).start(() -> {
            try {
                if (delayMillis > 0) Thread.sleep(delayMillis);
                runGuarded(trigger);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("美股估值推導（{}）失敗：{}", trigger, e.getMessage());
            }
        });
    }

    /** 每日排程與開機自癒共用的本體；同一時間只允許一輪。 */
    Result runGuarded(String trigger) {
        if (!running.compareAndSet(false, true)) {
            log.info("美股估值推導（{}）略過：另一輪仍在執行", trigger);
            return new Result(trigger, 0, 0, 0, null);
        }
        try {
            return run(trigger);
        } finally {
            running.set(false);
        }
    }

    private Result run(String trigger) {
        List<String> codes = store.secEdgarUsStockCodes();
        int written = 0;
        int failures = 0;
        boolean first = true;
        for (String code : codes) {
            if (!first && !throttle()) break;
            first = false;
            try {
                written += store.append(client.fetchSecEdgarFacts(code)).financials();
            } catch (Exception e) {
                failures++;
                log.warn("SEC EDGAR 季報重抓失敗 {}：{}", code, e.toString());
            }
        }
        log.info("SEC EDGAR 季報重抓完成（{}）：{} 檔、新增 {} 列、失敗 {} 檔",
                trigger, codes.size(), written, failures);
        // 步驟二自己會記「處理 N 檔、新增 M 列、刪除重寫 X 列、略過 K 檔（含原因）」的 INFO 收尾行。
        return new Result(trigger, codes.size(), written, failures, derivation.deriveAll());
    }

    /** @return {@code false} 代表執行緒被中斷、本輪應提早收手（中斷旗標已還原）。 */
    private boolean throttle() {
        try {
            Thread.sleep(EDGAR_THROTTLE_MILLIS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
