package com.steven.assets.dto;

import java.util.List;

/** 每檔交易雷達狀態 Email 通知設定 API（Requirement 44）。 */
public final class TradingRadarNotificationDto {

    private TradingRadarNotificationDto() {}

    public record Request(
            Boolean active,
            List<String> actionStates,
            List<String> counterTrendStates,
            List<Long> recipientIds
    ) {}

    public record Option(String code, String label) {}

    public record RecipientOption(Long id, String email, boolean active) {}

    public record Response(
            String stockCode,
            String market,
            boolean configured,
            boolean active,
            List<String> actionStates,
            List<String> counterTrendStates,
            List<Long> recipientIds,
            List<Option> actionOptions,
            List<Option> counterTrendOptions,
            List<RecipientOption> recipients
    ) {}
}
