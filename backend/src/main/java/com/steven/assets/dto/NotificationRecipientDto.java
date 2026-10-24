package com.steven.assets.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 警示觸發 Email 通知收件人 DTO（Requirement 23）。
 */
public class NotificationRecipientDto {

    public record Response(
            Long id,
            String email,
            String name,
            Boolean active,
            Boolean receiveMarketAnalysis,
            Boolean addToCalendar,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {}

    public record CreateRequest(
            @NotBlank @Email String email,
            @Size(max = 100) String name,
            Boolean active
    ) {}

    public record UpdateRequest(
            @NotBlank @Email String email,
            @Size(max = 100) String name
    ) {}
}
