package com.steven.assets.service;

import com.steven.assets.dto.AssetSnapshotDto;
import com.steven.assets.dto.LatestAssetsDto;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.repository.AssetSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.NoSuchElementException;

/** 所有 latest assets 資料在同一 read-only transaction 聚合，拒絕不自洽的 snapshot/live 組合。 */
@Service
@RequiredArgsConstructor
public class LatestAssetsService {
    private final AssetSnapshotRepository snapshots;
    private final AssetService assets;
    private final StockPriceService stockPrices;

    @Transactional(readOnly = true)
    public LatestAssetsDto.Response getLatest() {
        AssetSnapshot latest = snapshots.findLatestWithStocks()
                .orElseThrow(() -> new NoSuchElementException("尚無資產快照"));
        AssetSnapshotDto.SnapshotDetailResponse detail = assets.getSnapshotDetail(latest.getId());
        StockPriceService.LiveAssetsResponse live = stockPrices.getLiveAssets();
        if (live == null || live.snapshotId() == null || !latest.getId().equals(live.snapshotId())) {
            throw new IllegalStateException("最新快照與即時資產資料不一致");
        }
        boolean targetComplete = live.stocks().stream()
                .allMatch(item -> "TARGET_SESSION_PRICE".equals(item.valuationSource()));
        return new LatestAssetsDto.Response(
                Instant.now(), "TARGET_SESSION_WITH_EXPLICIT_FALLBACK", targetComplete,
                stockPrices.getMarketStatus(), detail, live);
    }
}
