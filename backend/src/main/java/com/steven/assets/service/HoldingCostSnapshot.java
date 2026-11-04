package com.steven.assets.service;

import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.model.StockHolding;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;

/** Immutable, content-addressed projection of already-persisted private Fubon costs. */
public record HoldingCostSnapshot(Long ownerId, Long snapshotId, LocalDate snapshotDate, List<Entry> entries) {
    public HoldingCostSnapshot {
        if (ownerId == null || ownerId <= 0 || snapshotId == null || snapshotId <= 0 || snapshotDate == null) {
            throw new IllegalArgumentException("INVALID_COST_SNAPSHOT_IDENTITY");
        }
        entries = entries.stream().sorted(Comparator.comparing(Entry::brokerId)
                .thenComparing(Entry::stockCode).thenComparing(Entry::holdingId)).toList();
        var ids = new HashSet<Long>();
        if (entries.stream().anyMatch(entry -> !ids.add(entry.holdingId()))) {
            throw new IllegalArgumentException("DUPLICATE_COST_HOLDING");
        }
    }

    public static HoldingCostSnapshot capture(AssetSnapshot snapshot) {
        return new HoldingCostSnapshot(snapshot.getOwnerUserId(), snapshot.getId(), snapshot.getSnapshotDate(),
                snapshot.getStocks().stream().filter(HoldingCostSnapshot::inScope)
                        .map(stock -> new Entry(stock.getId(), stock.getBroker().getId(), stock.getStockCode(),
                                stock.getMarket(), stock.getCurrency(), stock.getShares(), stock.getInvestmentCost())).toList());
    }

    public static boolean inScope(StockHolding stock) {
        return "台股".equals(stock.getMarket()) && "TWD".equals(stock.getCurrency())
                && stock.getBroker() != null && "fubon".equals(stock.getBroker().getCode());
    }

    public record Entry(Long holdingId, Long brokerId, String stockCode, String market, String currency,
                        BigDecimal shares, BigDecimal investmentCost) {
        public Entry {
            if (holdingId == null || holdingId <= 0 || brokerId == null || brokerId <= 0
                    || stockCode == null || !stockCode.matches("[0-9A-Z]{2,20}")
                    || !"台股".equals(market) || !"TWD".equals(currency)
                    || shares == null || shares.signum() <= 0 || shares.scale() > 5 || shares.precision() > 15
                    || investmentCost == null || investmentCost.signum() < 0
                    || investmentCost.scale() > 2 || investmentCost.precision() > 20) {
                throw new IllegalArgumentException("INVALID_COST_ENTRY");
            }
        }
    }

    private static String decimal(BigDecimal value) { return value.stripTrailingZeros().toPlainString(); }

    /** All textual fields are validated identifiers, constants or an ISO date; no arbitrary JSON text. */
    public String canonicalJson() {
        StringBuilder out = new StringBuilder("{\"schemaVersion\":1,\"ownerId\":").append(ownerId)
                .append(",\"snapshotId\":").append(snapshotId).append(",\"snapshotDate\":\"")
                .append(snapshotDate).append("\",\"entries\":[");
        for (int i = 0; i < entries.size(); i++) {
            Entry row = entries.get(i);
            if (i > 0) out.append(',');
            out.append("{\"holdingId\":").append(row.holdingId()).append(",\"brokerId\":").append(row.brokerId())
                    .append(",\"stockCode\":\"").append(row.stockCode()).append("\",\"market\":\"台股\",\"currency\":\"TWD\",\"shares\":\"")
                    .append(decimal(row.shares())).append("\",\"investmentCost\":\"")
                    .append(decimal(row.investmentCost())).append("\"}");
        }
        return out.append("]}").toString();
    }

    public String cacheKey() {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonicalJson().getBytes(StandardCharsets.UTF_8)));
            return "fubon:holding-cost:" + ownerId + ":" + snapshotId + ":" + hash;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA_256_UNAVAILABLE", impossible);
        }
    }
}
