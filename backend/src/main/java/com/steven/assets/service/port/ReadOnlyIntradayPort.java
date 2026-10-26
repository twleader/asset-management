package com.steven.assets.service.port;

import com.steven.assets.model.marketdata.ReadOnlyIntradayData.ReadOnlyIntradayTicks;
import java.time.LocalDate;
public interface ReadOnlyIntradayPort {
    ReadOnlyIntradayTicks readIntradayTicks(String code, String market, LocalDate date);
}
