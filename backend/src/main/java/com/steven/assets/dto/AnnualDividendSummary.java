package com.steven.assets.dto;

import com.steven.assets.model.AnnualDividendData.*;
import java.time.LocalDate;
import java.util.List;

public record AnnualDividendSummary(int selectedYear, LocalDate asOfDate, List<Integer> availableYears,
                                    List<Market> markets, List<CurrencyTotal> currencyTotals,
                                    List<Row> rows, List<Gap> gaps, int unavailableCount) {
    public AnnualDividendSummary {
        availableYears = List.copyOf(availableYears);
        markets = List.copyOf(markets);
        currencyTotals = List.copyOf(currencyTotals);
        rows = List.copyOf(rows);
        gaps = List.copyOf(gaps);
    }
}
