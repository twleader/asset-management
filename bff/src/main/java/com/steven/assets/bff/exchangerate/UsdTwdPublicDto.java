package com.steven.assets.bff.exchangerate;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** 公開 USD/TWD endpoint 的 immutable response records。 */
public final class UsdTwdPublicDto {

    private UsdTwdPublicDto() {
    }

    public record HistoryPoint(
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate date,
            BigDecimal buyRate,
            BigDecimal sellRate,
            BigDecimal midRate) {
    }

    public record Spot(
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate date,
            BigDecimal buyRate,
            BigDecimal sellRate,
            BigDecimal midRate,
            String source,
            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant polledAt,
            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant sourceUpdatedAt,
            String quoteStatus) {
    }

    public record Response(
            String pair,
            String baseCurrency,
            String quoteCurrency,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate requestedStartDate,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate requestedEndDate,
            int refreshIntervalSeconds,
            String timezone,
            String liveUpdateStatus,
            Spot spot,
            int count,
            List<HistoryPoint> history) {

        public Response {
            history = history == null ? null : List.copyOf(history);
        }
    }
}
