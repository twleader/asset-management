package com.steven.assets.integration.fubon;

import com.steven.assets.model.AssetTransaction;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure, deterministic display-only formatter for source-owned Fubon transit notes. */
final class FubonTransitNoteFormatter {
    private static final int NOTE_LIMIT = 200;
    private static final BigDecimal LOT_SIZE = BigDecimal.valueOf(1000);
    private static final String PREFIX = "富邦證券當日已同步成交：";
    private static final String EMPTY = "富邦證券交割款；當日無已同步成交明細";

    String format(String transitType, List<AssetTransaction> transactions) {
        Direction direction = Direction.forTransitType(transitType);
        if (transactions == null || transactions.isEmpty()) return EMPTY;

        // assetCode is the source identity.  A corrected/display-name variant in another
        // filled row must not make one security appear twice in the settlement note.
        Map<String, InstrumentQuantity> merged = new LinkedHashMap<>();
        List<AssetTransaction> sorted = transactions.stream()
                .filter(transaction -> transaction != null)
                .sorted(Comparator.comparing(AssetTransaction::getAssetCode,
                                Comparator.nullsLast(String::compareTo))
                        .thenComparing(AssetTransaction::getAssetName, Comparator.nullsLast(String::compareTo))
                        .thenComparing(AssetTransaction::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();
        for (AssetTransaction transaction : sorted) {
            if (transaction == null || !direction.transactionType.equals(transaction.getTransactionType())
                    || transaction.getShares() == null || transaction.getShares().signum() <= 0
                    || blank(transaction.getAssetCode()) || blank(transaction.getAssetName())) {
                continue;
            }
            merged.merge(transaction.getAssetCode(), new InstrumentQuantity(transaction.getAssetName(), transaction.getShares()),
                    (existing, incoming) -> new InstrumentQuantity(existing.name(), existing.shares().add(incoming.shares())));
        }
        if (merged.isEmpty()) return EMPTY;

        StringBuilder note = new StringBuilder(PREFIX);
        boolean first = true;
        for (Map.Entry<String, InstrumentQuantity> entry : merged.entrySet()) {
            if (!first) note.append('；');
            first = false;
            note.append(direction.display).append(' ').append(entry.getValue().name())
                    .append('（').append(entry.getKey()).append('）')
                    .append(formatQuantity(entry.getValue().shares()));
        }
        if (note.length() <= NOTE_LIMIT) return note.toString();

        String summary = PREFIX + direction.display + "共 " + merged.size() + " 個標的（明細過長）";
        return summary.length() <= NOTE_LIMIT ? summary : EMPTY;
    }

    private static String formatQuantity(BigDecimal shares) {
        BigDecimal normalized = shares.stripTrailingZeros();
        if (normalized.remainder(LOT_SIZE).signum() == 0) {
            return normalized.divide(LOT_SIZE).stripTrailingZeros().toPlainString() + " 張";
        }
        return normalized.toPlainString() + " 股（"
                + normalized.divide(LOT_SIZE).stripTrailingZeros().toPlainString() + " 張）";
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private enum Direction {
        BUY("買股待付款", "買", "買入"),
        SELL("賣股待收款", "賣", "賣出");

        private final String transitType;
        private final String transactionType;
        private final String display;

        Direction(String transitType, String transactionType, String display) {
            this.transitType = transitType;
            this.transactionType = transactionType;
            this.display = display;
        }

        private static Direction forTransitType(String transitType) {
            for (Direction direction : values()) {
                if (direction.transitType.equals(transitType)) return direction;
            }
            throw new IllegalArgumentException("UNSUPPORTED_TRANSIT_TYPE");
        }
    }

    private record InstrumentQuantity(String name, BigDecimal shares) {}
}
