package com.steven.assets.repository;

import com.steven.assets.model.StockAlertTrigger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface StockAlertTriggerRepository extends JpaRepository<StockAlertTrigger, Long> {

    List<StockAlertTrigger> findByAlertIdOrderByTriggeredAtDesc(Long alertId);

    /** 補發用：trigger 表內出現過的市場（各市場各自算「最後交易日」）。 */
    @Query("SELECT DISTINCT t.market FROM StockAlertTrigger t")
    List<String> findDistinctMarkets();

    /** 補發用：某市場 triggered_at 落在 [start, end) 當日窗的觸發（依時間升冪）。 */
    @Query("SELECT t FROM StockAlertTrigger t WHERE t.market = :market " +
            "AND t.triggeredAt >= :start AND t.triggeredAt < :end ORDER BY t.triggeredAt ASC")
    List<StockAlertTrigger> findByMarketAndTriggeredAtInDay(@Param("market") String market,
            @Param("start") LocalDateTime start, @Param("end") LocalDateTime end);

    /**
     * 觸發即時匯出用（Requirement 54 / Task 254、視窗語意由 Task 256 修正）：某 owner 自 {@code since}
     * 起的全部觸發，含獨立條件與複合群組兩種來源，依寫入時間升冪。
     *
     * <p><b>刻意沒有上界</b>：未來時間不存在，多一個上界只是多一個會寫錯的參數。
     *
     * <p><b>一律以 {@code createdAt} 篩選，不可用 {@code triggeredAt}。</b>{@code since} 是台北時刻，
     * 只有 {@code createdAt} 與它同一個時鐘（{@code triggeredAt} 是<b>市場牆鐘</b>：美股紐約、英股倫敦）。
     * 拿市場牆鐘去比台北時刻，美股會整批位移 12 小時、英股 7 小時，視窗邊界附近的觸發時有時無。
     *
     * <p><b>為什麼是滾動視窗而不是「當日」</b>（Task 256）：美股交易時段換算台北是 21:30 → 隔日 04:00、
     * 橫跨午夜，任何以台北日期分檔的方案都會把同一個美股交易日切成兩個檔案（實測紐約 07-28 的四筆觸發
     * 被切成台北 07-28 三筆 ＋ 07-29 一筆，使用者打開當日檔只看得到一筆）。
     *
     * <p><b>owner 條件必須顯式寫在查詢裡，不得依賴 {@code @Filter(ownerFilter)}。</b>本查詢的主要呼叫端是
     * 背景執行緒（Redis 訂閱者），沒有 request context，{@code TenantFilterAspect} 直接 return、filter 不會
     * 被 enable；而 {@code StockAlertTrigger} 本身也沒掛 filter。漏掉 owner 條件就是把所有使用者的觸發
     * 寫進每個人的檔案，屬實質的跨租戶資料外流。
     *
     * <p>owner 走 join 而非在本表加 {@code owner_user_id} 冗餘欄：加了會違反「相同的資料只能存一份」，
     * 且該欄會與來源分家（alert 換 owner 後舊 trigger 列不會跟著改）。
     */
    @Query("SELECT t FROM StockAlertTrigger t WHERE t.createdAt >= :since AND ("
            + "  (t.alertId IS NOT NULL AND EXISTS (SELECT 1 FROM StockAlert a "
            + "     WHERE a.id = t.alertId AND a.ownerUserId = :ownerId)) OR "
            + "  (t.groupId IS NOT NULL AND EXISTS (SELECT 1 FROM StockAlertGroup g "
            + "     WHERE g.id = t.groupId AND g.ownerUserId = :ownerId))) "
            + "ORDER BY t.createdAt ASC")
    List<StockAlertTrigger> findByOwnerAndCreatedAtAfter(@Param("ownerId") Long ownerId,
                                                         @Param("since") LocalDateTime since);

    @Modifying
    @Query("DELETE FROM StockAlertTrigger t WHERE t.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
