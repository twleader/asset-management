package com.steven.assets.controller;

import com.steven.assets.dto.TradingRadarBlogDto;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.service.BlogOAuthService;
import com.steven.assets.service.BlogPublishOutputSupport;
import com.steven.assets.service.BlogPublishService;
import com.steven.assets.service.TradingRadarBlogSettingsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Task 374：HTTP controller 只負責 owner 邊界與 settings service 委派。 */
@ExtendWith(MockitoExtension.class)
class TradingRadarBlogControllerTest {

    private static final long OWNER_ID = 11L;

    @Mock private BlogPublishOutputSupport outputSupport;
    @Mock private BlogOAuthService oauthService;
    @Mock private BlogPublishService publishService;
    @Mock private TradingRadarBlogSettingsService settingsService;
    @Mock private CurrentUserContext currentUser;

    private TradingRadarBlogController controller;

    @BeforeEach
    void setup() {
        controller = new TradingRadarBlogController(outputSupport, oauthService, publishService,
                settingsService, currentUser);
    }

    @Test
    void status只將目前owner委派給settingsService() {
        TradingRadarBlogDto.StatusResponse expected = status(false);
        when(currentUser.hasUser()).thenReturn(true);
        when(currentUser.getEffectiveUserId()).thenReturn(OWNER_ID);
        when(settingsService.status(OWNER_ID)).thenReturn(expected);

        TradingRadarBlogDto.StatusResponse actual = controller.status();

        assertThat(actual).isSameAs(expected);
        verify(settingsService).status(OWNER_ID);
    }

    @Test
    void 未識別使用者status仍只委派nullOwner() {
        TradingRadarBlogDto.StatusResponse expected = status(false);
        when(currentUser.hasUser()).thenReturn(false);
        when(settingsService.status(null)).thenReturn(expected);

        assertThat(controller.status()).isSameAs(expected);
        verify(settingsService).status(null);
    }

    @Test
    void setEnabled只將owner與request值委派給settingsService() {
        TradingRadarBlogDto.StatusResponse expected = status(true);
        when(currentUser.hasUser()).thenReturn(true);
        when(currentUser.getEffectiveUserId()).thenReturn(OWNER_ID);
        when(settingsService.setEnabled(OWNER_ID, true)).thenReturn(expected);

        TradingRadarBlogDto.StatusResponse actual = controller.setEnabled(new TradingRadarBlogDto.EnabledRequest(true));

        assertThat(actual).isSameAs(expected);
        verify(settingsService).setEnabled(OWNER_ID, true);
    }

    @Test
    void setEnabled缺少owner維持400用的IllegalArgumentException邊界() {
        when(currentUser.hasUser()).thenReturn(false);

        assertThatThrownBy(() -> controller.setEnabled(new TradingRadarBlogDto.EnabledRequest(true)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void oauthCallback成功只委派credential處理且不自動開啟排程() {
        when(currentUser.getEffectiveUserId()).thenReturn(OWNER_ID);
        when(outputSupport.isBlogAllowedFor(OWNER_ID)).thenReturn(true);
        when(oauthService.handleCallback("code", "state"))
                .thenReturn(new BlogOAuthService.CallbackResult(true, null));

        assertThat(controller.callback("code", "state").getStatusCode().value()).isEqualTo(302);

        verifyNoInteractions(settingsService);
    }

    private static TradingRadarBlogDto.StatusResponse status(boolean enabled) {
        return new TradingRadarBlogDto.StatusResponse(false, null, "https://myrader.blogspot.com/", enabled,
                null, null, null);
    }
}
