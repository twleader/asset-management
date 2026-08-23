package com.steven.assets.service;

import com.steven.assets.model.StockDividendHistory;
import com.steven.assets.repository.StockDividendHistoryRepository;
import com.steven.assets.service.DividendBackfillTargetRepository.Target;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 配息四日期的<b>一次性歷史回補</b>（Requirement 94 / Task 357.3a、357.3b、357.3c）。
 *
 * <p><b>回補必須是兩段，只做前半段 {@code stock_dividend_history} 不會更新。</b></p>
 * <ol>
 *   <li><b>ext 端重抓 snapshot</b>：{@link DividendResyncClient#resyncOne} →
 *       external-materials-service 的 {@code POST /internal/dividend/sync} →
 *       {@code DividendPersister.syncOne} → {@code stock_dividend_snapshot_event}。</li>
 *   <li><b>backend 端投影到 current state</b>：
 *       {@link DividendCurrentStateProjectionService#projectOne} → {@code stock_dividend_history}。
 *       {@code DividendPersister} 的 Javadoc 自己寫明它「不維護 current-state」，而投影平常
 *       <b>只在 {@code DividendHistoryService.findFromDb} 被讀到時才觸發</b>——不主動觸發，
 *       回補就碰不到 357.3b 要修的那 24 列。</li>
 * </ol>
 *
 * <p><b>順序不可對調</b>：先投影再重抓，投影看到的是舊 snapshot，等於什麼都沒做。
 * 這一點由 {@code DividendBackfillServiceTest} 的 {@code InOrder} 斷言釘住。</p>
 *
 * <h2>與 357.8a 的關係</h2>
 * <p>本 service <b>不新增排程、不新增對外端點、不新增 9090 路由、不新增第二條抓取路徑</b>：
 * 它沒有 {@code @Scheduled}、沒有 controller，唯一的觸發點是
 * {@link DividendBackfillStarter}（{@code app.dividend-backfill.enabled} 預設 {@code false}），
 * 而對 FinMind 的 IO 仍然只發生在 external-materials-service。</p>
 *
 * <h2>357.3b 的修正清單怎麼產生</h2>
 * <p>每一檔在<b>重抓之前</b>與<b>投影之後</b>各讀一次 current-state，比對出
 * 「同一年度、同一組金額的事件，日期由 {@code ex_dividend_date} 移到 {@code ex_rights_date}」
 * 的列——那就是「舊資料的除息日其實是除權日」。逐筆寫進 ledger 的 {@code CORRECTION} 行
 * 與 INFO 日誌，並彙總進 {@link Report#corrections()}。</p>
 */
@Slf4j
@Service
public class DividendBackfillService {

    /** 一筆被 357.3b 修正的事件：日期從除息日欄移到了除權日欄。 */
    public record Correction(
            String code,
            String market,
            Integer year,
            LocalDate movedDate,
            BigDecimal cashDividend,
            BigDecimal stockDividend) {}

    /** 一檔回補失敗。<b>不會被標記為已完成</b>，下一次執行會自動續跑。 */
    public record Failure(String code, String market, String reason) {}

    /** 一次回補執行的結果。 */
    public record Report(
            int totalTargets,
            int alreadyDone,
            int attempted,
            int batchCount,
            List<String> succeeded,
            List<Failure> failures,
            List<Correction> corrections) {
        public Report {
            succeeded = succeeded == null ? List.of() : List.copyOf(succeeded);
            failures = failures == null ? List.of() : List.copyOf(failures);
            corrections = corrections == null ? List.of() : List.copyOf(corrections);
        }
    }

    private final DividendBackfillTargetRepository targets;
    private final DividendResyncClient resyncClient;
    private final DividendCurrentStateProjectionService projectionService;
    private final StockDividendHistoryRepository historyRepository;
    private final DividendBackfillLedger ledger;
    private final int batchSize;
    private final long batchPauseMillis;
    private final int sinceYear;

    public DividendBackfillService(
            DividendBackfillTargetRepository targets,
            DividendResyncClient resyncClient,
            DividendCurrentStateProjectionService projectionService,
            StockDividendHistoryRepository historyRepository,
            DividendBackfillLedger ledger,
            @Value("${app.dividend-backfill.batch-size:10}") int batchSize,
            @Value("${app.dividend-backfill.batch-pause-millis:5000}") long batchPauseMillis,
            @Value("${app.dividend-backfill.since-year:2000}") int sinceYear) {
        this.targets = targets;
        this.resyncClient = resyncClient;
        this.projectionService = projectionService;
        this.historyRepository = historyRepository;
        this.ledger = ledger;
        this.batchSize = batchSize;
        this.batchPauseMillis = batchPauseMillis;
        this.sinceYear = sinceYear;
    }

    /**
     * 跑一輪回補。已完成的標的（ledger 裡有 key）一律跳過；失敗的標的記進清單但<b>不中斷</b>
     * 其餘標的，也<b>不</b>標記為已完成，故下一次執行會自動續跑。
     */
    public Report run() {
        List<Target> all = targets.findBackfillTargets();
        Set<String> done = ledger.completedKeys();
        List<Target> pending = new ArrayList<>();
        for (Target target : all) {
            if (!done.contains(target.key())) pending.add(target);
        }
        List<List<Target>> batches = partition(pending, batchSize);
        log.info("配息四日期回補開始：目標 {} 檔、已完成 {} 檔、本次待跑 {} 檔、批次大小 {}、共 {} 批",
                all.size(), all.size() - pending.size(), pending.size(), batchSize, batches.size());
        safeAppend("START\ttargets=" + all.size() + "\talreadyDone=" + (all.size() - pending.size())
                + "\tpending=" + pending.size() + "\tbatchSize=" + batchSize
                + "\tbatches=" + batches.size());

        List<String> succeeded = new ArrayList<>();
        List<Failure> failures = new ArrayList<>();
        List<Correction> corrections = new ArrayList<>();
        for (int index = 0; index < batches.size(); index++) {
            if (index > 0 && !pauseBetweenBatches()) {
                log.warn("配息四日期回補被中斷，已完成的標的保留在進度檔，下次執行會續跑");
                safeAppend("INTERRUPTED\tafterBatch=" + index);
                break;
            }
            List<Target> batch = batches.get(index);
            log.info("配息四日期回補：批次 {}/{}（{} 檔）", index + 1, batches.size(), batch.size());
            for (Target target : batch) {
                try {
                    corrections.addAll(backfillOne(target));
                    ledger.markCompleted(target.key());
                    succeeded.add(target.key());
                } catch (Exception e) {
                    String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
                    failures.add(new Failure(target.code(), target.market(), reason));
                    // 357.3c：失敗必須逐檔留痕，不得靜默跳過；且不影響其餘標的繼續。
                    log.warn("配息四日期回補失敗：{} {} — {}", target.market(), target.code(), reason);
                    safeAppend("FAIL\t" + target.market() + "\t" + target.code() + "\t" + reason);
                }
            }
        }

        Report report = new Report(all.size(), all.size() - pending.size(), pending.size(),
                batches.size(), succeeded, failures, corrections);
        safeAppend("SUMMARY\tattempted=" + report.attempted() + "\tsucceeded="
                + report.succeeded().size() + "\tfailed=" + report.failures().size()
                + "\tcorrections=" + report.corrections().size());
        return report;
    }

    /**
     * 單檔的兩段回補 ＋ 357.3b 修正偵測。
     *
     * <p>「重抓之前」的 current-state 必須在呼叫 {@link DividendResyncClient#resyncOne} <b>之前</b>
     * 讀取，否則比不出日期搬家。</p>
     */
    private List<Correction> backfillOne(Target target) {
        List<Row> before = currentState(target);
        int events = resyncClient.resyncOne(target.code(), target.market());
        projectionService.projectOne(target.code(), target.market(), Instant.now());
        List<Row> after = currentState(target);

        List<Correction> found = detectCorrections(target, before, after);
        safeAppend("OK\t" + target.market() + "\t" + target.code()
                + "\tfetchedEvents=" + events
                + "\trowsBefore=" + before.size() + "\trowsAfter=" + after.size()
                + "\texRightsBefore=" + countExRights(before)
                + "\texRightsAfter=" + countExRights(after)
                + "\tcorrections=" + found.size());
        for (Correction correction : found) {
            log.info("配息四日期回補 357.3b 修正：{} {} 年度 {} 的 {} 由除息日欄移至除權日欄"
                            + "（現金 {}／股票 {}）",
                    correction.market(), correction.code(), correction.year(),
                    correction.movedDate(), plain(correction.cashDividend()),
                    plain(correction.stockDividend()));
            safeAppend("CORRECTION\t" + correction.market() + "\t" + correction.code()
                    + "\tyear=" + correction.year()
                    + "\tmovedDate=" + correction.movedDate()
                    + "\tcash=" + plain(correction.cashDividend())
                    + "\tstock=" + plain(correction.stockDividend()));
        }
        return found;
    }

    /**
     * 把 {@code pending} 依 {@code size} 切成連續批次；<b>順序與內容一律保留</b>。
     *
     * <p>{@code size <= 0} 視為「不分批」（單一批）——這是設定失誤時最無害的行為：
     * 切成大小 0 的批次會無限迴圈，切成每批一檔則會把 batch pause 乘上標的數。</p>
     */
    public static List<List<Target>> partition(List<Target> pending, int size) {
        if (pending == null || pending.isEmpty()) return List.of();
        int effective = size <= 0 ? pending.size() : size;
        List<List<Target>> batches = new ArrayList<>();
        for (int from = 0; from < pending.size(); from += effective) {
            batches.add(List.copyOf(pending.subList(from, Math.min(from + effective, pending.size()))));
        }
        return List.copyOf(batches);
    }

    /**
     * 357.3b 的偵測條件：同一年度、同一組金額的事件，回補前 {@code ex_dividend_date} 有值而
     * {@code ex_rights_date} 為空，回補後<b>剛好反過來且是同一個日期</b>——也就是舊資料那個
     * 「除息日」其實是除權日。
     *
     * <p>用 {@code Map#remove} 逐筆消耗，同一列不會被兩筆 after 重複認領。</p>
     */
    private static List<Correction> detectCorrections(
            Target target, List<Row> before, List<Row> after) {
        Map<String, Row> movable = new LinkedHashMap<>();
        for (Row row : before) {
            if (row.exDividendDate() != null && row.exRightsDate() == null) {
                movable.putIfAbsent(
                        identity(row.year(), row.exDividendDate(), row.cash(), row.stock()), row);
            }
        }
        List<Correction> found = new ArrayList<>();
        for (Row row : after) {
            if (row.exDividendDate() != null || row.exRightsDate() == null) continue;
            String key = identity(row.year(), row.exRightsDate(), row.cash(), row.stock());
            if (movable.remove(key) != null) {
                found.add(new Correction(target.code(), target.market(), row.year(),
                        row.exRightsDate(), row.cash(), row.stock()));
            }
        }
        return found;
    }

    private List<Row> currentState(Target target) {
        List<Row> rows = new ArrayList<>();
        for (StockDividendHistory history
                : historyRepository.findByStockSinceYear(target.code(), target.market(), sinceYear)) {
            rows.add(new Row(history.getYear(), history.getExDividendDate(),
                    history.getExRightsDate(), history.getCashDividend(),
                    history.getStockDividend()));
        }
        return rows;
    }

    private static long countExRights(List<Row> rows) {
        return rows.stream().filter(row -> row.exRightsDate() != null).count();
    }

    /**
     * 事件身分：年度 ＋ 日期 ＋ 兩個金額。金額一律以
     * {@link BigDecimal#stripTrailingZeros()} 正規化後比較，語意等同 {@code compareTo}
     * ——{@code equals} 會因為 scale 由 4 變 8 而判成不同值（Task 356 已踩過）。
     */
    private static String identity(
            Integer year, LocalDate date, BigDecimal cash, BigDecimal stock) {
        return year + "|" + date + "|" + plain(cash) + "|" + plain(stock);
    }

    private static String plain(BigDecimal value) {
        return value == null ? "0" : value.stripTrailingZeros().toPlainString();
    }

    /** @return {@code false} 代表執行緒被中斷，呼叫端應停止並保留進度 */
    private boolean pauseBetweenBatches() {
        if (batchPauseMillis <= 0) return true;
        try {
            Thread.sleep(batchPauseMillis);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 稽核帳寫入失敗不得讓回補整個停下來：帳是稽核用的，抓取與投影才是本體。
     * {@link DividendBackfillLedger#markCompleted} 刻意<b>不</b>經過這裡——進度寫不進去就
     * 必須當成該檔失敗，否則下一次續跑會漏掉它。
     */
    private void safeAppend(String line) {
        try {
            ledger.appendLine(line);
        } catch (RuntimeException e) {
            log.warn("回補稽核紀錄寫入失敗（不影響回補本身）：{}", e.getMessage());
        }
    }

    /** current-state 的不可變快照；刻意不持有 entity，避免 JPA session 狀態影響前後比對。 */
    private record Row(
            Integer year,
            LocalDate exDividendDate,
            LocalDate exRightsDate,
            BigDecimal cash,
            BigDecimal stock) {}
}
