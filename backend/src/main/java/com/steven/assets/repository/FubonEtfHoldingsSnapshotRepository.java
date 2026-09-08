package com.steven.assets.repository;

import com.steven.assets.model.FubonEtfHoldingsSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Set;

public interface FubonEtfHoldingsSnapshotRepository extends JpaRepository<FubonEtfHoldingsSnapshot, String> {
    @Query("select snapshot.etfStockCode from FubonEtfHoldingsSnapshot snapshot "
            + "where snapshot.success = true and snapshot.etfStockCode in :codes")
    Set<String> findSuccessfulEtfStockCodes(@Param("codes") Collection<String> codes);
}
