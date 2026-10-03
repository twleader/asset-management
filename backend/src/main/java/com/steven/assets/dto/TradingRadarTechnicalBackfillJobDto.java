package com.steven.assets.dto;

import java.util.List;

public record TradingRadarTechnicalBackfillJobDto(String jobId, String status, String createdAt,
        String completedAt, int symbols, int completedSymbols, String currentSymbol,
        String currentFrom, String currentTo, int windows, int availableWindows,
        int factRows, int profileResults, String reason, List<Coverage> coverage) {
    public record Coverage(String symbol, String profileId, String status, String reason, String depthStatus,
            String firstSourceDate, String lastSourceDate, int rows, String lastQueryFrom, String lastQueryTo) {}
}
