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
     * 觸發即時匯出用（Requirement 54 / Task 254）：某 owner 在 {@code [start, end)} 這個
     * <b>台北日期窗</b>內的全部觸發，含獨立條件與複合群組兩種來源，依寫入時間升冪。
     *
     * <p><b>窗口一律以 {@code createdAt} 界定，不可用 {@code triggeredAt}。</b>{@code triggeredAt} 存的是
     * <b>市場牆鐘</b>（美股存紐約時間、英股存倫敦時間），{@code createdAt} 才是台北牆鐘。用 {@code triggeredAt}
     * 的話，台北凌晨觸發的美股警示（紐約時間仍是前一日下午）會被歸進前一天的檔案——檔名日期與檔案實際
     * 產生日期分家，下游依日期取檔會抓不到剛剛那一筆。
     *
     * <p><b>owner 條件必須顯式寫在查詢裡，不得依賴 {@code @Filter(ownerFilter)}。</b>本查詢的主要呼叫端是
     * 背景執行緒（Redis 訂閱者），沒有 request context，{@code TenantFilterAspect} 直接 return、filter 不會
     * 被 enable；而 {@code StockAlertTrigger} 本身也沒掛 filter。漏掉 owner 條件就是把所有使用者的觸發
     * 寫進每個人的檔案，屬實質的跨租戶資料外流。
     *
     * <p>owner 走 join 而非在本表加 {@code owner_user_id} 冗餘欄：加了會違反「相同的資料只能存一份」，
     * 且該欄會與來源分家（alert 換 owner 後舊 trigger 列不會跟著改）。
     */
    @Query("SELECT t FROM StockAlertTrigger t WHERE t.createdAt >= :start AND t.createdAt < :end AND ("
            + "  (t.alertId IS NOT NULL AND EXISTS (SELECT 1 FROM StockAlert a "
            + "     WHERE a.id = t.alertId AND a.ownerUserId = :ownerId)) OR "
            + "  (t.groupId IS NOT NULL AND EXISTS (SELECT 1 FROM StockAlertGroup g "
            + "     WHERE g.id = t.groupId AND g.ownerUserId = :ownerId))) "
            + "ORDER BY t.createdAt ASC")
    List<StockAlertTrigger> findByOwnerAndCreatedAtInDay(@Param("ownerId") Long ownerId,
                                                         @Param("start") LocalDateTime start,
                                                         @Param("end") LocalDateTime end);

    @Modifying
    @Query("DELETE FROM StockAlertTrigger t WHERE t.createdAt < :cutoff")
    int deleteByCreatedAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
