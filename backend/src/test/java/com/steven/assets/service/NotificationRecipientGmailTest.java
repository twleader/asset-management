package com.steven.assets.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「僅 Gmail 收件人可加入 Google 日曆」的網域判定（Requirement 23 / Task 248）。
 * 後端必須自行把關，不可只靠前端隱藏開關。
 */
class NotificationRecipientGmailTest {

    @ParameterizedTest
    @ValueSource(strings = {"a@gmail.com", "a@googlemail.com", "first.last+tag@gmail.com"})
    @DisplayName("Google 網域回 true")
    void googleDomains(String email) {
        assertTrue(NotificationRecipientService.isGmail(email));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "a@ms1.wra.gov.tw",
            "a@transglobe.com.tw",
            "a@gmail.com.tw",      // 後綴不同，不是 Gmail
            "a@notgmail.com",
            "agmail.com"           // 根本沒有 @
    })
    @DisplayName("非 Google 網域回 false")
    void nonGoogleDomains(String email) {
        assertFalse(NotificationRecipientService.isGmail(email));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("null / 空字串回 false，不拋例外")
    void blankInputs(String email) {
        assertFalse(NotificationRecipientService.isGmail(email));
    }

    @Test
    @DisplayName("大小寫與前後空白不影響判定（email 寫入前已正規化，這裡是縱深保護）")
    void toleratesCasingAndWhitespace() {
        assertTrue(NotificationRecipientService.isGmail("a@GMail.Com "));
    }
}
