package com.steven.assets.service;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code ProcessRcloneClient.isInvalidClient} 的判別邏輯（Task 443／Requirement 160）。
 *
 * <p>2026-09-17 實測：{@code [GDriveOutput]} 的 {@code client_secret} 與 {@code .env} 現行值不同步，
 * rclone 回 {@code couldn't fetch token: invalid_client: ...}，但這個錯誤原本落在通用的「其他失敗」分支，
 * 訊息只是原樣附上 rclone stderr，沒有指出真正的根因。這一條驗證分類判準本身。
 *
 * <p>以反射直接測 private static 判別式（與同目錄 {@link ProcessRcloneClientRateLimitTest} 同一種寫法）——
 * 它不依賴 ProcessBuilder，無需真的執行 rclone。
 */
class ProcessRcloneClientInvalidClientTest {

    /** 2026-09-17 實測的 stderr。 */
    private static final String REAL_INVALID_CLIENT_STDERR =
            "couldn't fetch token: invalid_client: if you're using your own client id/secret, "
            + "make sure they're properly set up following the docs";

    private static boolean isInvalidClient(String stderr) throws Exception {
        Method m = ProcessRcloneClient.class.getDeclaredMethod("isInvalidClient", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, stderr);
    }

    @Test
    void 使用者實際遇到的invalid_client被判出() throws Exception {
        assertThat(isInvalidClient(REAL_INVALID_CLIENT_STDERR)).isTrue();
    }

    @Test
    void 大小寫混合不影響判別() throws Exception {
        assertThat(isInvalidClient("Invalid_Client")).isTrue();
        assertThat(isInvalidClient("INVALID_CLIENT: bad credentials")).isTrue();
    }

    @Test
    void 其他失敗與空輸入不被誤判() throws Exception {
        assertThat(isInvalidClient("directory not found")).isFalse();
        assertThat(isInvalidClient("")).isFalse();
        assertThat(isInvalidClient(null)).isFalse();
    }

    @Test
    void 速率限制字串不被誤判為invalid_client() throws Exception {
        // 兩者是不同的分類，錯誤訊息與修法完全不同，不得互相吃到
        assertThat(isInvalidClient("googleapi: Error 403: rateLimitExceeded")).isFalse();
    }

    @Test
    void invalidClient行程失敗回傳可行動且不洩漏stderr憑證的訊息() throws Exception {
        ProcessRcloneClient client = new ProcessRcloneClient(new com.fasterxml.jackson.databind.ObjectMapper());
        Method exec = ProcessRcloneClient.class.getDeclaredMethod("exec", List.class, String.class, long.class, String.class);
        exec.setAccessible(true);
        // fake process 只印出固定假 stderr，沒有執行 rclone 或外部 I/O。
        List<String> command = List.of("/bin/sh", "-c",
                "printf '%s' 'Invalid_Client access_token=FAKE_ACCESS refresh_token=FAKE_REFRESH client_secret=FAKE_SECRET' >&2; exit 1");
        try {
            exec.invoke(client, command, "GDriveOutput", 5L, "上傳");
            org.junit.jupiter.api.Assertions.fail("expected invalid_client failure");
        } catch (java.lang.reflect.InvocationTargetException failure) {
            assertThat(failure.getCause()).isInstanceOf(RcloneClient.RcloneUnavailableException.class);
            assertThat(failure.getCause().getMessage()).contains(
                    "client_id/secret", "Google 拒絕", "invalid_client", "GOOGLE_CLIENT_ID/GOOGLE_CLIENT_SECRET",
                    ".env", "未同步更新", "自動偵測", "不需要重建容器", "GCP Console", "刪除", "停用",
                    "rclone config reconnect GDriveOutput:")
                    .doesNotContain("FAKE_ACCESS", "FAKE_REFRESH", "FAKE_SECRET", "access_token=", "refresh_token=");
        }
    }
}
