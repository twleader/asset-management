package com.steven.assets.service.port;

import com.steven.assets.dto.TradingRadarTechnicalBackfillJobDto;

/** Existing bounded technical-history job capabilities; transport and credentials remain outside. */
public interface TechnicalHistoryBackfillPort {
    TradingRadarTechnicalBackfillJobDto start();
    TradingRadarTechnicalBackfillJobDto get(String jobId);
}
