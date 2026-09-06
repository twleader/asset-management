package com.steven.assets.integration.fubon;

import com.steven.assets.service.StockMasterService;
import com.steven.assets.service.fubon.FubonLocalNamePort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class FubonLocalNameAdapter implements FubonLocalNamePort {
    private static final String TAIWAN_MARKET = "台股";

    private final StockMasterService stockMasterService;

    @Autowired
    public FubonLocalNameAdapter(StockMasterService stockMasterService) {
        this.stockMasterService = stockMasterService;
    }

    @Override
    public String resolveTaiwanStockName(String stockCode) {
        return stockMasterService.resolveNameLocalOnly(stockCode, TAIWAN_MARKET);
    }
}
