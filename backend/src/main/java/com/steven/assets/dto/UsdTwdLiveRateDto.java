package com.steven.assets.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** business USD/TWD live endpoint 的 immutable transport contract。 */
public final class UsdTwdLiveRateDto {

    private UsdTwdLiveRateDto() {
    }

    public record Response(
            String pair,
            String baseCurrency,
            String quoteCurrency,
            @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd") LocalDate date,
            BigDecimal buyRate,
            BigDecimal sellRate,
            String source,
            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant polledAt,
            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant sourceUpdatedAt,
            String liveUpdateStatus,
            String quoteStatus) {
    }
}
