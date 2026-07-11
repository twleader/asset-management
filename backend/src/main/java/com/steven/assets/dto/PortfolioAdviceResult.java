package com.steven.assets.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;

/**
 * 資產配置建議（Requirement 32）——Claude 回傳 JSON 的解析目標。
 * 忽略未知欄位（模型可能多帶說明性欄位）；缺欄位為 null。
 *
 * <p>Task 165 起：{@code targetAllocation} 除比例外帶「目前該類金額 / 目標金額 / 差額」，
 * 另新增 {@code rebalancePlan}（逐標的增減碼具體金額）——讓建議由「只有比例」進化為「可執行的新台幣操作」。
 * 其中 {@code targetAmount}／{@code deltaAmount} 由後端以「資產總額 × 目標%」決定性回填（LLM 只給比例與分類），
 * 金額算術不交給 LLM。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PortfolioAdviceResult(
        String summary,                          // 整體評析（繁中一段）
        String riskAssessment,                   // 風險與現況評估
        List<TargetAllocation> targetAllocation, // 建議目標配置
        List<Rebalance> rebalancePlan,           // 逐標的再平衡操作（增/減碼＋金額）
        List<Action> actions,                    // 具體調整動作（非金額類步驟）
        List<String> warnings,                   // 風險提醒
        List<Reference> references               // 參考來源（web_search）
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TargetAllocation(
            String assetClass,      // 資產類別（如 現金/存款、債券、台股、海外股票、基金）
            BigDecimal targetPct,   // 建議目標比例（%）
            BigDecimal currentValue,// 目前歸屬此類的資產金額（LLM 依持有明細分類；估）
            BigDecimal targetAmount,// 目標金額（後端＝資產總額 × targetPct，決定性回填）
            BigDecimal deltaAmount, // 差額＝targetAmount − currentValue（後端回填；正=增碼、負=減碼）
            String rationale        // 理由
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Rebalance(
            String assetClass,        // 所屬類別
            String holding,           // 標的（個股代號／基金名稱／存款；或「整體」）
            String action,            // BUY（增碼/買進）/ SELL（減碼/賣出）/ HOLD（維持）
            BigDecimal estimatedAmount,// 估計操作金額（新台幣，正數）
            String rationale          // 理由
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
