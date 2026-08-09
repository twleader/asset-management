package com.steven.assets.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Application pipeline for one dividend decision instant.
 *
 * <p>The projection is attempted first so all later radar reads in the same stock
 * build see the latest authoritative ACTIVE/CANCELLED state.  A missing complete
 * snapshot is a normal no-op, while projection infrastructure failures are
 * fail-soft: append-only evidence resolution and the pre-existing history remain
 * readable.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DividendEventEvidencePipeline implements DividendEventEvidenceRepository {

    private final DividendCurrentStateProjectionService projectionService;
    private final DividendEventEvidenceStore store;

    @Override
    public DividendEventEvidenceResolver.Resolution resolve(
            String code, String market, Instant decisionInstant, List<LocalDate> sessions) {
        try {
            projectionService.projectOne(code, market, decisionInstant);
        } catch (RuntimeException e) {
            log.warn("dividend current-state projection 失敗，沿用既有 history：{} {}: {}",
                    market, code, e.getMessage());
        }
        return store.resolve(code, market, decisionInstant, sessions);
    }

    /**
     * Historical/bulk evidence resolution is deliberately projection-free.  In
     * particular, a backtest signal instant must never rewrite today's mutable
     * {@code stock_dividend_history} projection.
     */
    @Override
    public List<DividendEventEvidenceBatch.Result> resolveBatch(
            List<DividendEventEvidenceBatch.Query> queries) {
        return store.resolveBatch(queries);
    }
}
