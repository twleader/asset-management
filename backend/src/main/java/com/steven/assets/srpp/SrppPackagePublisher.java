package com.steven.assets.srpp;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.service.AssetService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Requirement 163／Task 452.9：原子發布不可變 package 與三筆 evidence。
 *
 * <p>同一交易內：確保 owner key → 以 owner-explicit 讀取重算快照 revision（與凍結時不同即放棄）→ 取
 * generatedAt → 組 context → 不變量 → JCS → INSERT package 與 evidence。任一步例外整筆 rollback。
 * 除 {@code srpp_owner_key}／{@code srpp_context_package}／{@code srpp_context_evidence} 外不寫任何資料。
 */
@Slf4j
@Service
public class SrppPackagePublisher {
    private final SrppOwnerKeyRepository ownerKeys;
    private final SrppContextPackageRepository packages;
    private final SrppContextEvidenceRepository evidence;
    private final AssetSnapshotRepository snapshots;
    private final AssetService assets;
    private final Clock clock;

    @Autowired
    public SrppPackagePublisher(SrppOwnerKeyRepository ownerKeys, SrppContextPackageRepository packages,
                                SrppContextEvidenceRepository evidence, AssetSnapshotRepository snapshots,
                                AssetService assets) {
        this(ownerKeys, packages, evidence, snapshots, assets, Clock.system(SrppTime.TW_ZONE));
    }

    SrppPackagePublisher(SrppOwnerKeyRepository ownerKeys, SrppContextPackageRepository packages,
                         SrppContextEvidenceRepository evidence, AssetSnapshotRepository snapshots,
                         AssetService assets, Clock clock) {
        this.ownerKeys = ownerKeys;
        this.packages = packages;
        this.evidence = evidence;
        this.snapshots = snapshots;
        this.assets = assets;
        this.clock = clock;
    }

    /** @return 發布的 packageId；快照 revision 已變動時回 empty（留待下一輪）。 */
    @Transactional
    public Optional<UUID> publish(SrppCapture capture, ObjectNode modules) {
        long ownerId = capture.ownerId();
        ownerKeys.insertIfAbsent(ownerId, UUID.randomUUID());
        UUID ownerKey = ownerKeys.findOwnerKey(ownerId)
                .orElseThrow(() -> new SrppRejectedException("OWNER_KEY_MISSING"));

        Optional<AssetSnapshot> latest = snapshots.findLatestWithStocksByOwnerUserId(ownerId);
        String currentRevision = latest.map(entity -> SrppSnapshotRevision.of(assets.getSnapshotDetail(entity)))
                .orElse(null);
        if (!capture.assetsRevision().equals(currentRevision)) {
            log.info("SRPP package 放棄發布 owner={} reason=SOURCE_REVISION_CHANGED", ownerId);
            return Optional.empty();
        }

        UUID packageId = UUID.randomUUID();
        Instant generatedAt = clock.instant();
        ObjectNode context = SrppContextAssembler.assemble(packageId, generatedAt, ownerKey, capture, modules);
        SrppContextInvariants.verify(context);
        String contextJcs = SrppJcs.canonicalize(context);

        // 查詢鍵與 context 內同值（同一程式路徑寫入）；generated_at 以 context 的秒精度值保存。
        Instant storedGeneratedAt = SrppTime.parse(context.path("generatedAt").textValue());
        packages.insertPackage(packageId, ownerId, capture.tradingDate(), capture.slot(),
                capture.policy().bundleHash(), storedGeneratedAt, contextJcs);
        for (SrppSourceEvidence source : capture.sources()) {
            evidence.insertEvidence(packageId, source.sourceId(), source.body());
        }
        return Optional.of(packageId);
    }
}
