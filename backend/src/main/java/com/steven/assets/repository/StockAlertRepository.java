package com.steven.assets.repository;

import com.steven.assets.model.StockAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface StockAlertRepository extends JpaRepository<StockAlert, Long> {
    List<StockAlert> findAllByOrderByDisplayOrderAsc();
    List<StockAlert> findByActiveTrue();
    List<StockAlert> findByStockCodeAndMarket(String stockCode, String market);

    /**
     * 背景檢查的取件口（Task 253）：只取「獨立單一條件」（{@code group_id IS NULL}）。
     *
     * <p>複合條件群組的成員 {@code active} 恆為 true，若沿用 {@link #findByActiveTrue()} 取件，
     * 每個成員都會各自被 {@code evaluate} 判定、各自寄信 —— AND 語意會靜默退化成 OR
     * （功能看起來有做、卻毫無錯誤訊息）。群組改由 {@code StockAlertGroupRepository.findByActiveTrue()}
     * 取件並整組 AND 判定。
     */
    List<StockAlert> findByActiveTrueAndGroupIdIsNull();

    /**
     * 某複合條件群組的成員條件，依 {@code displayOrder} 升冪（Task 253）。
     * 排序即群組合併 label（{@code buildGroupLabel}）的條件串接順序，故不可改成依 id 排。
     */
    List<StockAlert> findByGroupIdOrderByDisplayOrderAsc(Long groupId);

    /**
     * 刪除某群組的全部成員（Task 253：{@code updateGroup} 整組覆寫、{@code deleteGroup} 清成員）。
     *
     * <p>刻意用 derived delete（entity remove）而非 bulk {@code @Modifying}：bulk delete 繞過
     * persistence context，先前若已載入這些成員（例如 {@code evaluateGroup} 讀過），
     * 它們會以 managed 狀態殘留、flush 時對已刪除的列發 UPDATE → 0 rows → StaleStateException。
     * {@code stock_alert} 無 unique 約束，不存在 {@code StockAlertRecipientRepository.deleteByAlertId}
     * 那種「先 insert 後 delete 撞唯一鍵」的問題，故無需 bulk。
     * 自帶 {@code @Transactional}：derived delete 需交易界定；呼叫端 service 方法本身已是
     * {@code @Transactional}（此處 join），此標註只是讓本方法自足、不依賴呼叫端。
     */
    @Transactional
    void deleteByGroupId(Long groupId);

    /**
     * 觀察清單衍生 view：每個 (stockCode, market) 一筆，附該股票最小 displayOrder（用於排序）。
     * 結果欄位 [stockCode, market, minDisplayOrder]，依 minDisplayOrder 升冪。
     */
    @Query("SELECT a.stockCode, a.market, MIN(a.displayOrder) as ord " +
            "FROM StockAlert a GROUP BY a.stockCode, a.market ORDER BY ord ASC")
    List<Object[]> findDistinctStockCodeMarket();

    /** 同股票同市場所有 alert 的最小 displayOrder（拖曳重排觀察清單時用作群組 anchor）。 */
    @Query("SELECT MIN(a.displayOrder) FROM StockAlert a " +
            "WHERE a.stockCode = :code AND a.market = :market")
    Integer findMinDisplayOrderFor(String code, String market);
}
