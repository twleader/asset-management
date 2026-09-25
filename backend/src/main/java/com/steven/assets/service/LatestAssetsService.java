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
        return assemble(latest, detail, live);
    }

    /**
     * Requirement 163／Task 452.5：owner-explicit 版本，供沒有 request context 的背景 producer 使用。
     * 背景執行緒不會啟用 {@code ownerFilter}，因此絕不可呼叫無 owner 條件的 {@code findLatestWithStocks()}；
     * detail 與 live 皆由同一個已載入的 entity 導出，一致性檢查與 Response 組裝與 {@link #getLatest()} 共用。
     */
    @Transactional(readOnly = true)
    public LatestAssetsDto.Response getLatestForOwner(long ownerId) {
        AssetSnapshot latest = snapshots.findLatestWithStocksByOwnerUserId(ownerId)
                .orElseThrow(() -> new NoSuchElementException("尚無資產快照"));
        AssetSnapshotDto.SnapshotDetailResponse detail = assets.getSnapshotDetail(latest);
        StockPriceService.LiveAssetsResponse live = stockPrices.getLiveAssets(latest);
        return assemble(latest, detail, live);
    }

    private LatestAssetsDto.Response assemble(AssetSnapshot latest,
                                              AssetSnapshotDto.SnapshotDetailResponse detail,
                                              StockPriceService.LiveAssetsResponse live) {
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
