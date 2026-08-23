package com.steven.assets.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Task 357.3c：續跑進度與稽核帳的檔案落地行為。
 *
 * <p>「續跑」的本質是「新的 process 讀得到上一個 process 寫下的進度」，因此每個測試都
 * <b>另外 new 一個 ledger 實例</b>來讀，而不是重用同一個物件的記憶體狀態。</p>
 */
class FileDividendBackfillLedgerTest {

    @Test
    @DisplayName("尚未跑過時回空集合，且不得在建構當下就建立目錄")
    void returnsEmptyBeforeAnyRunAndDoesNotTouchDiskOnConstruction(@TempDir Path tempDir) {
        Path stateDir = tempDir.resolve("dividend-backfill-t357");

        FileDividendBackfillLedger ledger = new FileDividendBackfillLedger(stateDir.toString());

        assertThat(Files.exists(stateDir)).isFalse();
        assertThat(ledger.completedKeys()).isEmpty();
        assertThat(Files.exists(stateDir)).isFalse();
    }

    @Test
    @DisplayName("已完成的標的跨實例可見（recreate 容器後仍能續跑）")
    void completedKeysSurviveANewInstance(@TempDir Path tempDir) {
        Path stateDir = tempDir.resolve("state");
        FileDividendBackfillLedger first = new FileDividendBackfillLedger(stateDir.toString());
        first.markCompleted("台股|2881");
        first.markCompleted("美股|AAPL");

        FileDividendBackfillLedger second = new FileDividendBackfillLedger(stateDir.toString());

        assertThat(second.completedKeys()).containsExactly("台股|2881", "美股|AAPL");
    }

    @Test
    @DisplayName("稽核行逐筆 append 並帶時間戳，供整理 357.3b 的修正清單")
    void appendsAuditLinesWithTimestamps(@TempDir Path tempDir) throws IOException {
        Path stateDir = tempDir.resolve("state");
        FileDividendBackfillLedger ledger = new FileDividendBackfillLedger(stateDir.toString());

        ledger.appendLine("OK\t台股\t2881\tcorrections=1");
        ledger.appendLine("CORRECTION\t台股\t2881\tyear=2025\tmovedDate=2025-09-25");

        List<String> lines =
                Files.readAllLines(stateDir.resolve("report.tsv"), StandardCharsets.UTF_8);
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).endsWith("OK\t台股\t2881\tcorrections=1");
        assertThat(lines.get(1)).contains("CORRECTION\t台股\t2881");
        // 時間戳在最前面、以 tab 與內容分隔。
        assertThat(lines.get(0).split("\t")[0]).isNotBlank();
    }

    @Test
    @DisplayName("進度檔的空行與前後空白不得被當成標的 key")
    void ignoresBlankLinesInTheProgressFile(@TempDir Path tempDir) throws IOException {
        Path stateDir = tempDir.resolve("state");
        Files.createDirectories(stateDir);
        Files.writeString(stateDir.resolve("completed.tsv"),
                "台股|2881\n\n  台股|2885  \n\n", StandardCharsets.UTF_8);

        FileDividendBackfillLedger ledger = new FileDividendBackfillLedger(stateDir.toString());

        assertThat(ledger.completedKeys()).containsExactly("台股|2881", "台股|2885");
    }
}
