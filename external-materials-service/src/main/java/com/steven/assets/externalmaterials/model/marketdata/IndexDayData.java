package com.steven.assets.externalmaterials.model.marketdata;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Immutable market contract shared by core and IO adapters. */
public final class IndexDayData {
    private IndexDayData() {}
    /**
     * 指數當日 5 分 K 摘要（Task 263）：date 為該批點位所屬的交易所當地日期，呼叫端據此守門。
     * 四個數值欄各自獨立判 null（某陣列整日皆 null 時該欄為 null，不影響其餘欄位）。
     */
    public record DayQuote(java.time.LocalDate date, BigDecimal open, BigDecimal high,
                           BigDecimal low, BigDecimal latestClose,
                           java.time.Instant freshnessInstant) {
        /** Source-compatible fixture constructor; production always supplies the final 5m timestamp. */
        public DayQuote(java.time.LocalDate date, BigDecimal open, BigDecimal high,
                        BigDecimal low, BigDecimal latestClose) {
            this(date, open, high, low, latestClose,
                    date == null ? null : date.atStartOfDay(java.time.ZoneId.of("Asia/Taipei")).toInstant());
        }
    }
}
