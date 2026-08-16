package com.steven.assets.dto;

import java.util.List;

/**
 * 今日股市分析（Requirement 31）設定 DTO：目前分析引擎（engine，Task 337）+ 模型 + 思考深度（effort）
 * + 每日自動分析開關（enabled）+ 可設定的分析寄送時間（sendTimes，Task 191），及各自可選清單。
 * （Task 179 起新聞固定讀本地 {@code news_headline}，已移除新聞搜尋次數 web search 設定。）
 *
 * <p>{@code engine=local} 時 {@code model}／{@code effort} 仍為有效的 {@code llm} 模式設定值，
 * 只是本次不生效——前端據此把兩個下拉停用而非隱藏。</p>
 */
public record MarketAnalysisSettingsDto(
        String engine,
        String model,
        String effort,
        Boolean enabled,
        List<ModelOption> availableModels,
        List<EffortOption> availableEfforts,
        List<EngineOption> availableEngines,
        List<SendTime> sendTimes
) {
    public record ModelOption(String id, String label) {}

    public record EffortOption(String id, String label) {}

    /** 分析引擎選項（Task 337）：{@code local}＝本機規則引擎（免費）、{@code llm}＝Claude（付費）。 */
    public record EngineOption(String id, String label) {}

    /** 一個分析寄送時間點（Task 191）：{@code time} 為 {@code HH:mm}（Asia/Taipei）。 */
    public record SendTime(Long id, String time, Boolean active) {}
}
