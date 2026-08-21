package com.steven.assets.integration.fubon;

import java.util.List;

public interface FubonBrokerClient {
    FubonDtos.CallResult<FubonDtos.PortfolioResponse> readPortfolio();
    FubonDtos.CallResult<FubonDtos.QuoteBatchResponse> readTwQuotes(List<String> codes);
}
