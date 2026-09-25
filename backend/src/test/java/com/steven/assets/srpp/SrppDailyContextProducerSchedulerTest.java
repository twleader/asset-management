package com.steven.assets.srpp;

import com.steven.assets.model.AppUser;
import com.steven.assets.repository.AppUserRepository;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.service.MarketDataService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Requirement 163／Task 452.10：producer 閘門、時段、空 registry、owner 隔離。 */
class SrppDailyContextProducerSchedulerTest {
    private static final ZoneId TW = ZoneId.of("Asia/Taipei");
    private static final LocalDate THURSDAY = LocalDate.of(2026, 9, 24);

    private final MarketDataService marketData = mock(MarketDataService.class);
    private final SrppPolicyRegistryService registry = mock(SrppPolicyRegistryService.class);
    private final AssetSnapshotRepository snapshots = mock(AssetSnapshotRepository.class);
    private final AppUserRepository users = mock(AppUserRepository.class);
    private final SrppSourceCapture capture = mock(SrppSourceCapture.class);
    private final SrppPackagePublisher publisher = mock(SrppPackagePublisher.class);
    private final SupportedPolicy policy = SrppTestData.policy(Map.of());

    private SrppDailyContextProducerScheduler at(LocalDate date, String time) {
        Clock clock = Clock.fixed(LocalDateTime.of(date, java.time.LocalTime.parse(time)).atZone(TW).toInstant(), TW);
        return new SrppDailyContextProducerScheduler(marketData, registry, snapshots, users, capture, publisher, clock);
    }

    private static AppUser user(long id, String status) {
        AppUser user = new AppUser();
        user.setId(id);
        user.setStatus(status);
        return user;
    }

    private void openWithOwners(Long... owners) {
        when(marketData.isTwTradingDayKnown(any())).thenReturn(Optional.of(true));
        when(registry.supportedPolicies()).thenReturn(List.of(policy));
        when(snapshots.findDistinctOwnerUserIds()).thenReturn(List.of(owners));
    }

    private SrppCapture captured(long owner) {
        return new SrppCapture(owner, policy, THURSDAY, "09:05", SrppTestData.proposal().build(), "rev",
                java.time.Instant.EPOCH, List.of());
    }

    private void assertNoOwnerRead() {
        verifyNoInteractions(registry, snapshots, users, capture, publisher);
    }

    @Test
    void weekendWarmsCalendarButReadsNothing() {
        at(LocalDate.of(2026, 9, 26), "10:00").produce();
        verify(marketData).warmTwHolidaysIfExpiringWithin(SrppDailyContextProducerScheduler.CALENDAR_WARM_WINDOW);
        assertNoOwnerRead();
    }

    @Test
    void holidayAndUnknownCalendarProduceNothing() {
        when(marketData.isTwTradingDayKnown(THURSDAY)).thenReturn(Optional.of(false));
        at(THURSDAY, "10:00").produce();
        when(marketData.isTwTradingDayKnown(THURSDAY)).thenReturn(Optional.empty());
        at(THURSDAY, "10:00").produce();
        doThrow(new IllegalStateException("boom")).when(marketData).isTwTradingDayKnown(THURSDAY);
        at(THURSDAY, "10:00").produce();
        assertNoOwnerRead();
    }

    @Test
    void warmFailureIsSwallowed() {
        doThrow(new IllegalStateException("down")).when(marketData).warmTwHolidaysIfExpiringWithin(any());
        when(marketData.isTwTradingDayKnown(THURSDAY)).thenReturn(Optional.empty());
        at(THURSDAY, "10:00").produce();
        assertNoOwnerRead();
    }

    @ParameterizedTest
    @ValueSource(strings = {"08:59", "09:04:59", "14:00", "15:30"})
    void outsideWindowProducesNothing(String time) {
        when(marketData.isTwTradingDayKnown(THURSDAY)).thenReturn(Optional.of(true));
        at(THURSDAY, time).produce();
        assertNoOwnerRead();
    }

    @ParameterizedTest
    @CsvSource({"09:05,09:05", "11:39:59,09:05", "11:40,11:40", "13:59:59,11:40"})
    void slotSelection(String time, String slot) {
        openWithOwners(1L);
        when(users.findByStatus(AppUser.STATUS_ACTIVE)).thenReturn(List.of(user(1, "ACTIVE")));
        when(capture.capture(eq(1L), eq(policy), eq(THURSDAY), anyString())).thenReturn(captured(1));
        at(THURSDAY, time).produce();
        verify(capture).capture(1L, policy, THURSDAY, slot);
    }

    @Test
    void emptyRegistryDoesZeroCapture() {
        when(marketData.isTwTradingDayKnown(THURSDAY)).thenReturn(Optional.of(true));
        when(registry.supportedPolicies()).thenReturn(List.of());
        at(THURSDAY, "10:00").produce();
        verifyNoInteractions(snapshots, users, capture, publisher);
    }

    @Test
    void oneOwnerFailureDoesNotAffectNextAndInactiveOwnersAreSkipped() {
        openWithOwners(3L, 1L, 2L, 4L);
        when(users.findByStatus(AppUser.STATUS_ACTIVE)).thenReturn(List.of(user(1, "ACTIVE"), user(2, "ACTIVE"),
                user(3, "ACTIVE")));
        when(capture.capture(eq(1L), any(), any(), any())).thenThrow(new IllegalStateException("db down"));
        when(capture.capture(eq(2L), any(), any(), any())).thenThrow(new SrppRejectedException("FUTURE_DATA_AS_OF"));
        when(capture.capture(eq(3L), any(), any(), any())).thenReturn(captured(3));

        at(THURSDAY, "10:00").produce();

        verify(capture, never()).capture(eq(4L), any(), any(), any());
        verify(publisher).publish(any(), any());
        verify(publisher, never()).publish(org.mockito.ArgumentMatchers.argThat(c -> c != null && c.ownerId() != 3L), any());
    }

    @Test
    void calculatorRejectionSkipsPublish() {
        openWithOwners(1L);
        when(users.findByStatus(AppUser.STATUS_ACTIVE)).thenReturn(List.of(user(1, "ACTIVE")));
        SrppTestData bad = SrppTestData.proposal();
        bad.liveSnapshotId = 99L;
        when(capture.capture(anyLong(), any(), any(), any())).thenReturn(new SrppCapture(1L, policy, THURSDAY, "09:05",
                bad.build(), "rev", java.time.Instant.EPOCH, List.of()));
        at(THURSDAY, "10:00").produce();
        verifyNoInteractions(publisher);
    }
}
