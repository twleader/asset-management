package com.steven.assets.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 生產環境的 pg_dump / pg_restore 白名單子行程。 */
@Component
final class ProcessBackupDatabaseProcess implements BackupDatabaseProcess {

    private static final long PROCESS_TIMEOUT_SECONDS = 300;

    private final String dbHost;
    private final String dbPort;
    private final String dbName;
    private final String dbUser;
    private final String dbPassword;

    ProcessBackupDatabaseProcess(
            @Value("${DB_HOST:postgres}") String dbHost,
            @Value("${DB_PORT:5432}") String dbPort,
            @Value("${DB_NAME}") String dbName,
            @Value("${DB_USERNAME}") String dbUser,
            @Value("${DB_PASSWORD}") String dbPassword) {
        this.dbHost = dbHost;
        this.dbPort = dbPort;
        this.dbName = dbName;
        this.dbUser = dbUser;
        this.dbPassword = dbPassword;
    }

    @Override
    public void dump(Path outputFile) {
        run(List.of(
                "pg_dump",
                "-h", dbHost,
                "-p", dbPort,
                "-U", dbUser,
                "-d", dbName,
                "--format=custom",
                "--compress=9",
                "--no-owner",
                "--no-acl"
        ), outputFile, "pg_dump");
    }

    @Override
    public void restore(Path inputFile) {
        run(List.of(
                "pg_restore",
                "-h", dbHost,
                "-p", dbPort,
                "-U", dbUser,
                "-d", dbName,
                "--clean",
                "--if-exists",
                "--no-owner",
                "--no-acl",
                inputFile.toString()
        ), null, "pg_restore");
    }

    private void run(List<String> command, Path stdoutFile, String label) {
        Path stderrFile = null;
        Process process = null;
        try {
            stderrFile = Files.createTempFile("backup-pg-err-", ".tmp");
            Files.setPosixFilePermissions(stderrFile, PosixFilePermissions.fromString("rw-------"));
            ProcessBuilder builder = new ProcessBuilder(command);
            builder.environment().put("PGPASSWORD", dbPassword);
            if (stdoutFile == null) {
                builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            } else {
                builder.redirectOutput(stdoutFile.toFile());
            }
            builder.redirectError(stderrFile.toFile());
            process = builder.start();
            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
                throw new IllegalStateException(label + " 執行逾時");
            }
            if (process.exitValue() != 0) {
                // stderr 可能含資料庫細節；只回固定摘要，不把原始內容帶進 exception/log。
                throw new IllegalStateException(label + " 執行失敗");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            throw new IllegalStateException(label + " 執行被中斷");
        } catch (IOException | UnsupportedOperationException | SecurityException failure) {
            if (process != null) {
                process.destroyForcibly();
            }
            throw new IllegalStateException(label + " 無法執行");
        } finally {
            if (stderrFile != null) {
                try {
                    Files.deleteIfExists(stderrFile);
                } catch (IOException ignored) {
                    // 不讓清理錯誤覆蓋主要結果。
                }
            }
        }
    }
}
