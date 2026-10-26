package com.steven.assets.service.port;

import com.steven.assets.model.marketdata.HistoricalSourceData.IntradayBarDto;
import com.steven.assets.model.marketdata.HistoricalSourceData.IntradaySourceSessionDto;
import com.steven.assets.model.marketdata.HistoricalSourceData.LiveRefreshDto;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import com.steven.assets.model.marketdata.IndexIntradayPoint;

/** Fixed internal market-data capabilities; no caller-selected URLs. */
public interface HistoricalDataPort {
    IntradaySourceSessionDto intradaySession(String stockCode, String market, LocalDate date, boolean readOnly);
    IntradayBarDto[] intraday5m(String stockCode, String market, int daysBack);
    Map<String, Object> backfillStock(String stockCode, String market, LocalDate since, LocalDate until);
    Map<String, Object> backfillAll();
    Map<String, Object> backfillExchangeRate(String currency, LocalDate since);
    Map<String, Object> backfillExchangeRateFrom(String currency, LocalDate since);
    Map<String, Object> backfillCommodity(String code, LocalDate since);
    Map<String, Object> backfillCommodityFrom(String code, LocalDate since);
    void refreshExchangeRateBot(String currency);
    LiveRefreshDto refreshCommodities();
    Map<String, String> stockName(String code, String market);
}
