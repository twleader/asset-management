package com.steven.assets.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.security.CurrentUserContext;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * TradingRadarExportService 單元測試（Requirement 48）。
 * 覆蓋多快照四分頁、時間欄為文字、缺漏彙總、空區間、from>to。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TradingRadarExportServiceTest {

    @Mock private TradingRadarSnapshotStore store;
    @Mock private CurrentUserContext currentUserContext;

    /**
     * <b>刻意注入真的 {@link com.steven.assets.service.export.ExcelDocRenderer}（不是 mock）</b>：
     * 本測試驗的是實際產出的三分頁版面，換成 mock 就什麼都驗不到。
     * Requirement 55 / Task 271 讓本 service 多了這個依賴，{@code @InjectMocks} 對未宣告成
     * {@code @Mock} 的欄位會塞 null，故用 {@code @Spy} 提供真實實例。<b>下方斷言一字不得改。</b>
     */
    @org.mockito.Spy
    private com.steven.assets.service.export.ExcelDocRenderer excelDocRenderer =
            new com.steven.assets.service.export.ExcelDocRenderer();

    @InjectMocks private TradingRadarExportService service;

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode snap(String gen, String regime, int stockCount) throws Exception {
        StringBuilder stocks = new StringBuilder("[");
        for (int i = 0; i < stockCount; i++) {
            if (i > 0) stocks.append(",");
            stocks.append("{\"stockCode\":\"233").append(i).append("\",\"action\":\"HOLD\",\"score\":60,\"held\":true}");
        }
        stocks.append("]");
        String json = "{\"ruleVersion\":\"TW_RULES_V5\",\"generatedAt\":\"" + gen + "\","
                + "\"market\":{\"regime\":\"" + regime + "\",\"regimeLabel\":\"中性\",\"score\":40,\"stale\":false},"
                + "\"stocks\":" + stocks + ",\"skippedNonTwStocks\":2}";
        return mapper.readTree(json);
    }

    @Test
    void 多快照產出四分頁_時間欄為文字_缺漏彙總可見() throws Exception {
        when(currentUserContext.getEffectiveUserId()).thenReturn(1L);
        JsonNode n1 = snap("2026-07-20T10:00:00+08:00", "NEUTRAL", 2);
        JsonNode n2 = snap("2026-07-20T13:30:00+08:00", "RISK_ON", 1);
        // indexCount 3、查得 2 → 缺漏 1
        when(store.range(eq(1L), anyLong(), anyLong()))
                .thenReturn(new TradingRadarSnapshotStore.SnapshotRange(List.of(n1, n2), 3, 1));

        byte[] data = excelDocRenderer.render(
                service.manualDoc("2026-07-20T00:00:00", "2026-07-20T23:59:59").doc());

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(4);
            Sheet idx = wb.getSheet("快照索引");
            assertThat(idx).isNotNull();
            assertThat(idx.getRow(0).getCell(0).getStringCellValue()).contains("缺漏 1");
            assertThat(idx.getRow(1).getCell(2).getStringCellValue()).isEqualTo("動作政策版本");
            assertThat(idx.getRow(2).getCell(2).getStringCellValue())
                    .as("舊快照缺欄必須留空，不得從 ruleVersion 推導")
                    .isEmpty();
            // 個股決策：表頭 1 列 + 資料 3 列（2+1 檔）
            Sheet stock = wb.getSheet("個股決策");
            assertThat(stock.getLastRowNum()).isEqualTo(3);
            // 快照時間欄為文字（非數值 cell）
            assertThat(stock.getRow(1).getCell(0).getStringCellValue()).isEqualTo("2026-07-20T10:00:00+08:00");
            assertThat(wb.getSheet("大盤總覽")).isNotNull();
            assertThat(wb.getSheet("台美公開資訊")).isNotNull();
        }
    }

    /**
     * Task 357／357.6d：「下一配息」四個日期欄必須出現在表頭最末，且值來自
     * {@code evidence.nextExDividendDate} 等四個新欄位、缺值為空白格而非 0 或字串 "null"。
     * {@link com.steven.assets.service.export.ExportDoc.Table} 的建構期驗證
     * （headers／columnFormats／每列 cell 數必須三者相等，見 ExportDoc.java）已經是本斷言
     * 的機械保證：只要下方任何一個長度對不上，{@code service.manualDoc(...)} 本身就會先
     * 拋 {@code IllegalArgumentException}，本測試再額外驗證「值真的落在正確欄位」。
     */
    @Test
    void 下一配息四個日期欄出現在表頭最末且值正確對齊() throws Exception {
        when(currentUserContext.getEffectiveUserId()).thenReturn(1L);
        String json = "{\"ruleVersion\":\"TW_RULES_V5\",\"generatedAt\":\"2026-08-22T09:00:00+08:00\","
                + "\"market\":{\"regime\":\"NEUTRAL\",\"regimeLabel\":\"中性\",\"score\":40,\"stale\":false},"
                + "\"stocks\":[{\"stockCode\":\"2885\",\"action\":\"HOLD\",\"score\":60,\"held\":true,"
                + "\"evidence\":{\"nextExDividendDate\":null,\"nextExRightsDate\":\"2026-09-10\","
                + "\"nextCashPaymentDate\":null,\"nextStockPaymentDate\":\"2026-10-05\"}}],"
                + "\"skippedNonTwStocks\":0}";
        when(store.range(eq(1L), anyLong(), anyLong()))
                .thenReturn(new TradingRadarSnapshotStore.SnapshotRange(
                        List.of(mapper.readTree(json)), 1, 0));

        byte[] data = excelDocRenderer.render(
                service.manualDoc("2026-08-22T00:00:00", "2026-08-22T23:59:59").doc());

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            Sheet stock = wb.getSheet("個股決策");
            org.apache.poi.ss.usermodel.Row header = stock.getRow(0);
            int lastCol = header.getLastCellNum() - 1;
            // 四個新欄一律緊鄰在整張表最末四格。
            assertThat(header.getCell(lastCol - 3).getStringCellValue()).isEqualTo("下一除息日");
            assertThat(header.getCell(lastCol - 2).getStringCellValue()).isEqualTo("下一除權日");
            assertThat(header.getCell(lastCol - 1).getStringCellValue()).isEqualTo("下一發放股息日");
            assertThat(header.getCell(lastCol).getStringCellValue()).isEqualTo("下一發放股權日");

            org.apache.poi.ss.usermodel.Row dataRow = stock.getRow(1);
            assertThat(dataRow.getCell(lastCol - 3).getCellType())
                    .as("nextExDividendDate 缺值必須是 BLANK 而非 0").isEqualTo(CellType.BLANK);
            assertThat(dataRow.getCell(lastCol - 2).getStringCellValue()).isEqualTo("2026-09-10");
            assertThat(dataRow.getCell(lastCol - 1).getCellType())
                    .as("nextCashPaymentDate 缺值必須是 BLANK 而非 0").isEqualTo(CellType.BLANK);
            assertThat(dataRow.getCell(lastCol).getStringCellValue()).isEqualTo("2026-10-05");
        }
    }

    @Test
    void 空區間回含表頭的合法檔() throws Exception {
        when(currentUserContext.getEffectiveUserId()).thenReturn(1L);
        when(store.range(eq(1L), anyLong(), anyLong()))
                .thenReturn(new TradingRadarSnapshotStore.SnapshotRange(List.of(), 0, 0));

        byte[] data = excelDocRenderer.render(
                service.manualDoc("2026-07-20T00:00:00", "2026-07-20T23:59:59").doc());

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(4);
            assertThat(wb.getSheet("快照索引").getRow(0).getCell(0).getStringCellValue()).contains("查無");
        }
    }

    @Test
    void 起始晚於結束回400語意的IllegalArgument() {
        assertThatThrownBy(() -> service.manualDoc("2026-07-20T13:00:00", "2026-07-20T09:00:00"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 時間格式錯誤丟IllegalArgument() {
        assertThatThrownBy(() -> service.manualDoc("not-a-date", "2026-07-20T09:00:00"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
