package com.steven.assets.externalmaterials.service.port;

import com.steven.assets.externalmaterials.model.marketdata.IndexDayData;

/** Narrow market capability implemented by the existing IO adapter. */
public interface IndexIntradaySourcePort {
    IndexDayData.DayQuote fetchIndexIntradayDay(String market);
}
