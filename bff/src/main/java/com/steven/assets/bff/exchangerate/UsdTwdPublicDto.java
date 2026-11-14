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
            BigDecimal midRate,
            String rateBasis,
            String source,
            String sourceSha256,
            String sourceMemberSha256,
            @JsonFormat(shape = JsonFormat.Shape.STRING) Instant fetchedAt,
            boolean spotBasisVerified) {
        public HistoryPoint(LocalDate date,BigDecimal buy,BigDecimal sell,BigDecimal mid) {
            this(date,buy,sell,mid,"UNKNOWN",null,null,null,null,false);
        }
    }

    public record SourceCapture(String source,String sourceKind,String sourceSha256,String sourceUrl,
            LocalDate requestedStartDate,LocalDate requestedEndDate,Instant fetchedAt,String rateBasis) {}

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
            List<HistoryPoint> history,
            HistoryPoint historyBoundary,
            List<SourceCapture> historySourceCaptures) {
        public Response(String pair,String base,String quote,LocalDate start,LocalDate end,int interval,String timezone,String status,Spot spot,int count,List<HistoryPoint> history) {
            this(pair,base,quote,start,end,interval,timezone,status,spot,count,history,null,List.of());
        }

        public Response {
            history = history == null ? null : List.copyOf(history);
            historySourceCaptures=historySourceCaptures==null?List.of():List.copyOf(historySourceCaptures);
        }
    }
}
