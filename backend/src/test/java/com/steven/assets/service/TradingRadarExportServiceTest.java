package com.steven.assets.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.security.CurrentUserContext;
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
 * 覆蓋多快照三分頁、時間欄為文字、缺漏彙總、空區間、from>to。
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
    void 多快照產出三分頁_時間欄為文字_缺漏彙總可見() throws Exception {
        when(currentUserContext.getEffectiveUserId()).thenReturn(1L);
        JsonNode n1 = snap("2026-07-20T10:00:00+08:00", "NEUTRAL", 2);
        JsonNode n2 = snap("2026-07-20T13:30:00+08:00", "RISK_ON", 1);
        // indexCount 3、查得 2 → 缺漏 1
        when(store.range(eq(1L), anyLong(), anyLong()))
                .thenReturn(new TradingRadarSnapshotStore.SnapshotRange(List.of(n1, n2), 3, 1));

        byte[] data = service.export("2026-07-20T00:00:00", "2026-07-20T23:59:59");

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(3);
            Sheet idx = wb.getSheet("快照索引");
            assertThat(idx).isNotNull();
            assertThat(idx.getRow(0).getCell(0).getStringCellValue()).contains("缺漏 1");
            // 個股決策：表頭 1 列 + 資料 3 列（2+1 檔）
            Sheet stock = wb.getSheet("個股決策");
            assertThat(stock.getLastRowNum()).isEqualTo(3);
            // 快照時間欄為文字（非數值 cell）
            assertThat(stock.getRow(1).getCell(0).getStringCellValue()).isEqualTo("2026-07-20T10:00:00+08:00");
            assertThat(wb.getSheet("大盤總覽")).isNotNull();
        }
    }

    @Test
    void 空區間回含表頭的合法檔() throws Exception {
        when(currentUserContext.getEffectiveUserId()).thenReturn(1L);
        when(store.range(eq(1L), anyLong(), anyLong()))
                .thenReturn(new TradingRadarSnapshotStore.SnapshotRange(List.of(), 0, 0));

        byte[] data = service.export("2026-07-20T00:00:00", "2026-07-20T23:59:59");

        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(data))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(3);
            assertThat(wb.getSheet("快照索引").getRow(0).getCell(0).getStringCellValue()).contains("查無");
        }
    }

    @Test
    void 起始晚於結束回400語意的IllegalArgument() {
        assertThatThrownBy(() -> service.export("2026-07-20T13:00:00", "2026-07-20T09:00:00"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 時間格式錯誤丟IllegalArgument() {
        assertThatThrownBy(() -> service.export("not-a-date", "2026-07-20T09:00:00"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
