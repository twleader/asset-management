package com.steven.assets.repository;

import com.steven.assets.model.AssetSnapshot;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface AssetSnapshotRepository extends JpaRepository<AssetSnapshot, Long> {

    Optional<AssetSnapshot> findBySnapshotDate(LocalDate date);

    /** Snapshot mutation 的唯一 by-id row lock；必須在 active transaction 內呼叫。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM AssetSnapshot s WHERE s.id = :id")
    Optional<AssetSnapshot> findByIdForUpdate(@Param("id") Long id);

    /** Bulk maintenance 依固定 id 次序一次鎖住所有 snapshot，避免與局部 replace 交錯。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM AssetSnapshot s ORDER BY s.id ASC")
    List<AssetSnapshot> findAllForUpdateOrderById();

    boolean existsBySnapshotDate(LocalDate date);

    List<AssetSnapshot> findAllByOrderBySnapshotDateAsc();

    /**
     * 儀表板指定快照的 history：只讀該 owner 的指定列及前一列，再交由共用 builder 計算。
     * owner/date 唯一鍵使 MAX(previous date) 至多匹配一列，整個查詢至多回傳兩個 roots。
     */
    @Query("SELECT s FROM AssetSnapshot s WHERE s.ownerUserId = :ownerUserId " +
            "AND (s.id = :snapshotId OR s.snapshotDate = " +
            "(SELECT MAX(previous.snapshotDate) FROM AssetSnapshot previous " +
            "WHERE previous.ownerUserId = :ownerUserId AND previous.snapshotDate < :snapshotDate)) " +
            "ORDER BY s.snapshotDate ASC")
    List<AssetSnapshot> findHistoryWindow(@Param("ownerUserId") Long ownerUserId,
                                        @Param("snapshotId") Long snapshotId,
                                        @Param("snapshotDate") LocalDate snapshotDate);

    @Query("SELECT s FROM AssetSnapshot s ORDER BY s.snapshotDate DESC")
    List<AssetSnapshot> findAllOrderByDateDesc();

    @Query("SELECT s FROM AssetSnapshot s ORDER BY s.snapshotDate DESC LIMIT 1")
    Optional<AssetSnapshot> findLatest();

    /**
     * 背景排程專用（Requirement 35 / Task 174）：所有擁有快照的 owner id。
     * 背景無 request context → {@code @Filter(ownerFilter)} 不啟用 → 此查詢跨全部租戶，
     * 供 {@code SnapshotDateRollScheduler} 逐 owner 各自處理其最新快照。
     */
    @Query("SELECT DISTINCT s.ownerUserId FROM AssetSnapshot s")
    List<Long> findDistinctOwnerUserIds();

    /**
     * 指定 owner 的最新一筆快照（Requirement 35 / Task 174）。
     * 帶 {@code ownerUserId} 條件 → 即使背景排程 {@code @Filter} 未啟用亦 owner-scoped 安全；
     * 不可改用無 owner 的 {@link #findLatest()}（背景會拿到全體最大日期那一筆、漏掉其他 owner）。
     * derived name 等同 {@code ORDER BY snapshot_date DESC LIMIT 1}。
     */
    Optional<AssetSnapshot> findFirstByOwnerUserIdOrderBySnapshotDateDesc(Long ownerUserId);

    /**
     * Fubon configured-admin preflight only. Native SQL keeps the explicit server-selected
     * owner authoritative when an internal request has no ordinary tenant identity. Generic
     * user reads above deliberately retain their Hibernate tenant filter.
     */
    @Query(value = "SELECT * FROM asset_snapshot WHERE owner_user_id = :ownerUserId " +
            "ORDER BY snapshot_date DESC LIMIT 1", nativeQuery = true)
    Optional<AssetSnapshot> findLatestForFubonConfiguredOwner(@Param("ownerUserId") Long ownerUserId);

    /**
     * Same physical row locked by generic snapshot mutations, restricted to Fubon writers.
     * The caller must recheck ACTIVE configured-admin ownership after this first DB operation.
     */
    @Query(value = "SELECT * FROM asset_snapshot WHERE owner_user_id = :ownerUserId " +
            "ORDER BY snapshot_date DESC LIMIT 1 FOR UPDATE", nativeQuery = true)
    Optional<AssetSnapshot> lockLatestForFubonConfiguredOwner(@Param("ownerUserId") Long ownerUserId);

    /** 背景 mutation 專用 owner-latest row lock；owner/date unique 保證最多一列。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM AssetSnapshot s WHERE s.ownerUserId = :ownerUserId " +
            "AND s.snapshotDate = (SELECT MAX(s2.snapshotDate) FROM AssetSnapshot s2 " +
            "WHERE s2.ownerUserId = :ownerUserId)")
    Optional<AssetSnapshot> findLatestByOwnerUserIdForUpdate(@Param("ownerUserId") Long ownerUserId);

    /** 背景交易雷達通知專用：明確 owner 條件並一併載入最新快照持股。 */
    @Query("SELECT s FROM AssetSnapshot s LEFT JOIN FETCH s.stocks " +
            "WHERE s.id = (SELECT s2.id FROM AssetSnapshot s2 " +
            "WHERE s2.ownerUserId = :ownerUserId ORDER BY s2.snapshotDate DESC LIMIT 1)")
    Optional<AssetSnapshot> findLatestWithStocksByOwnerUserId(Long ownerUserId);

    /**
     * 用 JOIN FETCH 載入最新快照及其持股，避免 LazyInitializationException
     * 使用子查詢取得最新快照 ID，再用 JOIN FETCH 載入
     */
    @Query("SELECT s FROM AssetSnapshot s LEFT JOIN FETCH s.stocks WHERE s.id = (SELECT s2.id FROM AssetSnapshot s2 ORDER BY s2.snapshotDate DESC LIMIT 1)")
    Optional<AssetSnapshot> findLatestWithStocks();

    /**
     * 用 JOIN FETCH 載入所有快照及其持股
     */
    @Query("SELECT DISTINCT s FROM AssetSnapshot s LEFT JOIN FETCH s.stocks ORDER BY s.snapshotDate ASC")
    List<AssetSnapshot> findAllWithStocksOrderByDateAsc();

    /**
     * 績效比較（Requirement 33）：使用者跨全部快照持有過的 distinct (stockCode, market)。
     * 查詢 root 為帶 {@code @Filter(ownerFilter)} 的 AssetSnapshot（JOIN s.stocks），owner 隔離於 repository 層
     * 自動生效——不可改為直查無 @Filter 的 StockHolding，否則會跨租戶洩漏。結果欄位 [stockCode, market]。
     */
    @Query("SELECT DISTINCT sh.stockCode, sh.market FROM AssetSnapshot s JOIN s.stocks sh")
    List<Object[]> findDistinctOwnedStocks();
}
