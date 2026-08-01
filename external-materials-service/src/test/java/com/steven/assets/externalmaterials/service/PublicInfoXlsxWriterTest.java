package com.steven.assets.externalmaterials.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.externalmaterials.client.NewsRow;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 公開資訊 Excel 轉換的單元測試（Requirement 55 / Task 272）。
 *
 * <p>重點：表頭必須由 items <b>動態推導</b>（硬編會在 {@code NewsRow} 加欄位時靜默少一欄）、
 * items 為空時仍產出含 metadata 的合法檔、且欄位集合與 JSON 那一份一致（含 {@code @JsonIgnore} 的排除）。
 */
class PublicInfoXlsxWriterTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    private final PublicInfoXlsxWriter writer = new PublicInfoXlsxWriter(mapper);

    private static Map<String, Object> payload(List<?> items) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("generatedAt", "2026-08-01T08:00:00+08:00");
        p.put("trigger", "cron");
        p.put("tradingDayCutoff", "2026-07-31");
        p.put("count", items.size());
        p.put("items", items);
        return p;
    }

    private Sheet read(byte[] bytes) throws Exception {
        Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes));
        return wb.getSheetAt(0);
    }

    @Test
    @DisplayName("metadata 四列 ＋ 空行 ＋ items 表；分頁名為「公開資訊」")
    void 版面結構() throws Exception {
        NewsRow row = new NewsRow("台積電法說會", "ltn", "https://x/1", "finance", "tw",
                "摘要", Instant.parse("2026-07-31T05:00:00Z"), List.of("半導體"));
        Sheet s = read(writer.build(payload(List.of(row))));

        assertThat(s.getSheetName()).isEqualTo("公開資訊");
        assertThat(s.getRow(0).getCell(0).getStringCellValue()).isEqualTo("generatedAt");
        assertThat(s.getRow(1).getCell(0).getStringCellValue()).isEqualTo("trigger");
        assertThat(s.getRow(2).getCell(0).getStringCellValue()).isEqualTo("tradingDayCutoff");
        assertThat(s.getRow(3).getCell(0).getStringCellValue()).isEqualTo("count");
        assertThat(s.getRow(3).getCell(1).getNumericCellValue()).isEqualTo(1.0);
        assertThat(s.getRow(4)).as("空行不建 Row").isNull();

        Row header = s.getRow(5);
        List<String> headers = new ArrayList<>();
        for (int i = 0; i < header.getLastCellNum(); i++) headers.add(header.getCell(i).getStringCellValue());
        assertThat(headers).contains("title", "source", "url", "category", "region", "summary", "publishedAt");
        assertThat(headers).as("tags 標了 @JsonIgnore，JSON 沒有、Excel 也不該有").doesNotContain("tags");

        assertThat(s.getRow(6).getCell(headers.indexOf("title")).getStringCellValue()).isEqualTo("台積電法說會");
    }

    @Test
    @DisplayName("表頭由 items 動態推導：第二筆多出來的欄位也要進表頭，第一筆該格為空")
    void 表頭動態推導() throws Exception {
        // 用 Map 直接構造，模擬「日後 NewsRow 加欄位」的情形
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("title", "A"); a.put("source", "ltn");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("title", "B"); b.put("source", "udn"); b.put("sentiment", "positive");

        Sheet s = read(writer.build(payload(List.of(a, b))));
        Row header = s.getRow(5);
        List<String> headers = new ArrayList<>();
        for (int i = 0; i < header.getLastCellNum(); i++) headers.add(header.getCell(i).getStringCellValue());

        assertThat(headers).as("只取第一個元素 keys 的壞實作會少 sentiment 這一欄")
                .containsExactly("title", "source", "sentiment");
        int idx = headers.indexOf("sentiment");
        assertThat(s.getRow(6).getCell(idx).getCellType()).as("第一筆缺該欄 → BLANK 格").isEqualTo(CellType.BLANK);
        assertThat(s.getRow(7).getCell(idx).getStringCellValue()).isEqualTo("positive");
    }

    @Test
    @DisplayName("items 為空時仍產出含 metadata 的合法檔，且不擲例外")
    void 空清單仍產出合法檔() throws Exception {
        byte[] bytes = writer.build(payload(List.of()));
        assertThatCode(() -> read(bytes)).doesNotThrowAnyException();

        Sheet s = read(bytes);
        assertThat(s.getRow(0).getCell(0).getStringCellValue()).isEqualTo("generatedAt");
        assertThat(s.getRow(3).getCell(1).getNumericCellValue()).isEqualTo(0.0);
        assertThat(s.getRow(4)).isNull();
        assertThat(s.getRow(5)).as("表頭列仍在（雖然無欄位）").isNotNull();
    }

    @Test
    @DisplayName("metadata 的 count 是數值格、其餘是文字格")
    void metadata型別() throws Exception {
        Sheet s = read(writer.build(payload(List.of())));
        assertThat(s.getRow(0).getCell(1).getCellType()).isEqualTo(CellType.STRING);
        assertThat(s.getRow(3).getCell(1).getCellType()).isEqualTo(CellType.NUMERIC);
    }
}
