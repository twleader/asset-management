package com.steven.assets.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.time.LocalDateTime;

/**
 * 警示觸發 Email 通知收件人 DTO（Requirement 23）。
 */
public class NotificationRecipientDto {

    public record Response(
            Long id,
            String email,
            Boolean active,
            Boolean receiveMarketAnalysis,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {}

    public record CreateRequest(
            @NotBlank @Email String email,
            Boolean active
    ) {}

    public record UpdateRequest(
            @NotBlank @Email String email
    ) {}
}
