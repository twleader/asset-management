package com.steven.assets.externalmaterials.service;

import java.time.LocalDate;
import java.util.List;
import static com.steven.assets.externalmaterials.service.FubonMarketData.*;

public interface FubonMarketDataPort {
    DividendBatch dividends(List<String> symbols, LocalDate queryDate);
    TechnicalRead technical(String symbol, LocalDate queryDate);
    /** Task408 strict v2 technical history; the old v1 method remains only for frozen cache readers. */
    default TechnicalBundle technicalV2(String symbol, LocalDate queryDate) {
        throw new Unavailable("TECHNICAL_V2_UNAVAILABLE");
    }
    default StockBasicRead basic(String symbol, LocalDate queryDate) {
        throw new Unavailable("STOCK_BASIC_UNAVAILABLE");
    }
    default IntradayCandlesRead candles(String symbol, LocalDate queryDate) {
        throw new Unavailable("INTRADAY_CANDLES_UNAVAILABLE");
    }
    SubscriptionAck subscriptions(List<String> symbols);
}
