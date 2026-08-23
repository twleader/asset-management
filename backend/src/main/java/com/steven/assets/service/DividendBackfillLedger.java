package com.steven.assets.service;

import java.util.Set;

/**
 * 一次性回補的<b>進度與稽核帳</b>（Requirement 94 / Task 357.3c）。
 *
 * <p>Task 357.3c 要求回補「分批、可中斷續跑、失敗逐檔記錄且不得靜默跳過」。既有的
 * {@code DividendPersister.scheduledSyncAll} 失敗只有一行 {@code log.warn}，重跑會從頭再跑一次
 * ——那既不能續跑也留不下清單，這正是 357.3a 允許新增一支內部 runner 的理由。</p>
 *
 * <p><b>進度必須存在容器外</b>：回補以 recreate 容器的方式觸發，寫在容器可寫層的進度在
 * 下一次 recreate 就消失，「續跑」會退化成「整個重跑」。唯一實作
 * {@code com.steven.assets.repository.FileDividendBackfillLedger} 因此把檔案寫在
 * {@code EXPORT_OUTPUT_DIR} 之下——那是既有的 host 家目錄 volume。</p>
 *
 * <p><b>刻意不建新資料表。</b>回補是一次性動作，為它加一張表要多一個 Liquibase changeset、
 * 多一份長期維護的 schema，而它在回補完成後永遠不會再被讀。</p>
 */
public interface DividendBackfillLedger {

    /**
     * 已完成的標的 key（{@code market|code}，見
     * {@link DividendBackfillTargetRepository.Target#key()}）。
     *
     * <p>讀取失敗一律往外拋：這裡靜默回空集合會讓整批標的被重抓一次，正好撞上 357.3c
     * 要避免的 FinMind 額度問題。</p>
     */
    Set<String> completedKeys();

    /** 標記一檔已完成。<b>只有兩段都成功才呼叫</b>，失敗的標的必須留給下一次續跑。 */
    void markCompleted(String key);

    /** 追加一行稽核紀錄（成功、失敗、357.3b 修正）。 */
    void appendLine(String line);
}
