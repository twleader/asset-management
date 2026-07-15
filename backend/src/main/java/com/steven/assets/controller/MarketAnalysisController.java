package com.steven.assets.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.dto.MarketAnalysisDto;
import com.steven.assets.dto.MarketAnalysisSettingsDto;
import com.steven.assets.model.DailyMarketAnalysis;
import com.steven.assets.security.AdminRequiredException;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.MarketAnalysisSendTimeService;
import com.steven.assets.service.MarketAnalysisService;
import com.steven.assets.util.MarketZones;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
    private final MarketAnalysisSendTimeService sendTimeService;
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

    /** 目前設定（模型 + 思考深度）+ 各自可選清單。已登入者可讀。 */
    @GetMapping("/settings")
    public MarketAnalysisSettingsDto getSettings() {
        return analysisService.getSettings();
    }

    /** 更新分析模型／思考深度／每日自動分析開關（限管理者；白名單驗證於 service，非法值 → 400）。body 可含 model、effort、enabled 之任意組合。 */
    @PutMapping("/settings")
    public MarketAnalysisSettingsDto updateSettings(@RequestBody Map<String, Object> body) {
        if (!currentUser.isAdmin()) {
            throw new AdminRequiredException();
        }
        return analysisService.updateSettings(
                str(body, "model"),
                str(body, "effort"),
                boolOrNull(body, "enabled"));
    }

    // ===== 分析寄送時間（Task 191）：GET 開放已登入者；新增／刪除／切換啟用限管理者（縱深防禦） =====

    /** 分析寄送時間清單（`[{id,time,active}]`，升序）。已登入者可讀。 */
    @GetMapping("/send-times")
    public List<MarketAnalysisSettingsDto.SendTime> sendTimes() {
        return sendTimeService.list();
    }

    /** 新增寄送時間（限管理者）；body `{time:"HH:mm"}`。格式／唯一性驗證於 service（非法 → 400）。回更新後清單。 */
    @PostMapping("/send-times")
    public List<MarketAnalysisSettingsDto.SendTime> addSendTime(@RequestBody Map<String, Object> body) {
        if (!currentUser.isAdmin()) {
            throw new AdminRequiredException();
        }
        return sendTimeService.add(str(body, "time"));
    }

    /** 刪除寄送時間（限管理者）。回更新後清單。 */
    @DeleteMapping("/send-times/{id}")
    public List<MarketAnalysisSettingsDto.SendTime> deleteSendTime(@PathVariable Long id) {
        if (!currentUser.isAdmin()) {
            throw new AdminRequiredException();
        }
        return sendTimeService.delete(id);
    }

    /** 切換寄送時間啟用／停用（限管理者）。回更新後清單。 */
    @PatchMapping("/send-times/{id}/active")
    public List<MarketAnalysisSettingsDto.SendTime> toggleSendTime(@PathVariable Long id) {
        if (!currentUser.isAdmin()) {
            throw new AdminRequiredException();
        }
        return sendTimeService.toggleActive(id);
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null ? null : v.toString();
    }

    /** body 值 → Boolean（JSON boolean 或字串 "true"/"false" 皆可）。 */
    private static Boolean boolOrNull(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.valueOf(v.toString().trim());
    }
}
