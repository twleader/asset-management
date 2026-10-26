package com.steven.assets.externalmaterials.model.marketdata;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Immutable market contract shared by core and IO adapters. */
public final class DividendData {
    private DividendData() {}
    /**
     * @param exDividendDate 除息日（現金股利基準日），ISO yyyy-MM-dd 或 null（Task 357／Requirement 94：
     *                       停止與除權日互相 fallback，只配股事件此欄一律 null，不得以除權日冒充）
     * @param exRightsDate   除權日（股票股利基準日），ISO yyyy-MM-dd 或 null；只配息事件此欄一律 null
     */
    public record DividendEvent(
            Integer year,
            BigDecimal cashDividend,
            BigDecimal stockDividend,
            String exDividendDate,        // ISO YYYY-MM-DD or null
            String exRightsDate,          // ISO YYYY-MM-DD or null
            String cashPaymentDate,       // ISO or null
            String stockPaymentDate       // ISO or null
    ) {}
    public enum FetchStatus { COMPLETE, EMPTY_COMPLETE, PARTIAL, FAILED }
    /**
     * Typed fetch observation。空事件只有在 provider 明確成功完成整段 scope 時才是
     * {@link FetchStatus#EMPTY_COMPLETE}；例外／timeout 不得再與成功查無事件混成同一結果。
     */
    public record DividendFetchResult(
            String source,
            List<DividendEvent> events,
            FetchStatus status,
            LocalDate scopeFrom,
            LocalDate scopeTo,
            Instant sourceAvailableAt,
            String errorReason,
            List<String> sourceUrls) {
        public DividendFetchResult(String source, List<DividendEvent> events,
                                   FetchStatus status, LocalDate scopeFrom, LocalDate scopeTo,
                                   Instant sourceAvailableAt, String errorReason) {
            this(source, events, status, scopeFrom, scopeTo, sourceAvailableAt, errorReason, List.of());
        }

        public DividendFetchResult {
            events = events == null ? List.of() : List.copyOf(events);
            sourceUrls = sourceUrls == null ? List.of() : sourceUrls.stream()
                    .filter(url -> url != null && !url.isBlank()).distinct().toList();
        }

        public DividendFetchResult(String source, List<DividendEvent> events) {
            this(source, events, FetchStatus.PARTIAL,
                    LocalDate.now().minusYears(1), LocalDate.now(), Instant.now(),
                    "legacy result 未提供可驗證的 upcoming scope", List.of());
        }

        public boolean complete() {
            return status == FetchStatus.COMPLETE || status == FetchStatus.EMPTY_COMPLETE;
        }
    }
}
