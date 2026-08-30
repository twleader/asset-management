package com.steven.assets.repository;

import com.steven.assets.model.FubonEtfHoldingsSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FubonEtfHoldingsSnapshotRepository extends JpaRepository<FubonEtfHoldingsSnapshot, String> {
}
