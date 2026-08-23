package com.steven.assets.repository;

import com.steven.assets.service.DividendBackfillLedger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 檔案式的回補進度／稽核帳（Task 357.3c）。
 *
 * <p>兩個 append-only 檔案：</p>
 * <ul>
 *   <li>{@code completed.tsv}：一行一個已完成的 {@code market|code}，續跑時據此跳過。</li>
 *   <li>{@code report.tsv}：一行一筆稽核紀錄（{@code OK}／{@code FAIL}／{@code CORRECTION}／
 *       {@code SUMMARY}），前綴 UTC 時間戳。357.3b 的「舊除息日其實是除權日」逐檔清單就從
 *       這個檔案的 {@code CORRECTION} 行整理。</li>
 * </ul>
 *
 * <p><b>為什麼是檔案不是資料表</b>：見 {@link DividendBackfillLedger} 的 Javadoc。目錄一律
 * <b>延遲建立</b>——回補預設關閉時本 bean 仍會被建立，建構當下就去碰檔案系統會在完全用不到
 * 它的正常啟動路徑上留下空目錄。</p>
 */
@Slf4j
@Repository
public class FileDividendBackfillLedger implements DividendBackfillLedger {

    private final Path stateDir;
    private final Path completedFile;
    private final Path reportFile;
    private final AtomicBoolean announced = new AtomicBoolean();

    public FileDividendBackfillLedger(
            @Value("${app.dividend-backfill.state-dir:"
                    + "${EXPORT_OUTPUT_DIR:/home/steven}/dividend-backfill-t357}") String stateDir) {
        this.stateDir = Path.of(stateDir);
        this.completedFile = this.stateDir.resolve("completed.tsv");
        this.reportFile = this.stateDir.resolve("report.tsv");
    }

    /** 回補產出的檔案位置，供啟動時記進日誌（主 agent 事後要讀這兩個檔）。 */
    public Path stateDir() {
        return stateDir;
    }

    @Override
    public Set<String> completedKeys() {
        if (!Files.isRegularFile(completedFile)) return Set.of();
        try {
            List<String> lines = Files.readAllLines(completedFile, StandardCharsets.UTF_8);
            Set<String> keys = new LinkedHashSet<>();
            for (String line : lines) {
                String trimmed = line.strip();
                if (!trimmed.isEmpty()) keys.add(trimmed);
            }
            return keys;
        } catch (IOException e) {
            // 刻意不降級為空集合：那會讓已完成的標的整批重抓，正是 357.3c 要避免的額度浪費。
            throw new UncheckedIOException("無法讀取回補進度檔：" + completedFile, e);
        }
    }

    @Override
    public void markCompleted(String key) {
        append(completedFile, key);
    }

    @Override
    public void appendLine(String line) {
        append(reportFile, Instant.now() + "\t" + line);
    }

    private void append(Path file, String line) {
        try {
            if (announced.compareAndSet(false, true)) {
                log.info("配息四日期回補的進度與稽核帳落點：{}（completed.tsv／report.tsv）", stateDir);
            }
            Files.createDirectories(stateDir);
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("無法寫入回補紀錄檔：" + file, e);
        }
    }
}
