package com.steven.assets.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.dto.MarketAnalysisDto;
import com.steven.assets.dto.MarketAnalysisSettingsDto;
import com.steven.assets.model.DailyMarketAnalysis;
import com.steven.assets.security.AdminRequiredException;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.MarketAnalysisService;
import com.steven.assets.util.MarketZones;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 今日股市分析（Requirement 31）business API。全域參考資料：GET 開放給已登入者；
 * POST /generate 以 {@link CurrentUserContext#isAdmin()} 縱深防禦（BFF 已對外擋一層 ADMIN）。
 */
@RestController
@RequestMapping("/api/market-analysis")
@RequiredArgsConstructor
public class MarketAnalysisController {

    private final MarketAnalysisService analysisService;
    private final CurrentUserContext currentUser;
    private final ObjectMapper objectMapper;

    /** 最近一筆分析；尚無資料回 {status:"NONE"}。 */
    @GetMapping("/today")
    public MarketAnalysisDto today() {
        DailyMarketAnalysis latest = analysisService.latest();
        return latest == null ? MarketAnalysisDto.none() : MarketAnalysisDto.from(latest, objectMapper);
    }

    /** 近 N 筆（analysis_date 降序）。 */
    @GetMapping("/history")
    public List<MarketAnalysisDto> history(@RequestParam(defaultValue = "30") int limit) {
        return analysisService.history(limit).stream()
                .map(a -> MarketAnalysisDto.from(a, objectMapper))
                .toList();
    }

    /** 管理者手動重跑當日分析（同步；web search + thinking 可能耗數十秒）。 */
    @PostMapping("/generate")
    public MarketAnalysisDto generate() {
        if (!currentUser.isAdmin()) {
            throw new AdminRequiredException();
        }
        LocalDate today = LocalDate.now(MarketZones.TW_ZONE);
        DailyMarketAnalysis row = analysisService.generate(today, "manual");
        return MarketAnalysisDto.from(row, objectMapper);
    }

    /** 目前模型設定 + 可選模型清單。已登入者可讀。 */
    @GetMapping("/settings")
    public MarketAnalysisSettingsDto getSettings() {
        return analysisService.getSettings();
    }

    /** 更新分析模型（限管理者；白名單驗證於 service，非法值 → 400）。 */
    @PutMapping("/settings")
    public MarketAnalysisSettingsDto updateSettings(@RequestBody Map<String, String> body) {
        if (!currentUser.isAdmin()) {
            throw new AdminRequiredException();
        }
        return analysisService.updateModel(body == null ? null : body.get("model"));
    }
}
