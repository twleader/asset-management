package com.steven.assets.repository;

import com.steven.assets.model.AssetSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface AssetSnapshotRepository extends JpaRepository<AssetSnapshot, Long> {

    Optional<AssetSnapshot> findBySnapshotDate(LocalDate date);

    boolean existsBySnapshotDate(LocalDate date);

    List<AssetSnapshot> findAllByOrderBySnapshotDateAsc();

    @Query("SELECT s FROM AssetSnapshot s ORDER BY s.snapshotDate DESC")
    List<AssetSnapshot> findAllOrderByDateDesc();

    @Query("SELECT s FROM AssetSnapshot s ORDER BY s.snapshotDate DESC LIMIT 1")
    Optional<AssetSnapshot> findLatest();

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
}
