package com.steven.assets.externalmaterials.service;

import java.time.LocalDate;
import java.util.List;
import static com.steven.assets.externalmaterials.service.FubonMarketData.*;

public interface FubonMarketDataPort {
    DividendBatch dividends(List<String> symbols, LocalDate queryDate);
    TechnicalRead technical(String symbol, LocalDate queryDate);
    SubscriptionAck subscriptions(List<String> symbols);
}
