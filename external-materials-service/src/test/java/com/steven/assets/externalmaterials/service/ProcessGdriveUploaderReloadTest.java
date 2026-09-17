package com.steven.assets.externalmaterials.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.*;
import static java.util.concurrent.TimeUnit.SECONDS;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ProcessGdriveUploader} 的 config 重新載入行為，以及 invalid_client 分類判斷
 * （Task 443／Requirement 160）。
 *
 * <p>reload 行為比照 backend {@code ProcessRcloneClientReloadTest} 的測試案例（首次安裝、內容不變不重裝、
 * 內容改變才重裝、source 消失不清空既有 writable、atomic move 語意、併發安全），全程用 {@code @TempDir}
 * 的假 source／writable 路徑，<b>不碰 {@code /etc}、不連網</b>；直接呼叫 package-private 的
 * {@link ProcessGdriveUploader#reloadIfSourceChanged(Path, Path)}（同套件內測試類可直接呼叫，不需要反射）。
 *
 * <p>invalid_client 判斷則比照 backend {@code ProcessRcloneClientInvalidClientTest}，以反射直接呼叫
 * 443.4 新抽出的 private static {@code isInvalidClient}。
 */
class ProcessGdriveUploaderReloadTest {

    private static final String REMOTE = "GDriveOutput";

    /** 2026-09-17 實測的 stderr。 */
    private static final String REAL_INVALID_CLIENT_STDERR =
            "couldn't fetch token: invalid_client: if you're using your own client id/secret, "
            + "make sure they're properly set up following the docs";

    @TempDir
    Path tmp;

    private static ProcessGdriveUploader newUploader() {
        return new ProcessGdriveUploader(REMOTE);
    }

    // ===== reloadIfSourceChanged（比照 443.7／ProcessRcloneClientReloadTest）=====

    @Test
    void 首次呼叫來源存在寫入路徑不存在時安裝成功() throws IOException {
        Path source = tmp.resolve("rclone.conf");
        Path writable = tmp.resolve("rclone-output.conf");
        Files.writeString(source, "[" + REMOTE + "]\ntype = drive\n");
        ProcessGdriveUploader uploader = newUploader();

        boolean reloaded = uploader.reloadIfSourceChanged(source, writable);

        assertThat(reloaded).isTrue();
        assertThat(Files.getPosixFilePermissions(writable)).containsExactlyInAnyOrder(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
        assertThat(Files.readAllBytes(writable)).isEqualTo(Files.readAllBytes(source));
    }

    @Test
    void 內容未變時不重新安裝且不覆蓋外部改寫的writable() throws IOException {
        Path source = tmp.resolve("rclone.conf");
        Path writable = tmp.resolve("rclone-output.conf");
        String sourceContent = "[" + REMOTE + "]\ntype = drive\n";
        Files.writeString(source, sourceContent);
        ProcessGdriveUploader uploader = newUploader();
        assertThat(uploader.reloadIfSourceChanged(source, writable)).isTrue();

        // 模擬 rclone 執行期把續期後的 token 寫回 writable（外部改寫，不動 source）
        String externallyModified = sourceContent + "token = {\"access_token\":\"refreshed\"}\n";
        Files.writeString(writable, externallyModified);

        boolean reloaded = uploader.reloadIfSourceChanged(source, writable);

        assertThat(reloaded).isFalse();
        assertThat(Files.readString(writable, StandardCharsets.UTF_8)).isEqualTo(externallyModified);
    }

    @Test
    void source內容變動後重新安裝且writable更新為新內容() throws IOException {
        Path source = tmp.resolve("rclone.conf");
        Path writable = tmp.resolve("rclone-output.conf");
        Files.writeString(source, "[" + REMOTE + "]\ntype = drive\n");
        ProcessGdriveUploader uploader = newUploader();
        assertThat(uploader.reloadIfSourceChanged(source, writable)).isTrue();

        String newContent = "[" + REMOTE + "]\ntype = drive\nclient_secret = rotated-secret\n";
        Files.writeString(source, newContent);

        boolean reloaded = uploader.reloadIfSourceChanged(source, writable);

        assertThat(reloaded).isTrue();
        assertThat(Files.readString(writable, StandardCharsets.UTF_8)).isEqualTo(newContent);
    }

    @Test
    void source不存在時回傳false且不擲例外() {
        Path source = tmp.resolve("不存在.conf");
        Path writable = tmp.resolve("rclone-output.conf");
        ProcessGdriveUploader uploader = newUploader();

        boolean reloaded = uploader.reloadIfSourceChanged(source, writable);

        assertThat(reloaded).isFalse();
    }

    @Test
    void 安裝後source被刪除時writable內容保留() throws IOException {
        Path source = tmp.resolve("rclone.conf");
        Path writable = tmp.resolve("rclone-output.conf");
        String content = "[" + REMOTE + "]\ntype = drive\n";
        Files.writeString(source, content);
        ProcessGdriveUploader uploader = newUploader();
        assertThat(uploader.reloadIfSourceChanged(source, writable)).isTrue();

        Files.delete(source);

        boolean reloaded = uploader.reloadIfSourceChanged(source, writable);

        assertThat(reloaded).isFalse();
        assertThat(Files.readAllBytes(writable)).isEqualTo(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void 併發呼叫時writable內容完整不partial() throws Exception {
        Path source = tmp.resolve("rclone.conf");
        Path writable = tmp.resolve("rclone-output.conf");
        String content = ("[" + REMOTE + "]\ntype = drive\nclient_secret = abcdef0123456789\n").repeat(200);
        Files.writeString(source, content);
        ProcessGdriveUploader uploader = newUploader();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        Runnable task = () -> {
            try {
                ready.countDown();
                start.await();
                uploader.reloadIfSourceChanged(source, writable);
            } catch (Throwable t) {
                failures.add(t);
            }
        };
        Thread t1 = new Thread(task, "reload-1");
        Thread t2 = new Thread(task, "reload-2");
        t1.start();
        t2.start();
        assertThat(ready.await(5, SECONDS)).isTrue();
        start.countDown();
        t1.join(5000);
        t2.join(5000);
        assertThat(t1.isAlive()).isFalse();
        assertThat(t2.isAlive()).isFalse();

        assertThat(failures).isEmpty();
        assertThat(Files.readAllBytes(writable)).isEqualTo(content.getBytes(StandardCharsets.UTF_8));
    }

    // ===== invalid_client 分類判斷（比照 443.4／ProcessRcloneClientInvalidClientTest）=====

    private static boolean isInvalidClient(String stderr) throws Exception {
        Method m = ProcessGdriveUploader.class.getDeclaredMethod("isInvalidClient", String.class);
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
        assertThat(isInvalidClient("googleapi: Error 403: rateLimitExceeded")).isFalse();
        assertThat(isInvalidClient("")).isFalse();
        assertThat(isInvalidClient(null)).isFalse();
    }

    @Test
    void 原子安裝失敗保留舊檔與fingerprint且清除temp並可重試() throws Exception {
        Path source = tmp.resolve("rclone.conf");
        Path writable = tmp.resolve("rclone-output.conf");
        Files.writeString(source, "old-config");
        ProcessGdriveUploader client = newUploader();
        assertThat(client.reloadIfSourceChanged(source, writable)).isTrue();
        Files.writeString(source, "new-config");
        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.move(any(Path.class), eq(writable),
                    eq(StandardCopyOption.ATOMIC_MOVE), eq(StandardCopyOption.REPLACE_EXISTING)))
                    .thenThrow(new AtomicMoveNotSupportedException("fake", "fake", "offline failure"));
            assertThat(client.reloadIfSourceChanged(source, writable)).isFalse();
        }
        assertThat(Files.readString(writable)).isEqualTo("old-config");
        try (var entries = Files.list(tmp)) {
            assertThat(entries.map(path -> path.getFileName().toString()).toList())
                    .containsExactlyInAnyOrder("rclone.conf", "rclone-output.conf");
        }
        // 成功才更新 fingerprint；失敗後來源相同的下一輪仍必須重試。
        assertThat(client.reloadIfSourceChanged(source, writable)).isTrue();
        assertThat(Files.readString(writable)).isEqualTo("new-config");
    }

    @Test
    void 首次來源缺檔後變可讀下一輪可安裝() throws IOException {
        Path source = tmp.resolve("rclone.conf");
        Path writable = tmp.resolve("rclone-output.conf");
        ProcessGdriveUploader client = newUploader();
        assertThat(client.reloadIfSourceChanged(source, writable)).isFalse();
        Files.writeString(source, "recovered-config");
        assertThat(client.reloadIfSourceChanged(source, writable)).isTrue();
        assertThat(Files.readString(writable)).isEqualTo("recovered-config");
    }

    @Test
    void isAvailable入口會重試而能從初始缺檔自癒並保留既有snapshot() throws IOException {
        Path source = tmp.resolve("rclone.conf");
        Path writable = tmp.resolve("rclone-output.conf");
        // 只轉向離線路徑；isAvailable 本身及 superclass 的 reload 狀態機完全使用正式實作。
        ProcessGdriveUploader uploader = new ProcessGdriveUploader(REMOTE) {
            @Override
            synchronized boolean reloadIfSourceChanged(Path ignoredSource, Path ignoredWritable) {
                return super.reloadIfSourceChanged(source, writable);
            }
        };
        assertThat(uploader.isAvailable()).isFalse();
        Files.writeString(source, "recovered-config");
        assertThat(uploader.isAvailable()).isTrue();
        assertThat(Files.readString(writable)).isEqualTo("recovered-config");
        Files.delete(source);
        assertThat(uploader.isAvailable()).isTrue();
        assertThat(Files.readString(writable)).isEqualTo("recovered-config");
    }

    @Test
    void invalidClient行程失敗回傳可行動且不洩漏stderr憑證的訊息() throws Exception {
        ProcessGdriveUploader client = newUploader();
        Method exec = ProcessGdriveUploader.class.getDeclaredMethod("exec", List.class, String.class);
        exec.setAccessible(true);
        // fake process 只印出固定假 stderr，沒有執行 rclone 或外部 I/O。
        List<String> command = List.of("/bin/sh", "-c",
                "printf '%s' 'Invalid_Client access_token=FAKE_ACCESS refresh_token=FAKE_REFRESH client_secret=FAKE_SECRET' >&2; exit 1");
        try {
            exec.invoke(client, command, "上傳");
            org.junit.jupiter.api.Assertions.fail("expected invalid_client failure");
        } catch (java.lang.reflect.InvocationTargetException failure) {
            assertThat(failure.getCause()).isInstanceOf(RuntimeException.class);
            assertThat(failure.getCause().getMessage()).contains(
                    "client_id/secret", "Google 拒絕", "invalid_client", "GOOGLE_CLIENT_ID/GOOGLE_CLIENT_SECRET",
                    ".env", "未同步更新", "自動偵測", "不需要重建容器", "GCP Console", "刪除", "停用",
                    "rclone config reconnect GDriveOutput:")
                    .doesNotContain("FAKE_ACCESS", "FAKE_REFRESH", "FAKE_SECRET", "access_token=", "refresh_token=");
        }
    }

    @Test
    void 權限設定失敗不得安裝新版憑證快照() throws Exception {
        Path source = tmp.resolve("rclone.conf");
        Path writable = tmp.resolve("rclone-output.conf");
        Files.writeString(source, "old-config");
        ProcessGdriveUploader client = newUploader();
        assertThat(client.reloadIfSourceChanged(source, writable)).isTrue();
        Files.writeString(source, "new-config");
        try (MockedStatic<Files> files = mockStatic(Files.class, CALLS_REAL_METHODS)) {
            files.when(() -> Files.setPosixFilePermissions(any(Path.class), anySet()))
                    .thenThrow(new IOException("offline permissions failure"));
            assertThat(client.reloadIfSourceChanged(source, writable)).isFalse();
        }
        assertThat(Files.readString(writable)).isEqualTo("old-config");
        try (var entries = Files.list(tmp)) {
            assertThat(entries.map(path -> path.getFileName().toString()).toList())
                    .containsExactlyInAnyOrder("rclone.conf", "rclone-output.conf");
        }
        assertThat(client.reloadIfSourceChanged(source, writable)).isTrue();
    }
}
