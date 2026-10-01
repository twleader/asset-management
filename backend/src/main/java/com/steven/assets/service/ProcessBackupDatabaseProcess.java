package com.steven.assets.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** 生產環境的 pg_dump / pg_restore 白名單子行程。 */
@Component
final class ProcessBackupDatabaseProcess implements BackupDatabaseProcess {

    private static final long DUMP_TIMEOUT_SECONDS = 300;
    private static final long RESTORE_TIMEOUT_SECONDS = 900;
    private static final AtomicBoolean PROCESS_CLEANUP_UNSAFE = new AtomicBoolean();

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
    public void validate(Path inputFile) {
        run(List.of("pg_restore", "--list", inputFile.toString()), null, "pg_restore --list");
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
        if (PROCESS_CLEANUP_UNSAFE.get()) throw new IllegalStateException("資料庫備份子行程狀態未定，已停止後續操作");
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
            long maximum = "pg_restore".equals(label) ? RESTORE_TIMEOUT_SECONDS : DUMP_TIMEOUT_SECONDS;
            long timeoutSeconds = BackupWorkflowDeadline.commandSeconds(maximum);
            process = builder.start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                terminateAndWait(process);
                throw new IllegalStateException(label + " 執行逾時");
            }
            if (process.exitValue() != 0) {
                // stderr 可能含資料庫細節；只回固定摘要，不把原始內容帶進 exception/log。
                throw new IllegalStateException(label + " 執行失敗");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (process != null) {
                terminateAndWait(process);
            }
            throw new IllegalStateException(label + " 執行被中斷");
        } catch (IOException | UnsupportedOperationException | SecurityException failure) {
            if (process != null) {
                terminateAndWait(process);
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

    private static void terminateAndWait(Process process) {
        List<ProcessHandle> descendants = new ArrayList<>(process.toHandle().descendants().toList());
        for (ProcessHandle child : descendants) child.destroyForcibly();
        process.destroyForcibly();
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while ((process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive))
                && System.nanoTime() < deadline) {
            try {
                process.waitFor(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (process.isAlive() || descendants.stream().anyMatch(ProcessHandle::isAlive)) {
            PROCESS_CLEANUP_UNSAFE.set(true);
            throw new IllegalStateException("資料庫備份子行程無法安全停止");
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
}
