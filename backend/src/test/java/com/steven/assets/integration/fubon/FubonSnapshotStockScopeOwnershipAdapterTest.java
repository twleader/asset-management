package com.steven.assets.integration.fubon;

import com.steven.assets.model.AppUser;
import com.steven.assets.model.AssetSnapshot;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.service.AssetService;
import com.steven.assets.service.SnapshotStockScopeOwnership;
import com.steven.assets.service.SnapshotStockScopeOwnershipPort;
import com.steven.assets.service.SnapshotUpdateTarget;
import com.steven.assets.service.UserAdminService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FubonSnapshotStockScopeOwnershipAdapterTest {
    private static final ZoneId TW_ZONE = ZoneId.of("Asia/Taipei");
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 24);
    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-08-24T02:00:00Z"), TW_ZONE);

    @Mock FubonConfigState configState;
    @Mock UserAdminService userAdminService;
    @Mock AssetSnapshotRepository snapshotRepository;

    private void configureEligibleTarget() {
        when(userAdminService.configuredAdmin()).thenReturn(Optional.of(activeAdmin(9L)));
        when(snapshotRepository.findFirstByOwnerUserIdOrderBySnapshotDateDesc(9L))
                .thenReturn(Optional.of(snapshot(7L, 9L, TODAY)));
    }

    @Test
    void capturesSourceOwnershipOnlyForReadyInventoryCapabilityAndEligibleTarget() {
        configureEligibleTarget();
        when(configState.snapshot()).thenReturn(config(FubonConfigState.State.READY));

        SnapshotStockScopeOwnership ownership = adapter(true, false)
                .capture(target(7L, 9L, TODAY));

        assertThat(ownership.isSourceOwned("台股", "fubon")).isTrue();
        assertThat(ownership.isSourceOwned("美股", "fubon")).isFalse();
        assertThat(ownership.isSourceOwned("台股", "other")).isFalse();
        verify(configState, times(1)).snapshot();
        verify(userAdminService, times(1)).configuredAdmin();
        verify(snapshotRepository, times(1)).findFirstByOwnerUserIdOrderBySnapshotDateDesc(9L);
    }

    @Test
    void fullStateAndFlagMatrixMapsEveryNonWriterCaseToPayloadOwnership() {
        for (FubonConfigState.State state : FubonConfigState.State.values()) {
            for (boolean inventoryEnabled : new boolean[]{false, true}) {
                for (boolean liveEnabled : new boolean[]{false, true}) {
                    reset(configState, userAdminService, snapshotRepository);
                    when(configState.snapshot()).thenReturn(config(state));
                    boolean expectedSource = state == FubonConfigState.State.READY
                            && inventoryEnabled && !liveEnabled;
                    if (expectedSource) {
                        when(userAdminService.configuredAdmin()).thenReturn(Optional.of(activeAdmin(9L)));
                        when(snapshotRepository.findFirstByOwnerUserIdOrderBySnapshotDateDesc(9L))
                                .thenReturn(Optional.of(snapshot(7L, 9L, TODAY)));
                    }
                    SnapshotStockScopeOwnership ownership = adapter(inventoryEnabled, liveEnabled)
                            .capture(target(7L, 9L, TODAY));
                    assertThat(ownership.isSourceOwned("台股", "fubon"))
                            .as("state=%s inventory=%s live=%s", state, inventoryEnabled, liveEnabled)
                            .isEqualTo(expectedSource);
                    verify(configState, times(1)).snapshot();
                }
            }
        }
    }

    @Test
    void eligibleCapabilityStillReturnsPayloadOwnershipForEveryNonWriterTarget() {
        when(configState.snapshot()).thenReturn(config(FubonConfigState.State.READY));

        assertThat(adapter(true, false).capture(target(7L, 9L, TODAY.minusDays(1)))
                .isSourceOwned("台股", "fubon")).isFalse();
        verify(userAdminService, never()).configuredAdmin();
        verify(snapshotRepository, never()).findFirstByOwnerUserIdOrderBySnapshotDateDesc(anyLong());

        reset(userAdminService, snapshotRepository);
        when(userAdminService.configuredAdmin()).thenReturn(Optional.of(activeAdmin(9L)));
        assertThat(adapter(true, false).capture(target(7L, 10L, TODAY))
                .isSourceOwned("台股", "fubon")).isFalse();
        verify(snapshotRepository, never()).findFirstByOwnerUserIdOrderBySnapshotDateDesc(anyLong());

        reset(userAdminService, snapshotRepository);
        when(userAdminService.configuredAdmin()).thenReturn(Optional.of(activeAdmin(9L)));
        when(snapshotRepository.findFirstByOwnerUserIdOrderBySnapshotDateDesc(9L))
                .thenReturn(Optional.of(snapshot(8L, 9L, TODAY)));
        assertThat(adapter(true, false).capture(target(7L, 9L, TODAY))
                .isSourceOwned("台股", "fubon")).isFalse();

        reset(userAdminService, snapshotRepository);
        when(userAdminService.configuredAdmin()).thenReturn(Optional.of(inactiveAdmin(9L)));
        assertThat(adapter(true, false).capture(target(7L, 9L, TODAY))
                .isSourceOwned("台股", "fubon")).isFalse();
    }

    @Test
    void returnedDecisionDoesNotReReadMutableConfigOrTargetEligibility() {
        configureEligibleTarget();
        when(configState.snapshot()).thenReturn(config(FubonConfigState.State.READY));

        SnapshotStockScopeOwnership ownership = adapter(true, false)
                .capture(target(7L, 9L, TODAY));

        assertThat(ownership.isSourceOwned("台股", "fubon")).isTrue();
        assertThat(ownership.isSourceOwned("台股", "fubon")).isTrue();
        verify(configState, times(1)).snapshot();
        verify(userAdminService, times(1)).configuredAdmin();
        verify(snapshotRepository, times(1)).findFirstByOwnerUserIdOrderBySnapshotDateDesc(9L);
    }

    @Test
    void assetServiceDependsOnlyOnTheServicePort() {
        assertThat(Arrays.stream(AssetService.class.getDeclaredFields()).map(Field::getType).toList())
                .contains(SnapshotStockScopeOwnershipPort.class)
                .noneMatch(type -> type.getPackageName().contains(".integration.fubon"));
    }

    private FubonSnapshotStockScopeOwnershipAdapter adapter(boolean inventoryEnabled, boolean liveEnabled) {
        return new FubonSnapshotStockScopeOwnershipAdapter(configState, userAdminService, snapshotRepository,
                CLOCK, inventoryEnabled, liveEnabled);
    }

    private static SnapshotUpdateTarget target(Long snapshotId, Long ownerUserId, LocalDate date) {
        return new SnapshotUpdateTarget(snapshotId, ownerUserId, date);
    }

    private static AssetSnapshot snapshot(Long id, Long ownerUserId, LocalDate date) {
        return AssetSnapshot.builder().id(id).ownerUserId(ownerUserId).snapshotDate(date).build();
    }

    private static AppUser activeAdmin(Long id) {
        return AppUser.builder().id(id).role(AppUser.ROLE_ADMIN).status(AppUser.STATUS_ACTIVE).build();
    }

    private static AppUser inactiveAdmin(Long id) {
        return AppUser.builder().id(id).role(AppUser.ROLE_ADMIN).status(AppUser.STATUS_DISABLED).build();
    }

    private static FubonConfigState.Snapshot config(FubonConfigState.State state) {
        return new FubonConfigState.Snapshot(state, state == FubonConfigState.State.READY ? "token" : null,
                state.name());
    }
}
