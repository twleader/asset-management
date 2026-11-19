package com.steven.assets.service;

import com.steven.assets.model.FubonScheduleSwitch;
import com.steven.assets.repository.FubonScheduleSwitchRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FubonScheduleSwitchServiceTest {

    private final FubonScheduleSwitchRepository repo = mock(FubonScheduleSwitchRepository.class);
    private final FubonScheduleSwitchService service = new FubonScheduleSwitchService(repo);

    @Test
    void enabledRowIsEnabledAndDisabledRowIsNot() {
        when(repo.findById("inventory")).thenReturn(Optional.of(new FubonScheduleSwitch("inventory", true, Instant.EPOCH)));
        when(repo.findById("srpp-order-source")).thenReturn(Optional.of(new FubonScheduleSwitch("srpp-order-source", false, Instant.EPOCH)));
        assertThat(service.isEnabled("inventory")).isTrue();
        assertThat(service.isEnabled("srpp-order-source")).isFalse();
    }

    @Test
    void missingRowOrReadFailureFailsClosed() {
        when(repo.findById("missing")).thenReturn(Optional.empty());
        when(repo.findById("broken")).thenThrow(new IllegalStateException("db down"));
        assertThat(service.isEnabled("missing")).isFalse();
        assertThat(service.isEnabled("broken")).isFalse();
    }

    @Test
    void stateDistinguishesOffMissingAndUnreadable() {
        when(repo.findById("a")).thenReturn(Optional.of(new FubonScheduleSwitch("a", false, Instant.EPOCH)));
        when(repo.findById("b")).thenReturn(Optional.empty());
        when(repo.findById("c")).thenThrow(new IllegalStateException("db down"));
        assertThat(service.state("a")).isEqualTo(FubonScheduleSwitchService.State.OFF);
        assertThat(service.state("b")).isEqualTo(FubonScheduleSwitchService.State.MISSING);
        assertThat(service.state("c")).isEqualTo(FubonScheduleSwitchService.State.UNREADABLE);
    }

    @Test
    void setEnabledUpdatesRowAndStampsTime() {
        FubonScheduleSwitch row = new FubonScheduleSwitch("trade", false, Instant.EPOCH);
        when(repo.findById("trade")).thenReturn(Optional.of(row));
        when(repo.save(any(FubonScheduleSwitch.class))).thenAnswer(call -> call.getArgument(0));
        var result = service.setEnabled("trade", true);
        assertThat(result.enabled()).isTrue();
        assertThat(result.updatedAt()).isAfter(Instant.EPOCH);
    }

    @Test
    void setEnabledRejectsUnknownKey() {
        when(repo.findById("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.setEnabled("nope", true)).isInstanceOf(NoSuchElementException.class);
    }
}
