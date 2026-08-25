package com.steven.assets.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.dto.TradingRadarBlogDto;
import com.steven.assets.model.BlogPublishCredential;
import com.steven.assets.model.TradingRadarExportSetting;
import com.steven.assets.repository.BlogPublishCredentialRepository;
import com.steven.assets.repository.TradingRadarExportSettingRepository;
import com.steven.assets.security.AdminRequiredException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Task 374：排程 Blog 開關只能由有效的新目的地 credential 重新啟用。 */
@ExtendWith(MockitoExtension.class)
class TradingRadarBlogSettingsServiceTest {

    private static final long OWNER_ID = 7L;
    private static final String OLD_DESTINATION = "https://twleader." + "blogspot.com/";

    @Mock private TradingRadarExportSettingRepository settingRepo;
    @Mock private BlogPublishCredentialRepository credentialRepo;
    @Mock private BlogPublishOutputSupport outputSupport;
    @Mock private GoogleHttpClient httpClient;

    private TradingRadarBlogSettingsService service;

    @BeforeEach
    void setup() {
        BlogOAuthService oauthService = new BlogOAuthService(credentialRepo, httpClient, new ObjectMapper(),
                "client", "secret", "https://app.example.com");
        service = new TradingRadarBlogSettingsService(settingRepo, oauthService, outputSupport);
        when(outputSupport.isBlogAllowedFor(OWNER_ID)).thenReturn(true);
    }

    @Test
    void 無credential時拒絕啟用且不寫入setting() {
        when(credentialRepo.findById(1L)).thenReturn(Optional.empty());

        rejectEnableWithoutSettingWrite();
    }

    @Test
    void 需要重新連接時拒絕啟用且不寫入setting() {
        givenCredential("BLOG1", "RT1", "https://myrader.blogspot.com/", true);

        rejectEnableWithoutSettingWrite();
    }

    @Test
    void 缺blogId時拒絕啟用且不寫入setting() {
        givenCredential(" ", "RT1", "https://myrader.blogspot.com/", false);

        rejectEnableWithoutSettingWrite();
    }

    @Test
    void 缺refreshToken時拒絕啟用且不寫入setting() {
        givenCredential("BLOG1", " ", "https://myrader.blogspot.com/", false);

        rejectEnableWithoutSettingWrite();
    }

    @Test
    void 舊目的地網址時拒絕啟用且不寫入setting() {
        givenCredential("BLOG1", "RT1", OLD_DESTINATION, false);

        rejectEnableWithoutSettingWrite();
    }

    @Test
    void 完整新目的地credential才可啟用並儲存true() {
        givenCredential("BLOG1", "RT1", "https://myrader.blogspot.com/", false);
        TradingRadarExportSetting setting = TradingRadarExportSetting.builder().ownerUserId(OWNER_ID).build();
        when(settingRepo.findByOwnerUserId(OWNER_ID)).thenReturn(Optional.of(setting));

        TradingRadarBlogDto.StatusResponse response = service.setEnabled(OWNER_ID, true);

        assertThat(setting.isBlogEnabled()).isTrue();
        assertThat(response.connected()).isTrue();
        assertThat(response.blogEnabled()).isTrue();
        verify(settingRepo).save(setting);
    }

    @Test
    void 非主要管理者不得改變開關() {
        when(outputSupport.isBlogAllowedFor(OWNER_ID)).thenReturn(false);

        assertThatThrownBy(() -> service.setEnabled(OWNER_ID, false))
                .isInstanceOf(AdminRequiredException.class);

        verify(settingRepo, never()).save(any());
    }

    private void rejectEnableWithoutSettingWrite() {
        assertThatThrownBy(() -> service.setEnabled(OWNER_ID, true))
                .isInstanceOf(IllegalArgumentException.class);
        verify(settingRepo, never()).save(any());
    }

    private void givenCredential(String blogId, String refreshToken, String blogUrl, boolean needsReconnect) {
        BlogPublishCredential credential = BlogPublishCredential.builder()
                .id(1L).blogId(blogId).refreshToken(refreshToken).blogUrl(blogUrl)
                .needsReconnect(needsReconnect).build();
        when(credentialRepo.findById(1L)).thenReturn(Optional.of(credential));
    }
}
