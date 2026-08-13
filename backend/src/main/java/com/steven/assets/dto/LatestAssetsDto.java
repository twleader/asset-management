package com.steven.assets.dto;

import com.steven.assets.service.StockPriceService;

import java.time.Instant;
import java.util.Map;

/** Requirement 68：外部最新完整資產的 immutable aggregation contract。 */
public final class LatestAssetsDto {
    private LatestAssetsDto() {}

    public record Response(
            Instant generatedAt,
            String valuationPolicy,
            boolean targetPriceComplete,
            Map<String, Object> marketStatus,
            AssetSnapshotDto.SnapshotDetailResponse snapshot,
            StockPriceService.LiveAssetsResponse liveAssets
    ) {}
}
