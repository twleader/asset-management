package com.steven.assets.service;

import com.steven.assets.dto.UserDto;
import com.steven.assets.model.AppUser;
import com.steven.assets.repository.AppUserRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserAdminServiceTest {

    private final AppUserRepository userRepo = mock(AppUserRepository.class);

    @Test
    void configuredAdminIsNormalizedAndCreatedActive() {
        UserAdminService service = service("  Owner@Example.COM  ");
        when(userRepo.findByEmail("owner@example.com")).thenReturn(Optional.empty());
        when(userRepo.save(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AppUser result = service.loginUpsert("OWNER@example.com", "Owner", null);

        assertThat(result.getEmail()).isEqualTo("owner@example.com");
        assertThat(result.getRole()).isEqualTo(AppUser.ROLE_ADMIN);
        assertThat(result.getStatus()).isEqualTo(AppUser.STATUS_ACTIVE);
        assertThat(service.isConfiguredAdmin(result.getEmail())).isTrue();
    }

    @Test
    void ordinaryUserIsCreatedPending() {
        UserAdminService service = service("owner@example.com");
        when(userRepo.findByEmail("member@example.com")).thenReturn(Optional.empty());
        when(userRepo.save(any(AppUser.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AppUser result = service.loginUpsert("member@example.com", "Member", null);

        assertThat(result.getRole()).isEqualTo(AppUser.ROLE_USER);
        assertThat(result.getStatus()).isEqualTo(AppUser.STATUS_PENDING);
        assertThat(service.isConfiguredAdmin(result.getEmail())).isFalse();
    }

    @Test
    void existingConfiguredAdminIsRepairedOnLogin() {
        UserAdminService service = service("owner@example.com");
        AppUser existing = user(1L, "owner@example.com", AppUser.ROLE_USER, AppUser.STATUS_DISABLED);
        when(userRepo.findByEmail("owner@example.com")).thenReturn(Optional.of(existing));
        when(userRepo.save(existing)).thenReturn(existing);

        AppUser result = service.loginUpsert("owner@example.com", null, null);

        assertThat(result.getRole()).isEqualTo(AppUser.ROLE_ADMIN);
        assertThat(result.getStatus()).isEqualTo(AppUser.STATUS_ACTIVE);
    }

    @Test
    void configuredAdminCannotBeDisabledOrDemoted() {
        UserAdminService service = service("owner@example.com");
        AppUser admin = user(1L, "owner@example.com", AppUser.ROLE_ADMIN, AppUser.STATUS_ACTIVE);
        when(userRepo.findById(1L)).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> service.updateStatus(1L, AppUser.STATUS_DISABLED))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("主要管理者");
        assertThatThrownBy(() -> service.updateRole(1L, AppUser.ROLE_USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("主要管理者");
        verify(userRepo, never()).save(admin);
    }

    @Test
    void anotherAdminCanBeDemoted() {
        UserAdminService service = service("owner@example.com");
        AppUser anotherAdmin = user(2L, "helper@example.com", AppUser.ROLE_ADMIN, AppUser.STATUS_ACTIVE);
        when(userRepo.findById(2L)).thenReturn(Optional.of(anotherAdmin));
        when(userRepo.save(anotherAdmin)).thenReturn(anotherAdmin);

        AppUser result = service.updateRole(2L, AppUser.ROLE_USER);

        assertThat(result.getRole()).isEqualTo(AppUser.ROLE_USER);
    }

    @Test
    void responseCarriesBackendProtectionDecision() {
        AppUser admin = user(1L, "owner@example.com", AppUser.ROLE_ADMIN, AppUser.STATUS_ACTIVE);

        UserDto.UserResponse response = UserDto.UserResponse.from(admin, true);

        assertThat(response.protectedAdmin()).isTrue();
    }

    @Test
    void blankOrMalformedAdminEmailFailsFast() {
        assertThatThrownBy(() -> service("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ADMIN_EMAIL 為必填");
        assertThatThrownBy(() -> service("not-an-email"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ADMIN_EMAIL 格式不正確");
    }

    private UserAdminService service(String adminEmail) {
        return new UserAdminService(userRepo, adminEmail);
    }

    private AppUser user(Long id, String email, String role, String status) {
        return AppUser.builder()
                .id(id)
                .email(email)
                .role(role)
                .status(status)
                .build();
    }
}
