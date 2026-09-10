package com.steven.assets.service;

import com.steven.assets.dto.ExchangeRateExportDto;
import com.steven.assets.model.ExchangeRateExportSchedule;
import com.steven.assets.model.ExchangeRateExportScheduleTime;
import com.steven.assets.repository.AppUserRepository;
import com.steven.assets.repository.ExchangeRateExportScheduleRepository;
import com.steven.assets.security.CurrentUserContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Requirement 145 / Task 423：匯率多時間的 child guard、整包更新與 run-now 邊界。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExchangeRateExportScheduleServiceTest {
    private static final ZoneId TW = ZoneId.of("Asia/Taipei");
    @Mock private ExchangeRateExportScheduleRepository repo;
    @Mock private ExcelExportService excel;
    @Mock private ObjectProvider<CurrentUserContext> users;
    @Mock private RcloneClient rclone;
    @Mock private AppUserRepository appUsers;
    @Mock private UserAdminService admins;
    @Mock private GdriveSelfCheck selfCheck;
    private ExchangeRateExportScheduleService service;

    @BeforeEach
    void setUp() {
        var gdrive = new GdriveOutputSupport(rclone, appUsers, admins, selfCheck, "GDriveOutput");
        service = new ExchangeRateExportScheduleService(repo, excel, users, gdrive,
                new com.steven.assets.service.export.ExcelDocRenderer(),
                new com.steven.assets.service.export.JsonDocRenderer(new com.fasterxml.jackson.databind.ObjectMapper()),
                new com.steven.assets.service.export.DualFormatExportWriter(gdrive), Path.of("/tmp").toString());
        when(repo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        CurrentUserContext current = new CurrentUserContext();
        current.setEffectiveUserId(1L);
        when(users.getObject()).thenReturn(current);
    }

    @Test
    void get無設定回0800TransientChild不寫DB() {
        when(repo.findByOwnerUserId(1L)).thenReturn(Optional.empty());
        var response = service.getForCurrentUser();
        assertThat(response.times()).singleElement().satisfies(time -> {
            assertThat(time.runHour()).isEqualTo(8);
            assertThat(time.runMinute()).isZero();
            assertThat(time.enabled()).isTrue();
        });
        verify(repo, never()).save(any());
    }

    @Test
    void update保留相同時間guard_新增時間無guard_並同步representative() {
        var schedule = ExchangeRateExportSchedule.builder().ownerUserId(1L).enabled(false).outputSubpath("input").build();
        var existing = ExchangeRateExportScheduleTime.builder().runHour(9).runMinute(30).enabled(true)
                .lastRunDate(LocalDate.now(TW).minusDays(1)).lastRunStatus("舊結果").build();
        schedule.addTime(existing);
        when(repo.findByOwnerUserId(1L)).thenReturn(Optional.of(schedule));

        var response = service.updateForCurrentUser(new ExchangeRateExportDto.SettingRequest(true, "input", null,
                List.of(new ExchangeRateExportDto.TimeRequest(12, 0, true), new ExchangeRateExportDto.TimeRequest(9, 30, true)), null, null));

        assertThat(response.times()).extracting(ExchangeRateExportDto.TimeResponse::runHour).containsExactly(9, 12);
        assertThat(schedule.getTimes()).anySatisfy(time -> assertThat(time.getLastRunDate()).isEqualTo(LocalDate.now(TW).minusDays(1)));
        assertThat(schedule.getTimes()).anySatisfy(time -> { assertThat(time.getRunHour()).isEqualTo(12); assertThat(time.getLastRunDate()).isNull(); });
        assertThat(schedule.getRunHour()).isEqualTo(9);
        assertThat(schedule.getRunMinute()).isEqualTo(30);
    }

    @Test
    void update拒絕空重複非法與總啟用全停用() {
        assertThatThrownBy(() -> service.updateForCurrentUser(new ExchangeRateExportDto.SettingRequest(false, "input", null, List.of(), null, null)))
                .hasMessageContaining("至少需要一個執行時間");
        assertThatThrownBy(() -> service.updateForCurrentUser(new ExchangeRateExportDto.SettingRequest(false, "input", null,
                List.of(new ExchangeRateExportDto.TimeRequest(8, 0, true), new ExchangeRateExportDto.TimeRequest(8, 0, false)), null, null)))
                .hasMessageContaining("不可重複");
        assertThatThrownBy(() -> service.updateForCurrentUser(new ExchangeRateExportDto.SettingRequest(false, "input", null,
                List.of(new ExchangeRateExportDto.TimeRequest(24, 0, true)), null, null))).hasMessageContaining("00:00～23:59");
        assertThatThrownBy(() -> service.updateForCurrentUser(new ExchangeRateExportDto.SettingRequest(true, "input", null,
                List.of(new ExchangeRateExportDto.TimeRequest(8, 0, false)), null, null))).hasMessageContaining("至少需啟用一個時間");
    }
}
