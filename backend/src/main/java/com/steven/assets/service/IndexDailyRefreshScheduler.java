package com.steven.assets.service;

import com.steven.assets.repository.UsIndexDailyHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 海外指數日線（{@code us_index_daily_history}）自動回補排程（Task 105）。
 *
 * <p>背景：Task 102 的「當日走勢昨收 / 漲跌%」與 Task 95/96 的日線圖 + MA20/60/240 都讀
 * {@code us_index_daily_history}，但該表原本只靠前端「回補日線（10 年）」按鈕手動觸發、**無排程**。
 * 久未點擊的指數日線會停在舊日期；當日走勢的點位是即時抓 Yahoo（最新交易日），昨收卻退回數日前的
 * 舊收盤，導致漲跌% 爆量失真（實機：SOX 走勢 ~14,041 卻拿 5 日前 12,330 當昨收 → +13.88%）。
 * 台股大盤日線由 external-materials 的 {@code TwseIndexPoller} 顧著、故一直最新；海外 8 指數缺對應排程
 * ——本類補上，讓昨收恆為「真正的前一交易日」。
 *
 * <p>設計：重用 {@link MacroHistoryService#refreshUsIndexDaily(String)}（即手動按鈕同一條路徑，Yahoo
 * {@code range=10y} 一次抓整段、idempotent upsert），對 {@link MacroHistoryService#OVERSEAS_INDEX_CODES}
 * 逐一回補。
 * <ul>
 *   <li>每日排程：美股收盤後（隔日 07:00 Asia/Taipei，TUE-SAT）。美股 16:00 ET ≈ 隔日 04~05:00 台北，
 *       07:00 留足 Yahoo daily bar 發佈緩衝；此時 8 市場（亞 / 歐 / 美）最新交易日皆已收。
 *       回補完成後<b>驗收一次</b>（Task 332.4）：美股指數若仍落後即留 WARN。</li>
 *   <li>落後補救檢查（Task 332.5）：09:00 與 12:00（Asia/Taipei，TUE-SAT），涵蓋「07:00 時來源尚未發布
 *       當日 bar」的情形；<b>只有確實落後才回補</b>，追上就不對外部來源發任何請求。</li>
 *   <li>開機 self-heal：任一指數過時即補一次，處理「服務於排程時點未運行（restart / crash）」場景，
 *       比照 {@code ClosePersister.selfHealMissedClose}。</li>
 * </ul>
 *
 * <p><b>新鮮度判準與交易雷達同源（Requirement 75 / Task 332）。</b>原先自癒用的是
 * 「落後超過 {@link #STALE_DAYS} 個日曆天才算過時」，而 {@code TradingRadarService} 判美股大盤 stale 的
 * 條件是「落後一盤即 stale」；「落後 1～3 盤」這段區間裡自癒認為正常、雷達認為不正常，於是出現
 * 「IXIC 缺前一盤、自癒卻輸出『皆為最新，略過』」的實測事故。現在美股指數改呼叫
 * {@link MarketDataService#mostRecentCompletedUsTradingDay(Instant)}——與雷達同一支方法。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndexDailyRefreshScheduler {

    /** 非美股指數的日曆天容忍：最新日線超過此天數即視為過時（容忍週末 + 1 個假日）。 */
    private static final int STALE_DAYS = 4;

    /**
     * <b>逐盤判準</b>適用的美股指數：{@link MacroHistoryService#OVERSEAS_INDEX_CODES} 中的美股四大
     * （道瓊 / 標普500 / 那斯達克綜合 / 費城半導體）＋ {@link MacroHistoryService#TOTAL_RETURN_US_INDEX_CODES}
     * 的含息報酬指數 SP500TR。這些代碼一律以
     * {@code latestTradingDate < mostRecentCompletedUsTradingDay(now)} 判過時（查無列亦為過時）。
     */
    static final List<String> US_INDEX_CODES = List.of("DJI", "SPX", "IXIC", "SOX", "SP500TR");

    /**
     * <b>維持 {@link #STALE_DAYS} 日曆天容忍</b>的非美股指數：英國富時100 / 德國DAX / 韓國KOSPI / 日經225。
     *
     * <p><b>判準為什麼要分岔（不要「統一一下比較乾淨」）：</b>交易雷達只讀 IXIC。本專案的交易日曆涵蓋
     * <b>台、美、英三個市場</b>——{@link MarketDataService#isTradingDay(String, LocalDate)} 對
     * {@code "美股"} 走 {@code isUsTradingDay}、{@code "英股"} 走 {@code isUkTradingDay}、其餘一律落到
     * {@code isTwTradingDay}。故：
     * <ul>
     *   <li>{@code N225}／{@code KOSPI}／{@code DAX} <b>沒有對應日曆</b>（會被當成台股日曆判斷），
     *       套用逐盤判準會因當地假日恆判過時、每次啟動都全量回補。</li>
     *   <li>{@code FTSE} <b>雖有英股日曆可用</b>（{@code getUkHolidays} 是完整維護的 LSE／英國銀行假日表、
     *       含順移邏輯），但雷達不讀它、且其發布時點與美股不同，Task 332 一併沿用 4 日容忍、不擴大改動面。</li>
     * </ul>
     * 兩份清單刻意都寫成正面列舉（而非「不在美股清單裡就算非美股」的反向判斷），
     * 新增指數時才能一眼看出它屬於哪一類；漏分類會由下方 static 區塊在啟動時直接擋下。
     */
    static final List<String> NON_US_INDEX_CODES = List.of("FTSE", "DAX", "KOSPI", "N225");

    static {
        Set<String> classified = new LinkedHashSet<>(US_INDEX_CODES);
        classified.addAll(NON_US_INDEX_CODES);
        Set<String> managed = new LinkedHashSet<>(MacroHistoryService.OVERSEAS_INDEX_CODES);
        managed.addAll(MacroHistoryService.TOTAL_RETURN_US_INDEX_CODES);
        if (!classified.equals(managed)) {
            throw new IllegalStateException(
                    "海外指數新鮮度判準未分類：US_INDEX_CODES ∪ NON_US_INDEX_CODES 必須恰等於 "
                            + "OVERSEAS_INDEX_CODES ∪ TOTAL_RETURN_US_INDEX_CODES，實際 "
                            + classified + " vs " + managed);
        }
    }

    private final MacroHistoryService macroHistoryService;
    private final UsIndexDailyHistoryRepository usDailyRepo;
    private final MarketDataService marketDataService;

    /** 海外指數收盤後回補：每日 07:00 Asia/Taipei（TUE-SAT，涵蓋週一~週五各市場交易日）。 */
    @Scheduled(cron = "0 0 7 * * TUE-SAT", zone = "Asia/Taipei")
    public void scheduledRefreshAll() {
        log.info("排程：回補海外指數日線（{} 檔）", MacroHistoryService.OVERSEAS_INDEX_CODES.size());
        refreshAll("scheduled");
        warnIfUsIndexStillStale("scheduled", Instant.now());
    }

    /**
     * 落後補救檢查（Task 332.5）：09:00 與 12:00 Asia/Taipei（TUE-SAT），涵蓋「07:00 全量回補時
     * Yahoo 尚未發布當日 daily bar」的情形。
     *
     * <p>只有美股指數確實落後才回補，且<b>只補落後的那幾檔</b>；已追上就直接返回、不對外部來源發任何請求，
     * 也不印 INFO（每天兩次的空轉不該洗版）。</p>
     */
    @Scheduled(cron = "0 0 9,12 * * TUE-SAT", zone = "Asia/Taipei")
    public void scheduledUsIndexGapCheck() {
        runUsIndexGapCheck(Instant.now());
    }

    /** {@link #scheduledUsIndexGapCheck()} 的本體，決策時刻由外部給定（可測）。 */
    void runUsIndexGapCheck(Instant now) {
        List<StaleIndex> stale = staleUsIndexes(now);
        LocalDate expected = marketDataService.mostRecentCompletedUsTradingDay(now);
        if (stale.isEmpty()) {
            log.debug("gap-check：美股指數日線皆已追上 {}，略過", expected);
            return;
        }
        for (StaleIndex s : stale) {
            log.info("gap-check：{} 最新交易日 {}，應為 {} → 啟動回補",
                    s.code(), s.describeLatest(), expected);
        }
        refreshCodes(stale.stream().map(StaleIndex::code).toList(), "gap-check");
    }

    /** 開機自我修復：任一指數日線過時就補一次（服務於排程時點未運行 / 久未手動回補）。 */
    @EventListener(ApplicationReadyEvent.class)
    public void selfHealStaleOnStartup() {
        new Thread(() -> {
            try {
                Thread.sleep(30_000);   // 等 external-materials-service 等依賴就緒
                runSelfHeal(Instant.now(), LocalDate.now());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                log.warn("海外指數日線 self-heal 失敗: {}", e.getMessage());
            }
        }, "index-daily-self-heal").start();
    }

    /**
     * {@link #selfHealStaleOnStartup()} 的本體，決策時刻由外部給定（可測）。
     *
     * <p>美股指數用逐盤判準（與雷達同源）、非美股指數用 {@link #STALE_DAYS} 日曆天容忍；
     * 回補只針對判定為落後的那幾檔（理由見 {@link #refreshCodes(List, String)}）。</p>
     */
    void runSelfHeal(Instant now, LocalDate today) {
        List<StaleIndex> staleUs = staleUsIndexes(now);
        List<StaleIndex> staleNonUs = staleNonUsIndexes(today);
        if (staleUs.isEmpty() && staleNonUs.isEmpty()) {
            log.info("self-heal：海外指數日線皆為最新，略過");
            return;
        }
        LocalDate expected = marketDataService.mostRecentCompletedUsTradingDay(now);
        for (StaleIndex s : staleUs) {
            log.info("self-heal：{} 最新交易日 {}，應為 {}", s.code(), s.describeLatest(), expected);
        }
        for (StaleIndex s : staleNonUs) {
            log.info("self-heal：{} 最新交易日 {}，已過時（> {} 日）",
                    s.code(), s.describeLatest(), STALE_DAYS);
        }
        List<String> codes = new ArrayList<>(staleUs.stream().map(StaleIndex::code).toList());
        codes.addAll(staleNonUs.stream().map(StaleIndex::code).toList());
        log.info("self-heal：偵測到海外指數日線落後 {} 檔，啟動回補", codes.size());
        refreshCodes(codes, "self-heal");
    }

    /** 一檔落後的指數：{@code latest} 為它目前的最新交易日，{@code null} 代表查無任何列。 */
    record StaleIndex(String code, LocalDate latest) {
        String describeLatest() {
            return latest == null ? "無資料" : latest.toString();
        }
    }

    /**
     * 落後的美股指數（逐盤判準，與交易雷達同源）：最新日線早於「最近一個已完成美股交易日」，或查無資料。
     * 回傳順序沿用 {@link #US_INDEX_CODES}。
     */
    List<StaleIndex> staleUsIndexes(Instant now) {
        LocalDate expected = marketDataService.mostRecentCompletedUsTradingDay(now);
        return collectStale(US_INDEX_CODES, expected);
    }

    /**
     * 過時的非美股指數（維持 {@link #STALE_DAYS} 日曆天容忍，理由見 {@link #NON_US_INDEX_CODES}）。
     *
     * @param today 判斷基準日（正式路徑傳 {@code LocalDate.now()}）
     */
    List<StaleIndex> staleNonUsIndexes(LocalDate today) {
        return collectStale(NON_US_INDEX_CODES, today.minusDays(STALE_DAYS));
    }

    /** 最新交易日早於 {@code expected}（或查無列）即列為落後。 */
    private List<StaleIndex> collectStale(List<String> codes, LocalDate expected) {
        List<StaleIndex> stale = new ArrayList<>();
        for (String code : codes) {
            LocalDate latest = latestTradingDate(code).orElse(null);
            if (latest == null || latest.isBefore(expected)) stale.add(new StaleIndex(code, latest));
        }
        return stale;
    }

    /**
     * 回補後驗收（Task 332.4）：美股指數若仍落後，逐檔以 WARN 記錄「哪一個指數／目前最新／應該要有哪一天」。
     * 已追上時不記錄，維持既有 INFO 收尾行即可、不新增噪音。
     *
     * <p>原始事故正是「回補宣稱成功、資料其實沒進來、日誌看不出異常」——本方法就是為了讓下次查得到。</p>
     */
    void warnIfUsIndexStillStale(String tag, Instant now) {
        LocalDate expected = marketDataService.mostRecentCompletedUsTradingDay(now);
        for (StaleIndex stale : staleUsIndexes(now)) {
            log.warn("回補後美股指數日線仍落後 [{}]：{} 最新交易日 {}，應為 {}",
                    tag, stale.code(), stale.describeLatest(), expected);
        }
    }

    private void refreshAll(String tag) {
        int ok = 0;
        int total = MacroHistoryService.OVERSEAS_INDEX_CODES.size();
        for (String code : MacroHistoryService.OVERSEAS_INDEX_CODES) {
            try {
                macroHistoryService.refreshUsIndexDaily(code);
                ok++;
                Thread.sleep(500);   // Yahoo 禮貌間隔
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("回補海外指數日線 {} 失敗 [{}]: {}", code, tag, e.getMessage());
            }
        }
        log.info("海外指數日線回補完成 [{}]：成功 {}/{} 檔", tag, ok, total);
        if (Thread.currentThread().isInterrupted()) return;   // 已被中斷（服務關閉中）就不再續做 TR

        // 含息報酬指數（績效比較頁 Requirement 33）：SP500TR 走 Yahoo ^SP500TR 日線回補
        for (String code : MacroHistoryService.TOTAL_RETURN_US_INDEX_CODES) {
            try {
                macroHistoryService.refreshUsIndexDaily(code);
                Thread.sleep(500);   // Yahoo 禮貌間隔
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("回補含息報酬指數日線 {} 失敗 [{}]: {}", code, tag, e.getMessage());
            }
        }

        // TWSE 報酬指數增量：補近 14 天內仍為 null 的 close_point_tr。
        // 不可只補「今日」——排程 07:00 時今日交易列尚未寫入（poller 盤後才寫），findById 必落空、增量形同無效；
        // 改補最近數日缺口，涵蓋前一交易日、週末順延與回補時的單日 miss。
        try {
            int filled = macroHistoryService.fillRecentTwseReturnIndexGaps(14);
            if (filled > 0) log.info("TWSE 報酬指數增量 [{}]：處理近 14 日 {} 個缺口", tag, filled);
        } catch (Exception e) {
            log.warn("TWSE 報酬指數增量失敗 [{}]: {}", tag, e.getMessage());
        }
    }

    /**
     * 只回補指定代碼（自癒與落後補救檢查專用，Task 332.5），與 {@link #refreshAll(String)} 刻意分開：
     * <ul>
     *   <li>判準由 4 日容忍收緊為逐盤後，觸發頻率明顯上升（凌晨重啟、來源延遲發布時的兩次補救檢查都可能觸發）。
     *       若沿用全量回補，等於每次都對 Yahoo 打 9 次 {@code range=10y} 請求——<b>不得放大外部請求量</b>。</li>
     *   <li>含息報酬指數與 TWSE 報酬指數增量兩個收尾步驟只屬每日全量回補，落後補救不重複跑
     *       （SP500TR 若自己落後，會以一般代碼身分出現在 {@code codes} 裡）。</li>
     * </ul>
     * 指數間的 {@code Thread.sleep(500)} 禮貌間隔與 {@code refreshAll} 一致。
     */
    private void refreshCodes(List<String> codes, String tag) {
        int ok = 0;
        for (String code : codes) {
            try {
                macroHistoryService.refreshUsIndexDaily(code);
                ok++;
                Thread.sleep(500);   // Yahoo 禮貌間隔
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("回補海外指數日線 {} 失敗 [{}]: {}", code, tag, e.getMessage());
            }
        }
        log.info("海外指數日線回補完成 [{}]：成功 {}/{} 檔（{}）", tag, ok, codes.size(), codes);
    }

    private Optional<LocalDate> latestTradingDate(String code) {
        return usDailyRepo.findTopByIndexCodeOrderByTradingDateDesc(code)
                .map(com.steven.assets.model.UsIndexDailyHistory::getTradingDate);
    }
}
