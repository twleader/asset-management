package com.steven.assets.model.marketdata;

import java.math.BigDecimal;
import java.util.List;

/** Shared immutable market facts, independent of HTTP adapters. */
public final class MarketDataResults {
    private MarketDataResults() {}
    public record DividendRateResult(
            String stockCode, String market, BigDecimal dividendRate,
            String source, String description, String stockName) {
        public DividendRateResult(String stockCode, String market, BigDecimal dividendRate,
                                  String source, String description) {
            this(stockCode, market, dividendRate, source, description, null);
        }
    }

    public record EtfHolding(String stockCode, String stockName, BigDecimal weight, BigDecimal shares) {}

    public record EtfHoldingsResult(
            String stockCode, String market, boolean supported, String source,
            String asOfDate, String message, List<EtfHolding> holdings) {}

    public record DividendRow(
            Integer year, BigDecimal cashDividend, BigDecimal stockDividend,
            String exDividendDate, BigDecimal yieldPct,
            String cashPaymentDate, String stockPaymentDate,
            Integer fillDays, BigDecimal previousClose,
            /**
             * 除權日（Task 357／Requirement 94），ISO {@code yyyy-MM-dd} 或 null。
             * 追加在既有欄位之後，供下游 bff／frontend 計算
             * anchorDate = {@code exDividendDate ?? exRightsDate}（357.3e-1／357.3e-2）。
             */
            String exRightsDate, String quality, String reason, String source, String observedAt) {
        public DividendRow(Integer year, BigDecimal cashDividend, BigDecimal stockDividend, String exDividendDate,
                           BigDecimal yieldPct, String cashPaymentDate, String stockPaymentDate, Integer fillDays,
                           BigDecimal previousClose, String exRightsDate) {
            this(year,cashDividend,stockDividend,exDividendDate,yieldPct,cashPaymentDate,stockPaymentDate,fillDays,previousClose,exRightsDate,"UNRESOLVED","舊傳輸資料未含可驗品質",null,null);
        }
        /** Compatibility constructor：357 之前既有的完整形狀（不含除權日）。 */
        public DividendRow(Integer year, BigDecimal cashDividend, BigDecimal stockDividend,
                           String exDividendDate, BigDecimal yieldPct,
                           String cashPaymentDate, String stockPaymentDate,
                           Integer fillDays, BigDecimal previousClose) {
            this(year, cashDividend, stockDividend, exDividendDate, yieldPct,
                    cashPaymentDate, stockPaymentDate, fillDays, previousClose, null);
        }

        public DividendRow(Integer year, BigDecimal cashDividend, BigDecimal stockDividend,
                           String exDividendDate, BigDecimal yieldPct) {
            this(year, cashDividend, stockDividend, exDividendDate, yieldPct, null, null, null, null);
        }
    }

    public record DividendHistoryResult(
            String stockCode, String market, String source, String message, List<DividendRow> rows,
            List<DividendAnnualSummary> annualSummaries, String quality) {
        public DividendHistoryResult { rows=rows==null?List.of():List.copyOf(rows); annualSummaries=annualSummaries==null?List.of():List.copyOf(annualSummaries); quality=quality==null?"UNRESOLVED":quality; }
        public DividendHistoryResult(String stockCode,String market,String source,String message,List<DividendRow> rows) {
            this(stockCode,market,source,message,rows,List.of(),"UNRESOLVED");
        }
    }
    public record DividendAnnualSummary(Integer year, BigDecimal cashDividend, BigDecimal stockDividend,
                                        BigDecimal yieldPct, int eventCount, int unavailableCount, String quality) {}
}
