package com.steven.assets.service;

import com.steven.assets.model.AnnualDividendData.*;
import com.steven.assets.security.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AnnualDividendServiceTest {
    @Test void missingIdentityFailsBeforePrivateRepositoryAccess() {
        TenantGuard guard = mock(TenantGuard.class);
        AnnualDividendReadRepository repository = mock(AnnualDividendReadRepository.class);
        assertThatThrownBy(() -> new AnnualDividendService(guard, repository).summary(null, null))
                .isInstanceOf(UnauthenticatedException.class);
        verifyNoInteractions(repository);
    }
    @Test void taipeiDefaultYearAndEffectiveOwnerArePassedExplicitlyAndSelectedYearRemainsAvailable() {
        TenantGuard guard = mock(TenantGuard.class);
        when(guard.requireCurrentUserId()).thenReturn(42L);
        AnnualDividendReadRepository repository = mock(AnnualDividendReadRepository.class);
        when(repository.load(42, LocalDate.of(2027, 1, 1))).thenReturn(
                new Facts(List.of(), List.of(), List.of(), List.of(), List.of(new Market("台股", "台灣股市"))));
        AnnualDividendService service = new AnnualDividendService(guard, repository,
                Clock.fixed(Instant.parse("2026-12-31T16:01:00Z"), ZoneOffset.UTC));
        assertThat(service.summary(null, null).selectedYear()).isEqualTo(2027);
        assertThat(service.summary("2025", "").availableYears()).containsExactly(2027, 2025);
        assertThatThrownBy(() -> service.summary("2026", "不存在")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void invalidYearFailsWithoutReadingPrivateData() {
        TenantGuard guard = mock(TenantGuard.class);
        when(guard.requireCurrentUserId()).thenReturn(42L);
        AnnualDividendReadRepository repository = mock(AnnualDividendReadRepository.class);
        var service = new AnnualDividendService(guard, repository);
        for (String year : List.of("", "1899", "2101", " 2026", "2026x"))
            assertThatThrownBy(() -> service.summary(year, "")).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }
}
