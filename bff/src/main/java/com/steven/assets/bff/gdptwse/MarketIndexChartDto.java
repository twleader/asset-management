package com.steven.assets.bff.gdptwse;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 公開股市大盤圖表 API 的 immutable response DTO。 */
public final class MarketIndexChartDto {

    private MarketIndexChartDto() {
    }

    public record Option(String value, String label) {
    }

    public record Response(
            String market,
            String marketLabel,
            String range,
            String rangeLabel,
            String mode,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate tradingDate,
            List<String> labels,
            List<BigDecimal> closes,
            List<BigDecimal> ma5,
            List<BigDecimal> ma20,
            List<BigDecimal> ma60,
            List<BigDecimal> ma240,
            List<Object> volumes,
            List<BigDecimal> turnovers,
            boolean hasVolume,
            BigDecimal previousClose,
            BigDecimal lastClose,
            BigDecimal change,
            BigDecimal changePercent,
            List<Option> supportedMarkets,
            List<Option> supportedRanges) {

        public Response {
            labels = immutableCopy(labels);
            closes = immutableCopy(closes);
            ma5 = immutableCopy(ma5);
            ma20 = immutableCopy(ma20);
            ma60 = immutableCopy(ma60);
            ma240 = immutableCopy(ma240);
            volumes = immutableCopy(volumes);
            turnovers = immutableCopy(turnovers);
            supportedMarkets = immutableCopy(supportedMarkets);
            supportedRanges = immutableCopy(supportedRanges);
        }
    }

    /**
     * {@link List#copyOf(java.util.Collection)} 會拒絕合法的 null 圖表點，故一律採可含 null 的防禦複製。
     */
    private static <T> List<T> immutableCopy(List<? extends T> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(source));
    }
}
