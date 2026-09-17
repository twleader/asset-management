package com.steven.assets.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
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
 * {@link ProcessRcloneClient} 的 config 重新載入行為（Task 443／Requirement 160）。
 *
 * <p>機制比照 Task 388 {@code ProcessBackupRemoteClient} 已驗證的 source-fingerprint 熱重載，直接呼叫
 * package-private 的 {@link ProcessRcloneClient#reloadIfSourceChanged(Path, Path)}（同套件內測試類可直接
 * 呼叫，不需要反射），全程用 {@code @TempDir} 的假 source／writable 路徑，<b>不碰 {@code /etc}、不連網</b>。
 */
class ProcessRcloneClientReloadTest {

    @TempDir
    Path tmp;

    private ProcessRcloneClient newClient() {
        return new ProcessRcloneClient(new ObjectMapper());
    }

    @Test
    void 首次呼叫來源存在寫入路徑不存在時安裝成功() throws IOException {
        Path source = tmp.resolve("rclone.conf");
        Path writable = tmp.resolve("rclone-output.conf");
        Files.writeString(source, "[GDriveOutput]\ntype = drive\n");
        ProcessRcloneClient client = newClient();

        boolean reloaded = client.reloadIfSourceChanged(source, writable);

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
        String sourceContent = "[GDriveOutput]\ntype = drive\n";
        Files.writeString(source, sourceContent);
        ProcessRcloneClient client = newClient();
        assertThat(client.reloadIfSourceChanged(source, writable)).isTrue();

        // 模擬 rclone 執行期把續期後的 token 寫回 writable（外部改寫，不動 source）
        String externallyModified = sourceContent + "token = {\"access_token\":\"refreshed\"}\n";
        Files.writeString(writable, externallyModified);

        boolean reloaded = client.reloadIfSourceChanged(source, writable);

        assertThat(reloaded).isFalse();
        assertThat(Files.readString(writable, StandardCharsets.UTF_8)).isEqualTo(externallyModified);
    }

    @Test
    void source內容變動後重新安裝且writable更新為新內容() throws IOException {
        Path source = tmp.resolve("rclone.conf");
        Path writable = tmp.resolve("rclone-output.conf");
        Files.writeString(source, "[GDriveOutput]\ntype = drive\n");
        ProcessRcloneClient client = newClient();
        assertThat(client.reloadIfSourceChanged(source, writable)).isTrue();

        String newContent = "[GDriveOutput]\ntype = drive\nclient_secret = rotated-secret\n";
        Files.writeString(source, newContent);

        boolean reloaded = client.reloadIfSourceChanged(source, writable);

        assertThat(reloaded).isTrue();
        assertThat(Files.readString(writable, StandardCharsets.UTF_8)).isEqualTo(newContent);
    }

    @Test
    void source不存在時回傳false且不擲例外() {
        Path source = tmp.resolve("不存在.conf");
        Path writable = tmp.resolve("rclone-output.conf");
        ProcessRcloneClient client = newClient();

        boolean reloaded = client.reloadIfSourceChanged(source, writable);

        assertThat(reloaded).isFalse();
    }

    @Test
    void 安裝後source被刪除時writable內容保留() throws IOException {
        Path source = tmp.resolve("rclone.conf");
        Path writable = tmp.resolve("rclone-output.conf");
        String content = "[GDriveOutput]\ntype = drive\n";
        Files.writeString(source, content);
        ProcessRcloneClient client = newClient();
        assertThat(client.reloadIfSourceChanged(source, writable)).isTrue();

        Files.delete(source);

        boolean reloaded = client.reloadIfSourceChanged(source, writable);

        assertThat(reloaded).isFalse();
        assertThat(Files.readAllBytes(writable)).isEqualTo(content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void 併發呼叫時writable內容完整不partial() throws Exception {
        Path source = tmp.resolve("rclone.conf");
        Path writable = tmp.resolve("rclone-output.conf");
        String content = "[GDriveOutput]\ntype = drive\nclient_secret = abcdef0123456789\n".repeat(200);
        Files.writeString(source, content);
        ProcessRcloneClient client = newClient();

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
        Runnable task = () -> {
            try {
                ready.countDown();
                start.await();
                client.reloadIfSourceChanged(source, writable);
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

    @Test
    void 原子安裝失敗保留舊檔與fingerprint且清除temp並可重試() throws Exception {
        Path source = tmp.resolve("rclone.conf");
        Path writable = tmp.resolve("rclone-output.conf");
        Files.writeString(source, "old-config");
        ProcessRcloneClient client = newClient();
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
        ProcessRcloneClient client = newClient();
        assertThat(client.reloadIfSourceChanged(source, writable)).isFalse();
        Files.writeString(source, "recovered-config");
        assertThat(client.reloadIfSourceChanged(source, writable)).isTrue();
        assertThat(Files.readString(writable)).isEqualTo("recovered-config");
    }

    @Test
    void 權限設定失敗不得安裝新版憑證快照() throws Exception {
        Path source = tmp.resolve("rclone.conf");
        Path writable = tmp.resolve("rclone-output.conf");
        Files.writeString(source, "old-config");
        ProcessRcloneClient client = newClient();
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
