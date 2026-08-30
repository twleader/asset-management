package com.steven.assets.service;

import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.repository.AssetSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/**
 * 所有既有 snapshot children／aggregate mutation 共用的 PostgreSQL row-lock 入口。
 * 呼叫端必須讓它成為該 transaction 的第一個 DB operation。
 */
@Service
@RequiredArgsConstructor
public class AssetSnapshotMutationLock {

    private final AssetSnapshotRepository snapshotRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public AssetSnapshot lockById(Long snapshotId) {
        return snapshotRepository.findByIdForUpdate(snapshotId)
                .orElseThrow(() -> new NoSuchElementException("找不到快照 ID: " + snapshotId));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<AssetSnapshot> lockLatestForOwner(Long ownerUserId) {
        return snapshotRepository.findLatestByOwnerUserIdForUpdate(ownerUserId);
    }

    /** Fubon internal sync only; ordinary user mutations keep the tenant-filtered methods. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<AssetSnapshot> lockLatestForFubonConfiguredOwner(Long ownerUserId) {
        return snapshotRepository.lockLatestForFubonConfiguredOwner(ownerUserId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public List<AssetSnapshot> lockAllInIdOrder() {
        return snapshotRepository.findAllForUpdateOrderById();
    }
}
