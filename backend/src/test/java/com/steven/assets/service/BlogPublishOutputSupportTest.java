package com.steven.assets.service;

import com.steven.assets.model.AppUser;
import com.steven.assets.repository.AppUserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * {@link BlogPublishOutputSupport} 單元測試（Requirement 102 / Task 366）。
 *
 * <p>與 {@code GdriveOutputSupportTest} 驗的是同一種規則：判準是主要管理者本人
 * （{@code isConfiguredAdmin}），且 fail-closed。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BlogPublishOutputSupportTest {

    @Mock private AppUserRepository userRepo;
    @Mock private UserAdminService userAdminService;

    private BlogPublishOutputSupport support;

    @org.junit.jupiter.api.BeforeEach
    void setup() {
        support = new BlogPublishOutputSupport(userRepo, userAdminService);
    }

    @Test
    void ownerUserId為null回false() {
        assertThat(support.isBlogAllowedFor(null)).isFalse();
    }

    @Test
    void 查無使用者回false() {
        when(userRepo.findById(99L)).thenReturn(Optional.empty());
        assertThat(support.isBlogAllowedFor(99L)).isFalse();
    }

    @Test
    void email為主要管理者回true() {
        AppUser u = new AppUser();
        u.setId(1L);
        u.setEmail("tw.leader@gmail.com");
        when(userRepo.findById(1L)).thenReturn(Optional.of(u));
        when(userAdminService.isConfiguredAdmin("tw.leader@gmail.com")).thenReturn(true);

        assertThat(support.isBlogAllowedFor(1L)).isTrue();
    }

    @Test
    void 非主要管理者回false() {
        AppUser u = new AppUser();
        u.setId(2L);
        u.setEmail("other@example.com");
        when(userRepo.findById(2L)).thenReturn(Optional.of(u));
        when(userAdminService.isConfiguredAdmin("other@example.com")).thenReturn(false);

        assertThat(support.isBlogAllowedFor(2L)).isFalse();
    }
}
