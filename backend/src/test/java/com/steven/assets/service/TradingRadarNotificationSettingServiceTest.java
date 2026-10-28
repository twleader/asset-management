package com.steven.assets.service;

import com.steven.assets.dto.TradingRadarNotificationDto;
import com.steven.assets.model.NotificationRecipient;
import com.steven.assets.model.TradingRadarNotificationSetting;
import com.steven.assets.model.TradingRadarNotificationState;
import com.steven.assets.repository.NotificationRecipientRepository;
import com.steven.assets.repository.TradingRadarNotificationRecipientRepository;
import com.steven.assets.repository.TradingRadarNotificationSettingRepository;
import com.steven.assets.repository.TradingRadarNotificationStateRepository;
import com.steven.assets.security.TenantGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TradingRadarNotificationSettingServiceTest {

    private static final String STOCK_CODE = "2330";
    private static final String MARKET = "台股";
    private static final Long SETTING_ID = 17L;
    private static final Long OWNER_ID = 29L;

    @Mock private TradingRadarNotificationSettingRepository settingRepo;
    @Mock private TradingRadarNotificationStateRepository stateRepo;
    @Mock private TradingRadarNotificationRecipientRepository recipientLinkRepo;
    @Mock private NotificationRecipientRepository recipientRepo;
    @Mock private TenantGuard tenantGuard;

    @Test
    void getProjectsNamesAndPreservesRecipientIdsAndExistingFields() {
        TradingRadarNotificationSetting setting = TradingRadarNotificationSetting.builder()
                .id(SETTING_ID)
                .ownerUserId(OWNER_ID)
                .stockCode(STOCK_CODE)
                .market(MARKET)
                .active(true)
                .build();
        NotificationRecipient named = NotificationRecipient.builder()
                .id(5L)
                .ownerUserId(OWNER_ID)
                .email("named@example.com")
                .name("王小明")
                .active(true)
                .build();
        NotificationRecipient unnamed = NotificationRecipient.builder()
                .id(6L)
                .ownerUserId(OWNER_ID)
                .email("unnamed@example.com")
                .name(null)
                .active(false)
                .build();

        when(settingRepo.findByStockCodeAndMarket(STOCK_CODE, MARKET)).thenReturn(Optional.of(setting));
        when(stateRepo.findStateCodes(SETTING_ID, TradingRadarNotificationState.TYPE_ACTION))
                .thenReturn(List.of("BUY_CANDIDATE"));
        when(stateRepo.findStateCodes(SETTING_ID, TradingRadarNotificationState.TYPE_COUNTER_TREND))
                .thenReturn(List.of());
        when(recipientLinkRepo.findRecipientIds(SETTING_ID)).thenReturn(List.of(6L));
        when(recipientRepo.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(named, unnamed));

        TradingRadarNotificationDto.Response response = new TradingRadarNotificationSettingService(
                settingRepo, stateRepo, recipientLinkRepo, recipientRepo, tenantGuard)
                .get(STOCK_CODE, MARKET);

        assertEquals(List.of(6L), response.recipientIds());
        assertEquals(List.of(
                new TradingRadarNotificationDto.RecipientOption(5L, "named@example.com", true, "王小明"),
                new TradingRadarNotificationDto.RecipientOption(6L, "unnamed@example.com", false, null)),
                response.recipients());
        verify(recipientRepo).findAllByOrderByCreatedAtAsc();
    }
}
