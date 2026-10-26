package com.steven.assets.service;

import com.steven.assets.service.port.ReadOnlyIntradayPort;
import com.steven.assets.model.marketdata.ReadOnlyIntradayData.ReadOnlyIntradayTicks;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * Requirement 108／Task 372 的 container-only 市場資料 bridge。
 *
 * <p>此 service 不接觸 owner、快照或持股；分時只代理 external 的 exact pure-read
 * endpoint，絕不重用會 cold-start 的 {@code HistoricalDataService.fetchIntradaySession}。</p>
 */
@Slf4j
@Service
public class PublicMarketDataReadOnlyService {

    private final ReadOnlyIntradayPort external;

    public PublicMarketDataReadOnlyService(ReadOnlyIntradayPort external) {
        this.external = external;
    }

    public ReadOnlyIntradayTicks readIntradayTicks(String code, String market, LocalDate date) {
        return external.readIntradayTicks(code, market, date);
    }

}
