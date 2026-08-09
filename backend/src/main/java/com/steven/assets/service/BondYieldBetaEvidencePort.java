package com.steven.assets.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only append-only observation port for per-instrument rate beta evidence.
 * Implementations must not reconstruct historical known-at from mutable current-state tables.
 */
public interface BondYieldBetaEvidencePort {

    record Evidence(
            List<BondYieldBetaResolver.Sample> samples,
            BondYieldBetaResolver.RateSignal rateSignal) {
        public Evidence {
            samples = samples == null ? List.of() : List.copyOf(samples);
        }

        public static Evidence empty() { return new Evidence(List.of(), null); }
    }

    List<BondYieldBetaResolver.Sample> load(BondYieldBetaResolver.Query query);

    default Evidence loadEvidence(BondYieldBetaResolver.Query query) {
        return new Evidence(load(query), null);
    }

    default BondYieldBetaResolver.Result resolve(BondYieldBetaResolver.Query query) {
        Evidence evidence = loadEvidence(query);
        return BondYieldBetaResolver.resolve(query, evidence.samples(), evidence.rateSignal());
    }

    default Map<BondYieldBetaResolver.Query, BondYieldBetaResolver.Result> resolveBatch(
            List<BondYieldBetaResolver.Query> queries) {
        Map<BondYieldBetaResolver.Query, BondYieldBetaResolver.Result> out = new LinkedHashMap<>();
        if (queries == null) return Map.of();
        for (BondYieldBetaResolver.Query query : queries) out.put(query, resolve(query));
        return Map.copyOf(out);
    }
}
