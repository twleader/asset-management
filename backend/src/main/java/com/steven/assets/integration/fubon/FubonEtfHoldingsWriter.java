package com.steven.assets.integration.fubon;

import com.steven.assets.model.FubonEtfHoldingsSnapshot;
import com.steven.assets.repository.FubonEtfHoldingsSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** One independently committed current result per ETF; no adapter I/O runs inside this transaction. */
@Service
@RequiredArgsConstructor
public class FubonEtfHoldingsWriter {
    private final FubonEtfHoldingsSnapshotRepository repository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void save(FubonEtfHoldingsSnapshot result) {
        repository.saveAndFlush(result);
    }
}
