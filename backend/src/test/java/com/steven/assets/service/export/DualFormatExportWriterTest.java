package com.steven.assets.service.export;

import com.steven.assets.service.GdriveOutputSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DualFormatExportWriter} 的單元測試（Requirement 55 / Task 269）。
 *
 * <p>重點：主檔名一致性、一份失敗另一份仍寫出、狀態欄截斷、Drive 兩份且可分辨、路徑逃脫。
 */
class DualFormatExportWriterTest {

    private static final byte[] XLSX = "xlsx-content".getBytes(StandardCharsets.UTF_8);
    private static final byte[] JSON = "{\"a\":1}".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path tmp;

    private GdriveOutputSupport gdrive;
    private DualFormatExportWriter writer;

    @BeforeEach
    void setUp() {
        gdrive = mock(GdriveOutputSupport.class);
        writer = new DualFormatExportWriter(gdrive);
    }

    private DualFormatExportWriter.DualResult write(String baseName) throws IOException {
        return writer.write(1L, tmp, baseName, JSON, XLSX, false, null);
    }

    private static String stripExt(Path p) {
        String n = p.getFileName().toString();
        return n.substring(0, n.lastIndexOf('.'));
    }

    @Test
    @DisplayName("兩份落點的主檔名逐字元相同，只差副檔名")
    void 主檔名一致() throws Exception {
        var r = write("資產總覽_1_20260801");
        assertThat(stripExt(r.jsonFile())).isEqualTo(stripExt(r.xlsxFile()));
        assertThat(r.jsonFile().getFileName().toString()).endsWith(".json");
        assertThat(r.xlsxFile().getFileName().toString()).endsWith(".xlsx");
        assertThat(Files.readAllBytes(r.xlsxFile())).isEqualTo(XLSX);
        assertThat(Files.readAllBytes(r.jsonFile())).isEqualTo(JSON);
    }

    @Test
    @DisplayName("寫完不留任何 .tmp")
    void 不留暫存檔() throws Exception {
        write("交易紀錄_1_20260801");
        try (Stream<Path> s = Files.list(tmp)) {
            assertThat(s.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".tmp"));
        }
    }

    @Test
    @DisplayName("baseName 含路徑分隔字元或 .. 即擲例外，且目錄內不產生任何檔")
    void 路徑逃脫被擋下() {
        for (String bad : List.of("a/b", "a\\b", "../x", "..")) {
            assertThatThrownBy(() -> write(bad))
                    .as("baseName=%s", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(tmp.toFile().list()).isEmpty();
    }

    @Test
    @DisplayName("jsonBytes 為 null（render 失敗）時 xlsx 仍寫出，且不擲例外")
    void json_render失敗時xlsx仍寫出() throws Exception {
        var r = writer.write(1L, tmp, "已實現損益_1_20260801", null, XLSX, false, null);
        assertThat(r.xlsxFile()).isNotNull();
        assertThat(Files.exists(r.xlsxFile())).isTrue();
        assertThat(r.jsonFile()).as("失敗那一份的 Path 回 null").isNull();
        assertThat(r.localStatus()).contains("xlsx 成功").contains("json render 失敗");
    }

    @Test
    @DisplayName("xlsxBytes 為 null 時 json 仍寫出")
    void xlsx_render失敗時json仍寫出() throws Exception {
        var r = writer.write(1L, tmp, "已實現損益_1_20260801", JSON, null, false, null);
        assertThat(r.jsonFile()).isNotNull();
        assertThat(r.xlsxFile()).isNull();
        assertThat(r.localStatus()).contains("json 成功").contains("xlsx render 失敗");
    }

    @Test
    @DisplayName("兩份都是 null 時不寫任何檔、不擲例外")
    void 兩份都失敗仍不擲例外() throws Exception {
        assertThatCode(() -> {
            var r = writer.write(1L, tmp, "x", null, null, false, null);
            assertThat(r.jsonFile()).isNull();
            assertThat(r.xlsxFile()).isNull();
        }).doesNotThrowAnyException();
        assertThat(tmp.toFile().list()).isEmpty();
    }

    @Test
    @DisplayName("json 寫檔失敗（目標路徑被同名目錄占住）時 xlsx 仍寫出、方法不擲例外")
    void 寫檔失敗不影響另一份() throws Exception {
        String base = "油價金價_1_20260801";
        // 用「同名目錄」而非唯讀檔：rename(2) 的權限檢查在目錄不在目標檔，
        // 唯讀的目標檔會被直接覆蓋成功，測不到東西。
        Files.createDirectory(tmp.resolve(base + ".json"));

        var r = write(base);
        assertThat(r.xlsxFile()).isNotNull();
        assertThat(Files.exists(r.xlsxFile())).isTrue();
        assertThat(r.jsonFile()).isNull();
        assertThat(r.localStatus()).contains("xlsx 成功").contains("json 失敗");
        try (Stream<Path> s = Files.list(tmp)) {
            assertThat(s.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".tmp"));
        }
    }

    @Test
    @DisplayName("狀態欄超長時截斷至 500／512 內，且仍保留成功字樣與副檔名標記")
    void 狀態欄截斷() throws Exception {
        // baseName 取 200 字元：加副檔名後 205 < NAME_MAX(255)，兩份都寫得出來；
        // localStatus 含兩個完整路徑（各 >250 字元）必然超過 500，才測得到「成功狀態被截斷」那條路徑。
        // 刻意不用深目錄撐長絕對路徑——macOS 的 PATH_MAX 是 1024（不是 Linux 的 4096），會先炸在建目錄。
        String longBase = "資".repeat(100) + "_1_20260801";
        assertThat(longBase.length() + ".xlsx".length()).isLessThan(255);

        String longSubpath = "s".repeat(600);
        when(gdrive.syncQuietly(anyLong(), anyString(), any()))
                .thenReturn(new GdriveOutputSupport.SyncResult("成功：" + "p".repeat(600) + "（1 bytes）", "p"));

        var r = writer.write(1L, tmp, longBase, JSON, XLSX, true, longSubpath);

        assertThat(r.xlsxFile()).isNotNull();
        assertThat(r.jsonFile()).isNotNull();
        assertThat(r.localStatus().length()).isLessThanOrEqualTo(500);
        assertThat(r.gdriveStatus().length()).isLessThanOrEqualTo(512);
        assertThat(r.localStatus()).contains("xlsx 成功");
        // 兩半各自先截斷再合併：合併後才從尾端截，會把 ／json 那一整段切掉，
        // 讓「必須能分辨是哪一份」的契約在長路徑下失效。
        assertThat(r.localStatus()).as("json 那一半不得被整段切掉").contains("／json ");
        assertThat(r.gdriveStatus()).startsWith("xlsx ").contains("／json ");
    }

    @Test
    @DisplayName("啟用 Drive 時上傳兩份、子路徑相同、副檔名各一，狀態能分辨哪一份")
    void drive上傳兩份() throws Exception {
        when(gdrive.syncQuietly(anyLong(), anyString(), any()))
                .thenReturn(new GdriveOutputSupport.SyncResult("成功：X（1 bytes）", "remote:X"))
                .thenReturn(new GdriveOutputSupport.SyncResult("失敗：quota", null));

        var r = writer.write(1L, tmp, "交易雷達_1_20260801", JSON, XLSX, true, "資產管理");

        ArgumentCaptor<String> sub = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Path> files = ArgumentCaptor.forClass(Path.class);
        verify(gdrive, times(2)).syncQuietly(anyLong(), sub.capture(), files.capture());

        assertThat(sub.getAllValues()).containsExactly("資產管理", "資產管理");
        assertThat(files.getAllValues().stream().map(p -> p.getFileName().toString()))
                .anyMatch(n -> n.endsWith(".xlsx"))
                .anyMatch(n -> n.endsWith(".json"));
        // 字串契約：xlsx <status>／json <status>
        assertThat(r.gdriveStatus()).isEqualTo("xlsx 成功：X（1 bytes）／json 失敗：quota");
        assertThat(r.xlsxGdrivePath()).isEqualTo("remote:X");
        assertThat(r.jsonGdrivePath()).isNull();
    }

    @Test
    @DisplayName("本機未兩份皆成功時完全不上傳")
    void 本機未兩份成功則不上傳() throws Exception {
        var r = writer.write(1L, tmp, "交易雷達_1_20260801", null, XLSX, true, "資產管理");
        verify(gdrive, never()).syncQuietly(anyLong(), anyString(), any());
        assertThat(r.gdriveStatus()).contains("略過");
    }

    @Test
    @DisplayName("未啟用 Drive 時零呼叫，狀態與落點欄位皆 null")
    void 未啟用drive() throws Exception {
        var r = write("油價金價_1_20260801");
        verify(gdrive, never()).syncQuietly(anyLong(), anyString(), any());
        assertThat(r.gdriveStatus()).isNull();
        assertThat(r.xlsxGdrivePath()).isNull();
        assertThat(r.jsonGdrivePath()).isNull();
    }
}
