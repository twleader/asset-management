package com.steven.assets.service.port;

import com.steven.assets.dto.QuoteDetailDto;
import com.steven.assets.model.marketdata.MarketDataResults.DividendRateResult;
import com.steven.assets.model.marketdata.MarketDataResults.EtfHoldingsResult;
import com.steven.assets.model.marketdata.MarketDataResults.DividendHistoryResult;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Fixed internal market-data capabilities; no caller-selected URLs. */
public interface MarketDataReadPort {
    QuoteDetailDto.Response quoteDetail(String stockCode, String market);
    DividendRateResult dividendRate(String stockCode, String market);
    EtfHoldingsResult etfHoldings(String stockCode, String market);
    DividendHistoryResult dividendHistory(String stockCode, String market, int years);
    Boolean detectTwClosure();
    Map<String, String> twHolidays(int year);
}
