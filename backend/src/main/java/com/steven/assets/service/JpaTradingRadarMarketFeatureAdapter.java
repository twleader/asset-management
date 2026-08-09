package com.steven.assets.service;

import com.steven.assets.model.CommodityPriceHistory;
import com.steven.assets.model.TwseIndexDailyHistory;
import com.steven.assets.model.UsIndexDailyHistory;
import com.steven.assets.repository.CommodityPriceHistoryRepository;
import com.steven.assets.repository.TwseInstitutionalDailyRepository;
import com.steven.assets.repository.TwseIndexDailyHistoryRepository;
import com.steven.assets.repository.UsIndexDailyHistoryRepository;
import com.steven.assets.util.MarketZones;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JPA adapter for numeric index, commodity and typed TWSE institutional tables.
 *
 * <p>The legacy rows are date-only.  This adapter does not invent timestamps;
 * the pure resolver applies the conservative boundaries prescribed by t307.7.
 * Institutional rows come only from the append-only BFI82U numeric observation table;
 * this adapter never reads or parses news text.</p>
 */
@Component
@RequiredArgsConstructor
public class JpaTradingRadarMarketFeatureAdapter implements TradingRadarMarketFeaturePort {

    private static final List<String> US_INDEX_CODES = List.of("IXIC", "SOX", "SPX", "DJI");
    private static final List<String> COMMODITY_CODES = List.of("WTI", "BRENT", "GOLD");

    private final UsIndexDailyHistoryRepository usIndexRepository;
    private final TwseIndexDailyHistoryRepository twseIndexRepository;
    private final CommodityPriceHistoryRepository commodityRepository;
    private final TwseInstitutionalDailyRepository institutionalRepository;

    @Override
    public TradingRadarMarketFeatureResolver.Sources load(
            String market, Instant decisionInstant) {
        if (market == null || decisionInstant == null) {
            return TradingRadarMarketFeatureResolver.Sources.empty();
        }
        return loadRange(market, decisionInstant.minusSeconds(366L * 24 * 60 * 60 * 2),
                decisionInstant);
    }

    @Override
    public TradingRadarMarketFeatureResolver.Sources loadRange(
            String market, Instant earliestDecision, Instant latestDecision) {
        if (market == null || earliestDecision == null || latestDecision == null) {
            return TradingRadarMarketFeatureResolver.Sources.empty();
        }
        LocalDate to = latestDecision.atZone(MarketZones.resolve(market)).toLocalDate();
        // Need only 21 prior sessions, but a full year is a conservative bounded lookback.
        LocalDate from = earliestDecision.atZone(MarketZones.resolve(market))
                .toLocalDate().minusYears(1);
        List<UsIndexDailyHistory> us = new ArrayList<>();
        for (String code : US_INDEX_CODES) {
            List<UsIndexDailyHistory> rows = usIndexRepository
                    .findByIndexCodeAndTradingDateBetweenOrderByTradingDateAsc(code, from, to);
            if (rows != null) us.addAll(rows);
        }
        List<TwseIndexDailyHistory> tw = twseIndexRepository
                .findByTradingDateBetweenOrderByTradingDateAsc(from, to);
        Map<String, List<CommodityPriceHistory>> commodities = new LinkedHashMap<>();
        for (String code : COMMODITY_CODES) {
            List<CommodityPriceHistory> rows = commodityRepository
                    .findByCommodityCodeAndPriceDateBetweenOrderByPriceDateAsc(code, from, to);
            commodities.put(code, rows == null ? List.of() : rows);
        }
        var institutional = institutionalRepository.findVisibleRange(from, to, latestDecision);
        return new TradingRadarMarketFeatureResolver.Sources(
                us,
                tw == null ? List.of() : tw,
                commodities,
                institutional == null ? List.of() : institutional);
    }
}
