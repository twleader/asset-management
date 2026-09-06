package com.steven.assets.integration.fubon;

import com.steven.assets.model.RealizedGain;
import com.steven.assets.repository.RealizedGainRepository;
import com.steven.assets.service.fubon.FubonSyncOwnerPort;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class FubonRealizedGainWriter {
    private static final String MARKET = "台股";
    private static final String CURRENCY = "TWD";
    private static final String BROKER = "富邦證券";

    private final FubonSyncOwnerPort ownerPolicy;
    private final RealizedGainRepository gains;
    private final EntityManager entityManager;

    @Autowired
    public FubonRealizedGainWriter(FubonSyncOwnerPort ownerPolicy, RealizedGainRepository gains,
            EntityManager entityManager) {
        this.ownerPolicy = ownerPolicy;
        this.gains = gains;
        this.entityManager = entityManager;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 30)
    CommitResult write(Long expectedOwnerId, List<PreparedGain> incoming) {
        if (incoming == null) throw new WriteRejected(FubonRealizedGainOutcome.REALIZED_GAIN_FAILED);
        FubonSyncOwnerPort.LockedOwner owner;
        try {
            owner = ownerPolicy.lockAndRevalidate(expectedOwnerId);
        } catch (FubonSyncOwnerPort.Rejected rejected) {
            throw new WriteRejected(rejected.denial() == FubonSyncOwnerPort.Denial.SYNC_OWNER_NOT_CONFIGURED
                    ? FubonRealizedGainOutcome.SYNC_OWNER_NOT_CONFIGURED : FubonRealizedGainOutcome.NO_OWNER);
        }
        if (owner == null || !Objects.equals(owner.ownerId(), expectedOwnerId)) {
            throw new WriteRejected(FubonRealizedGainOutcome.NO_OWNER);
        }
        entityManager.createNativeQuery("LOCK TABLE realized_gain IN SHARE ROW EXCLUSIVE MODE").executeUpdate();

        List<RealizedGain> existing = gains.findAllForFubonSyncOwner(expectedOwnerId);
        List<Group> groups = group(incoming);
        Map<MappingKey, Integer> equivalentAvailable = equivalentCounts(existing);
        int inserted = 0;
        int alreadyRepresented = 0;
        List<RealizedGain> additions = new ArrayList<>();

        for (Group group : groups) {
            PreparedGain exemplar = group.rows().getFirst();
            MappingKey key = MappingKey.of(exemplar);
            List<RealizedGain> sameFingerprint = existing.stream()
                    .filter(gain -> FubonRealizedGainFingerprint.SOURCE.equals(gain.getSyncSource())
                            && exemplar.fingerprint().equals(gain.getSyncFingerprint()))
                    .toList();
            Set<Integer> usedOccurrences = new HashSet<>();
            for (RealizedGain gain : sameFingerprint) {
                if (!equivalent(gain, expectedOwnerId, exemplar) || !isValidSync(gain)
                        || !key.equals(MappingKey.of(gain)) || !usedOccurrences.add(gain.getSyncOccurrence())) {
                    throw new WriteRejected(FubonRealizedGainOutcome.EXISTING_DATA_CONFLICT);
                }
            }
            int representedBySameFingerprint = Math.min(group.rows().size(), sameFingerprint.size());
            consumeEquivalent(equivalentAvailable, key, representedBySameFingerprint);
            int representedByEquivalent = consumeEquivalent(equivalentAvailable, key,
                    group.rows().size() - representedBySameFingerprint);
            int represented = representedBySameFingerprint + representedByEquivalent;
            alreadyRepresented += represented;

            for (int i = represented; i < group.rows().size(); i++) {
                int occurrence = smallestUnused(usedOccurrences);
                usedOccurrences.add(occurrence);
                additions.add(toEntity(expectedOwnerId, exemplar, occurrence));
                inserted++;
            }
        }
        if (!additions.isEmpty()) {
            gains.saveAll(additions);
            gains.flush();
        }
        return new CommitResult(inserted, alreadyRepresented);
    }

    static PreparedGain prepare(FubonDtos.RealizedGainRow row, String localName) {
        FubonAccountingContract.PreparedRealized calculated = FubonAccountingContract.prepareRealized(row);
        String name = localName == null ? "" : localName.trim();
        if (name.isEmpty() || name.length() > 50) name = row.stockNo();
        return new PreparedGain(row, name, calculated.salePrice(), calculated.proceeds(),
                calculated.investmentCost(), FubonRealizedGainFingerprint.of(row));
    }

    private static List<Group> group(List<PreparedGain> incoming) {
        Map<String, List<PreparedGain>> byFingerprint = new LinkedHashMap<>();
        for (PreparedGain row : incoming) {
            if (!valid(row)) throw new WriteRejected(FubonRealizedGainOutcome.REALIZED_GAIN_FAILED);
            List<PreparedGain> rows = byFingerprint.computeIfAbsent(row.fingerprint(), ignored -> new ArrayList<>());
            if (!rows.isEmpty() && !sameSource(rows.getFirst().source(), row.source())) {
                throw new WriteRejected(FubonRealizedGainOutcome.EXISTING_DATA_CONFLICT);
            }
            rows.add(row);
        }
        return byFingerprint.entrySet().stream()
                .map(entry -> new Group(entry.getKey(), List.copyOf(entry.getValue())))
                .sorted(Comparator.comparing(Group::fingerprint)).toList();
    }

    private static boolean valid(PreparedGain row) {
        return row != null && row.source() != null && row.assetName() != null && !row.assetName().isBlank()
                && row.assetName().length() <= 50 && row.fingerprint() != null
                && row.fingerprint().matches("[0-9a-f]{64}") && row.salePrice() != null
                && row.proceeds() != null && row.investmentCost() != null;
    }

    private static boolean sameSource(FubonDtos.RealizedGainRow left, FubonDtos.RealizedGainRow right) {
        return left.sourceDate().equals(right.sourceDate()) && left.stockNo().equals(right.stockNo())
                && left.buySell().equals(right.buySell()) && left.orderType().equals(right.orderType())
                && left.filledQty() == right.filledQty()
                && left.filledPrice().value().compareTo(right.filledPrice().value()) == 0
                && left.realizedProfit().value().compareTo(right.realizedProfit().value()) == 0
                && left.realizedLoss().value().compareTo(right.realizedLoss().value()) == 0;
    }

    private static Map<MappingKey, Integer> equivalentCounts(List<RealizedGain> existing) {
        Map<MappingKey, Integer> counts = new HashMap<>();
        for (RealizedGain gain : existing) {
            if (!isManual(gain) && !isValidSync(gain)) continue;
            MappingKey key = MappingKey.of(gain);
            if (key != null) counts.merge(key, 1, Integer::sum);
        }
        return counts;
    }

    private static int consumeEquivalent(Map<MappingKey, Integer> available, MappingKey key, int requested) {
        if (requested <= 0) return 0;
        int consumed = Math.min(requested, available.getOrDefault(key, 0));
        if (consumed == 0) return 0;
        int remaining = available.get(key) - consumed;
        if (remaining == 0) available.remove(key);
        else available.put(key, remaining);
        return consumed;
    }

    private static boolean isManual(RealizedGain gain) {
        return gain.getSyncSource() == null && gain.getSyncFingerprint() == null && gain.getSyncOccurrence() == null;
    }

    private static boolean isValidSync(RealizedGain gain) {
        return FubonRealizedGainFingerprint.SOURCE.equals(gain.getSyncSource())
                && gain.getSyncFingerprint() != null && gain.getSyncFingerprint().matches("[0-9a-f]{64}")
                && gain.getSyncOccurrence() != null && gain.getSyncOccurrence() >= 1;
    }

    private static boolean equivalent(RealizedGain gain, Long ownerId, PreparedGain incoming) {
        return gain != null && Objects.equals(gain.getOwnerUserId(), ownerId)
                && incoming.source().stockNo().equals(gain.getAssetCode())
                && MARKET.equals(gain.getMarket()) && CURRENCY.equals(gain.getCurrency())
                && BROKER.equals(gain.getBroker()) && incoming.source().sourceDate().equals(gain.getTradeDate())
                && numeric(gain.getShares(), BigDecimal.valueOf(incoming.source().filledQty()))
                && numeric(gain.getSalePrice(), incoming.salePrice())
                && scale2(gain.getProceeds(), incoming.proceeds())
                && scale2(gain.getInvestmentCost(), incoming.investmentCost())
                && gain.getExchangeRate() == null;
    }

    private static boolean numeric(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    private static boolean scale2(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.scale() == 2 && right.scale() == 2
                && left.compareTo(right) == 0;
    }

    private static int smallestUnused(Set<Integer> used) {
        for (int occurrence = 1; occurrence > 0; occurrence++) {
            if (!used.contains(occurrence)) return occurrence;
        }
        throw new WriteRejected(FubonRealizedGainOutcome.ROLLED_BACK);
    }

    private static RealizedGain toEntity(Long ownerId, PreparedGain source, int occurrence) {
        return RealizedGain.builder()
                .ownerUserId(ownerId).assetName(source.assetName()).assetCode(source.source().stockNo())
                .market(MARKET).currency(CURRENCY).broker(BROKER).tradeDate(source.source().sourceDate())
                .shares(BigDecimal.valueOf(source.source().filledQty())).salePrice(source.salePrice())
                .proceeds(source.proceeds()).investmentCost(source.investmentCost()).exchangeRate(null)
                .syncSource(FubonRealizedGainFingerprint.SOURCE).syncFingerprint(source.fingerprint())
                .syncOccurrence(occurrence).build();
    }

    record PreparedGain(FubonDtos.RealizedGainRow source, String assetName, BigDecimal salePrice,
                        BigDecimal proceeds, BigDecimal investmentCost, String fingerprint) {}
    private record Group(String fingerprint, List<PreparedGain> rows) {}
    private record MappingKey(String code, LocalDate date, BigDecimal shares, BigDecimal salePrice,
                              BigDecimal proceeds, BigDecimal investmentCost) {
        static MappingKey of(PreparedGain gain) {
            return new MappingKey(gain.source().stockNo(), gain.source().sourceDate(),
                    canonical(BigDecimal.valueOf(gain.source().filledQty())), canonical(gain.salePrice()),
                    gain.proceeds(), gain.investmentCost());
        }
        static MappingKey of(RealizedGain gain) {
            if (gain == null || !MARKET.equals(gain.getMarket()) || !CURRENCY.equals(gain.getCurrency())
                    || !BROKER.equals(gain.getBroker()) || gain.getAssetCode() == null || gain.getTradeDate() == null
                    || gain.getShares() == null || gain.getSalePrice() == null || gain.getProceeds() == null
                    || gain.getInvestmentCost() == null || gain.getExchangeRate() != null
                    || gain.getProceeds().scale() != 2 || gain.getInvestmentCost().scale() != 2) return null;
            return new MappingKey(gain.getAssetCode(), gain.getTradeDate(), canonical(gain.getShares()),
                    canonical(gain.getSalePrice()), gain.getProceeds(), gain.getInvestmentCost());
        }

        private static BigDecimal canonical(BigDecimal value) {
            return value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
        }
    }

    record CommitResult(int insertedCount, int alreadyRepresentedCount) {}

    static final class WriteRejected extends RuntimeException {
        private final FubonRealizedGainOutcome outcome;

        WriteRejected(FubonRealizedGainOutcome outcome) {
            super(outcome.name());
            this.outcome = outcome;
        }

        FubonRealizedGainOutcome outcome() { return outcome; }
    }
}
