package com.steven.assets.service;

import com.steven.assets.model.StockPriceHistory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Completed daily market facts only. The returned volume uses the persisted source unit. */
@Service
@RequiredArgsConstructor
public class SrppTechnicalSeriesService {
    public static final String FORMULA_VERSION = "SRPP_TECHNICAL_SERIES_V1";
    public static final String VOLUME_UNIT = "SOURCE_UNIT_UNVERIFIED";

    private final SrppCompletedTechnicalService completed;
    private final TechnicalIndicatorService technicalIndicators;

    public record DailyBar(
            LocalDate tradingDate, BigDecimal open, BigDecimal high, BigDecimal low,
            BigDecimal close, Long rawVolume, Long volume, String closeSource,
            BigDecimal obv20Change) {}

    public record AdditionalIndicators(
            BigDecimal ma10, BigDecimal ma20, BigDecimal ma60, BigDecimal ma240,
            BigDecimal k9, BigDecimal d9, BigDecimal previousK9, BigDecimal previousD9,
            BigDecimal j9, BigDecimal k3d2, BigDecimal rsv9,
            BigDecimal ema12, BigDecimal ema26, BigDecimal dif,
            BigDecimal macd, BigDecimal osc, BigDecimal rsi5, BigDecimal rsi10,
            BigDecimal bias10, BigDecimal bias20, BigDecimal b10b20, BigDecimal wr9) {}

    public record Response(
            String formulaVersion, String market, String stockCode, LocalDate asOf,
            int requestedBars, String volumeUnit,
            SrppCompletedTechnicalService.SymbolFacts summary,
            AdditionalIndicators additionalIndicators, List<DailyBar> dailyBars) {}

    @Transactional(readOnly = true)
    public Response read(String market, String code, LocalDate asOf, int bars) {
        var loaded = completed.loadOne(code, market, asOf);
        if (loaded.adjustedRows().isEmpty()) {
            return new Response(FORMULA_VERSION, market, code, asOf, bars, VOLUME_UNIT,
                    loaded.facts(), null, List.of());
        }
        List<StockPriceHistory> adjusted = loaded.adjustedRows();
        List<StockPriceHistory> raw = loaded.rawRows();
        int from = Math.max(0, adjusted.size() - bars);
        List<DailyBar> dailyBars = new ArrayList<>(adjusted.size() - from);
        for (int i = from; i < adjusted.size(); i++) {
            StockPriceHistory row = adjusted.get(i);
            StockPriceHistory source = raw.get(i);
            dailyBars.add(new DailyBar(row.getTradingDate(),
                    validPrice(row.getOpenPrice()), validPrice(row.getHighPrice()),
                    validPrice(row.getLowPrice()), validPrice(row.getClosePrice()),
                    validVolume(source.getVolume()), validVolume(row.getVolume()),
                    source.getCloseSource(),
                    SrppCompletedTechnicalCalculator.obv20ChangeAt(adjusted, i + 1)));
        }
        List<StockPriceHistory> desc = new ArrayList<>(adjusted);
        Collections.reverse(desc);
        var base = technicalIndicators.computeFromSeries(desc);
        var ext = base.extended();
        var additional = new AdditionalIndicators(base.ma10(), base.monthlyMa(),
                base.quarterlyMa(), base.annualMa(), base.k(), base.d(),
                base.previousK(), base.previousD(), ext.j9(), ext.k3d2(), ext.rsv(),
                ext.ema12(), ext.ema26(), ext.dif(), ext.macd(), ext.osc(),
                ext.rsi5(), ext.rsi10(), ext.bias10(), ext.bias20(), ext.b10b20(), ext.wr9());
        return new Response(FORMULA_VERSION, market, code, asOf, bars, VOLUME_UNIT,
                loaded.facts(), additional, List.copyOf(dailyBars));
    }

    private static BigDecimal validPrice(BigDecimal value) {
        return value == null || value.signum() <= 0 ? null : value;
    }

    private static Long validVolume(Long value) {
        return value == null || value < 0 ? null : value;
    }
}
