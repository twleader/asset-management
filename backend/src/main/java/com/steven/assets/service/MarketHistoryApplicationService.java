package com.steven.assets.service;

import com.steven.assets.dto.HistoryExport;
import com.steven.assets.model.ExchangeRateHistory;
import com.steven.assets.model.CommodityPriceHistory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** Application policy shared by market-history HTTP operations. */
@Service
@RequiredArgsConstructor
public class MarketHistoryApplicationService {
    private final HistoricalDataService history;
    private final MarketDataService market;
    private final ExcelExportService excel;

    public HistoricalDataService.IntradaySession intradaySession(String code, String market, LocalDate date, boolean readOnly) {
        return readOnly ? history.fetchIntradaySession(code, market, date, true)
                : history.fetchIntradaySession(code, market, date);
    }
    public Map<String, Object> holidays(int year) {
        return Map.of("tw", market.getTwHolidays(year), "us", market.getUsHolidays(year), "uk", market.getUkHolidays(year));
    }
    public List<ExchangeRateHistory> exchangeRates(String currency, LocalDate start, LocalDate end) {
        if (start == null && end == null) return history.getAllExchangeRates(currency);
        if (start == null) start = LocalDate.now().minusYears(10);
        if (end == null) end = LocalDate.now();
        return history.getExchangeRateHistory(currency, start, end);
    }
    public Map<String, Object> refreshExchangeRate(String currency) {
        int backfilled = history.backfillExchangeRate(currency, LocalDate.now().minusDays(10));
        history.fetchBotExchangeRate(currency);
        history.purgeOldExchangeRates(currency, 10);
        return Map.of("backfilled", backfilled, "currency", currency, "botFetched", true);
    }
    public Map<String, Object> backfillExchangeRate(String currency, LocalDate since) {
        if (since == null) since = LocalDate.now().minusYears(10);
        int count = history.backfillExchangeRateFrom(currency, since);
        return Map.of("backfilled", count, "currency", currency, "since", since.toString());
    }
    public Map<String, List<CommodityPriceHistory>> commodities(LocalDate start, LocalDate end) {
        LocalDate[] range = normalizeTenYearRange(start, end);
        return history.getCommodityHistory(range[0], range[1]);
    }
    public Map<String, Object> refreshCommodities() {
        return Map.of("backfilled", history.refreshCommodities());
    }
    public HistoryExport exportExchangeRates(String currency, LocalDate start, LocalDate end) throws java.io.IOException {
        LocalDate[] range = normalizeTenYearRange(start, end);
        return new HistoryExport(excel.exportExchangeRates(currency, range[0], range[1]), currency, range[0], range[1]);
    }
    public HistoryExport exportCommodities(LocalDate start, LocalDate end) throws java.io.IOException {
        LocalDate[] range = normalizeTenYearRange(start, end);
        return new HistoryExport(excel.exportCommodityPrices(range[0], range[1]), null, range[0], range[1]);
    }
    private LocalDate[] normalizeTenYearRange(LocalDate start, LocalDate end) {
        if (start == null) start = LocalDate.now().minusYears(10);
        if (end == null) end = LocalDate.now();
        if (start.isAfter(end)) throw new IllegalArgumentException("起始日期不可晚於結束日期：" + start + " > " + end);
        return new LocalDate[] {start, end};
    }
}
