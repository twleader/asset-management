package com.steven.assets.service;

import com.steven.assets.dto.HistoryExport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Keeps distinct query, refresh, intraday and export index admission policies. */
@Service
@RequiredArgsConstructor
public class MacroHistoryApplicationService {
    private static final Set<String> CODED_INDEX_CODES = Set.copyOf(MacroHistoryService.PAGE_CODED_INDEX_CODES);
    private static final Set<String> REFRESH_CODES = java.util.stream.Stream
            .concat(MacroHistoryService.PAGE_CODED_INDEX_CODES.stream(), MacroHistoryService.TOTAL_RETURN_US_INDEX_CODES.stream())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
    private final MacroHistoryService macro;
    private final ExcelExportService excel;

    public Map<String, Object> refreshUsDaily(String code) {
        if (!REFRESH_CODES.contains(code)) return Map.of("error", "未知指數代碼: " + code, "upserted", 0);
        return macro.refreshUsIndexDaily(code);
    }
    public List<MacroHistoryService.IntradayPoint> intraday(String market) {
        if (!"TWSE".equals(market) && !CODED_INDEX_CODES.contains(market))
            throw new IllegalArgumentException("未知指數代碼: " + market);
        return macro.fetchIndexIntraday(market);
    }
    public HistoryExport exportIndex(String market, LocalDate start, LocalDate end) throws java.io.IOException {
        String code = market == null ? "" : market.trim().toUpperCase();
        if (!MacroHistoryService.DAILY_INDEX_CODES.contains(code)) throw new IllegalArgumentException("未知指數代碼: " + market);
        if (start == null) start = LocalDate.now().minusYears(10);
        if (end == null) end = LocalDate.now();
        if (start.isAfter(end)) throw new IllegalArgumentException("起始日不可晚於結束日");
        return new HistoryExport(excel.exportIndexDaily(code, start, end), code, start, end);
    }
}
