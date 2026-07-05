package com.steven.assets.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * 資產配置建議（Requirement 32）——Claude 回傳 JSON 的解析目標。
 * 忽略未知欄位（模型可能多帶說明性欄位）；缺欄位為 null。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PortfolioAdviceResult(
        String summary,                          // 整體評析（繁中一段）
        String riskAssessment,                   // 風險與現況評估
        List<TargetAllocation> targetAllocation, // 建議目標配置
        List<Action> actions,                    // 具體調整動作
        List<String> warnings,                   // 風險提醒
        List<Reference> references               // 參考來源（web_search）
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TargetAllocation(
            String assetClass,   // 資產類別（如 現金/存款、債券、台股、海外股票、基金）
            BigDecimal targetPct, // 建議目標比例（%）
            String rationale     // 理由
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Action(
            String title,    // 動作標題
            String detail,   // 說明
            String priority  // HIGH / MEDIUM / LOW
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Reference(
            String title,
            String url
    ) {}
}
