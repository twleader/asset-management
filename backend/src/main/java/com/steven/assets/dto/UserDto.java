package com.steven.assets.dto;

import com.steven.assets.model.AppUser;

import java.time.LocalDateTime;

/**
 * 使用者管理相關 DTO（Requirement 28）。
 */
public class UserDto {

    /** 登入時 upsert：BFF 以 Google 取得的 email/name/picture 呼叫。 */
    public record LoginUpsertRequest(String email, String name, String picture) {}

    public record UserResponse(
            Long id,
            String email,
            String name,
            String picture,
            String role,
            String status,
            boolean protectedAdmin,
            LocalDateTime createdAt
    ) {
        public static UserResponse from(AppUser u, boolean protectedAdmin) {
            return new UserResponse(u.getId(), u.getEmail(), u.getName(), u.getPicture(),
                    u.getRole(), u.getStatus(), protectedAdmin, u.getCreatedAt());
        }
    }

    public record StatusRequest(String status) {}

    public record RoleRequest(String role) {}
}
