package com.steven.assets.service.srpp.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.service.AssetService;
import com.steven.assets.srpp.SrppAssetSnapshotIdentity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Same-owner DB-only static snapshot fence. No market capture, computation, or source mutation. */
@Service
public class SrppReportAssetRevision {
    private final AssetSnapshotRepository snapshots;
    private final AssetService assets;
    private final ObjectMapper mapper;

    public SrppReportAssetRevision(AssetSnapshotRepository snapshots, AssetService assets, ObjectMapper mapper) {
        this.snapshots = snapshots;
        this.assets = assets;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public boolean matches(long owner, JsonNode frozenSnapshot) {
        var latest = snapshots.findLatestWithStocksByOwnerUserId(owner);
        if (latest.isEmpty() || !Long.valueOf(owner).equals(latest.get().getOwnerUserId())) return false;
        var detail = assets.getSnapshotDetail(latest.get());
        return detail != null && SrppAssetSnapshotIdentity.matches(frozenSnapshot, mapper.valueToTree(detail));
    }
}
