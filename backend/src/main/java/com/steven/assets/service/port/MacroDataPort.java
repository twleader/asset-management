package com.steven.assets.service.port;

import com.steven.assets.model.marketdata.MacroSourceData.DailyOhlcDto;
import com.steven.assets.model.marketdata.MacroSourceData.TwseReturnIndexDto;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import com.steven.assets.model.marketdata.IndexIntradayPoint;

/** Fixed internal market-data capabilities; no caller-selected URLs. */
public interface MacroDataPort {
    Map<String, BigDecimal> imf(String indicator, String country, int scale);
    Map<String, Map<String, BigDecimal>> dgbas();
    DailyOhlcDto[] twseMonthly(YearMonth ym);
    TwseReturnIndexDto twseReturnIndex(LocalDate date);
    DailyOhlcDto[] usIndex(String code);
    IndexIntradayPoint[] indexIntraday(String market);
}
