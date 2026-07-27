package com.steven.assets.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ProcessRcloneClient.isRateLimited} 的判別邏輯（Requirement 51 / Task 243）。
 *
 * <p>這一條的價值在於它<b>兩邊都可能錯</b>：漏判會讓使用者看到一整段 Google API 的 JSON 並以為要去修
 * 設定；過度判斷（例如只看 {@code 403}）會把真正的權限不足寫成「稍候再試」，使用者就會一直等一個
 * 永遠不會好的狀態。
 *
 * <p>以反射直接測 private static 判別式——它不依賴 ProcessBuilder，無需真的執行 rclone。
 */
class ProcessRcloneClientRateLimitTest {

    /** 使用者實際遇到的 stderr（已截短，保留判別所需的特徵字）。 */
    private static final String REAL_RATE_LIMIT_STDERR = """
            2026/07/27 20:09:05 CRITICAL: Failed to create file system for "GDriveOutput:": \
            couldn't find root directory ID: googleapi: Error 403: Quota exceeded for quota metric \
            'Queries' and limit 'Queries per minute' of service 'drive.googleapis.com' for consumer \
            'project_number:202264815644'. Details: [ { "@type": "type.googleapis.com/google.rpc.ErrorInfo", \
            "reason": "RATE_LIMIT_EXCEEDED" } ], rateLimitExceeded""";

    private static boolean isRateLimited(String stderr) throws Exception {
        Method m = ProcessRcloneClient.class.getDeclaredMethod("isRateLimited", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, stderr);
    }

    @Test
    void 使用者實際遇到的速率限制被判出() throws Exception {
        assertThat(isRateLimited(REAL_RATE_LIMIT_STDERR)).isTrue();
    }

    @Test
    void 單一使用者層的速率限制也被判出() throws Exception {
        // Google 對不同觸發途徑回不同字樣，只認一個會漏
        assertThat(isRateLimited("googleapi: Error 403: User Rate Limit Exceeded, userRateLimitExceeded"))
                .isTrue();
    }

    @Test
    void 大小寫不影響判別() throws Exception {
        assertThat(isRateLimited("reason: RATE_LIMIT_EXCEEDED")).isTrue();
        assertThat(isRateLimited("ratelimitexceeded")).isTrue();
    }

    @Test
    void 真正的權限不足不得被誤判為速率限制() throws Exception {
        // 同樣是 403，但這是使用者必須處理的確定性失敗——寫成「稍候再試」會讓他一直等下去
        assertThat(isRateLimited(
                "googleapi: Error 403: Insufficient permissions for this file, insufficientFilePermissions"))
                .isFalse();
        assertThat(isRateLimited("googleapi: Error 403: The user has not granted the app, accessNotConfigured"))
                .isFalse();
    }

    @Test
    void 其他失敗與空輸入不被誤判() throws Exception {
        assertThat(isRateLimited("directory not found")).isFalse();
        assertThat(isRateLimited("")).isFalse();
        assertThat(isRateLimited(null)).isFalse();
    }
}
