package com.steven.assets.repository;

import com.steven.assets.model.StockAlertGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 複合條件警示群組存取（Requirement 16 / Task 253）。
 *
 * <p>一個群組綁 2～5 條 {@code stock_alert}（成員以 {@code group_id} 指回本表），
 * 全部條件在同一次評估中成立才觸發一次；觸發狀態（{@code last_triggered_*}）只記在本表，
 * 成員那五欄一律不寫。
 */
@Repository
public interface StockAlertGroupRepository extends JpaRepository<StockAlertGroup, Long> {

    /**
     * 警示頁混合清單用：群組與獨立條件**共用同一個 displayOrder 排序空間**，
     * 兩邊各自取出後合併再依 displayOrder 升冪，才不會出現兩套順序互相矛盾。
     */
    List<StockAlertGroup> findAllByOrderByDisplayOrderAsc();

    /**
     * 背景檢查取件：啟用中的群組。啟停只看本表這一列 —— 成員的 {@code active} 恆為 true，
     * 停用群組不會把成員降級成獨立條件（成員永不進入 {@code evaluate}）。
     */
    List<StockAlertGroup> findByActiveTrue();

    /** 某股票的全部群組（觀察清單合併顯示、以及「已存在相同複合條件」驗證用）。 */
    List<StockAlertGroup> findByStockCodeAndMarket(String stockCode, String market);
}
