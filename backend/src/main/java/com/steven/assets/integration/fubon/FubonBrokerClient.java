package com.steven.assets.integration.fubon;

import java.time.LocalDate;
import java.util.List;

public interface FubonBrokerClient {
    FubonDtos.CallResult<FubonDtos.PortfolioResponse> readPortfolio();
    FubonDtos.CallResult<FubonDtos.QuoteBatchResponse> readTwQuotes(List<String> codes);
    FubonDtos.CallResult<FubonDtos.TradeBatchResponse> readFilledTrades(LocalDate start, LocalDate end);
    FubonDtos.CallResult<FubonDtos.EtfHoldingsBatchResponse> readEtfHoldings(List<String> codes);
    FubonDtos.CallResult<FubonDtos.BankBalance> readBankBalance();
    FubonDtos.CallResult<FubonDtos.SettlementBatch> readSettlement();
    FubonDtos.CallResult<FubonDtos.RealizedGainBatch> readRealizedGains();
}
