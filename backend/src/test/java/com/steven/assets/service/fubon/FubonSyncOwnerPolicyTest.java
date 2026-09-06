package com.steven.assets.service.fubon;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class FubonSyncOwnerPolicyTest {
    private final FubonSyncOwnerConfigPort config = mock(FubonSyncOwnerConfigPort.class);
    private final FubonSyncOwnerDirectoryPort directory = mock(FubonSyncOwnerDirectoryPort.class);

    @Test
    void missingOrInvalidDedicatedOwnerNeverFallsBackToConfiguredAdmin() {
        when(config.syncOwnerEmail()).thenReturn(" ");

        FubonSyncOwnerPort.Decision decision = policy().preflight();

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denial()).isEqualTo(FubonSyncOwnerPort.Denial.SYNC_OWNER_NOT_CONFIGURED);
        verifyNoInteractions(directory);
    }

    @Test
    void normalizedDedicatedAddressMustPointToTheSameActiveConfiguredAdmin() {
        FubonSyncOwnerDirectoryPort.Owner owner = owner(9L, "owner@example.invalid", true, true);
        when(config.syncOwnerEmail()).thenReturn(" Owner@Example.Invalid ");
        when(directory.findByNormalizedEmail("owner@example.invalid")).thenReturn(Optional.of(owner));
        when(directory.configuredAdmin()).thenReturn(Optional.of(owner));

        FubonSyncOwnerPort.Decision decision = policy().preflight();

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.ownerId()).isEqualTo(9L);
    }

    @Test
    void differentConfiguredAdminOrInactiveRoleCannotSelectAnOwner() {
        when(config.syncOwnerEmail()).thenReturn("owner@example.invalid");
        when(directory.findByNormalizedEmail("owner@example.invalid"))
                .thenReturn(Optional.of(owner(9L, "owner@example.invalid", true, true)));
        when(directory.configuredAdmin()).thenReturn(Optional.of(
                owner(10L, "configured@example.invalid", true, true)));

        FubonSyncOwnerPort.Decision decision = policy().preflight();

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.denial()).isEqualTo(FubonSyncOwnerPort.Denial.NO_OWNER);
    }

    @Test
    void freshLockRechecksStatusRoleEmailAndExpectedId() {
        when(config.syncOwnerEmail()).thenReturn("owner@example.invalid");
        when(directory.lockById(9L)).thenReturn(Optional.of(owner(9L, "owner@example.invalid", false, true)));

        assertThatThrownBy(() -> policy().lockAndRevalidate(9L))
                .isInstanceOf(FubonSyncOwnerPort.Rejected.class)
                .hasMessage("NO_OWNER");
        verify(directory, never()).isConfiguredAdmin(any());
    }

    @Test
    void freshLockAcceptsOnlyTheExactConfiguredActiveAdmin() {
        FubonSyncOwnerDirectoryPort.Owner owner = owner(9L, "owner@example.invalid", true, true);
        when(config.syncOwnerEmail()).thenReturn("owner@example.invalid");
        when(directory.lockById(9L)).thenReturn(Optional.of(owner));
        when(directory.isConfiguredAdmin("owner@example.invalid")).thenReturn(true);

        FubonSyncOwnerPort.LockedOwner locked = policy().lockAndRevalidate(9L);

        assertThat(locked.ownerId()).isEqualTo(9L);
        verify(directory).lockById(9L);
    }

    private FubonSyncOwnerPolicy policy() {
        return new FubonSyncOwnerPolicy(config, directory);
    }

    private static FubonSyncOwnerDirectoryPort.Owner owner(Long id, String email, boolean active, boolean admin) {
        return new FubonSyncOwnerDirectoryPort.Owner(id, email, active, admin);
    }
}
