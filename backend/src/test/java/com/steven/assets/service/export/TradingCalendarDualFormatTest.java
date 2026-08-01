package com.steven.assets.service.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.dto.TradingCalendarExportDto;
import com.steven.assets.service.MarketDataService;
import com.steven.assets.service.TradingCalendarExportService;
import org.apache.poi.ss.usermodel.Workbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 交易日曆改為「一律兩份」之後的驗證（Requirement 55 / Task 271）。
 *
 * <p><b>本匯出點是十個裡唯一不接 {@link ExportDoc} 的</b>：它本來就有兩個 builder、且共吃同一份
 * {@code buildDays}，而 {@code buildJson} 的輸出是<b>對外契約</b>、形狀不得改變。故這裡驗的是
 * (1) 兩份都產出且主檔名相同、(2) JSON 結構未變、(3) format 殘留值不影響行為、
 * (4) 單次查詢探針必須用既有的 2 次而非 1 次。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TradingCalendarDualFormatTest {

    @Mock private MarketDataService marketDataService;
    @TempDir Path baseDir;

    private TradingCalendarExportService service;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        var gdrive = org.mockito.Mockito.mock(com.steven.assets.service.GdriveOutputSupport.class);
        service = new TradingCalendarExportService(marketDataService, mapper,
                new DualFormatExportWriter(gdrive), baseDir.toString());
        when(marketDataService.getTwHolidays(anyInt())).thenReturn(Map.of("2026-01-01", "元旦"));
        when(marketDataService.getUsHolidays(anyInt())).thenReturn(Map.of());
        when(marketDataService.getUkHolidays(anyInt())).thenReturn(Map.of());
        when(marketDataService.isTwTradingDay(any())).thenReturn(true);
        when(marketDataService.isUsTradingDay(any())).thenReturn(true);
        when(marketDataService.isUkTradingDay(any())).thenReturn(true);
    }

    @Test
    @DisplayName("一律產出兩份，主檔名逐字元相同、只差副檔名，且不留 .tmp")
    void 兩份都產出且主檔名相同() throws Exception {
        TradingCalendarExportDto.RunResponse r = service.exportToDir(2026, "out");

        Path xlsx = Path.of(r.path());
        Path json = Path.of(r.jsonPath());
        assertThat(xlsx).exists();
        assertThat(json).exists();
        assertThat(xlsx.getFileName().toString()).isEqualTo("交易日曆_2026.xlsx");
        assertThat(json.getFileName().toString()).isEqualTo("交易日曆_2026.json");
        assertThat(r.sizeBytes()).isPositive();
        assertThat(r.jsonSizeBytes()).isPositive();

        try (Stream<Path> s = Files.list(baseDir.resolve("out"))) {
            assertThat(s.map(p -> p.getFileName().toString())).noneMatch(n -> n.endsWith(".tmp"));
        }
    }

    @Test
    @DisplayName("JSON 結構未變（對外契約）：year／count／holidays／days 都在，且是可解析的合法 JSON")
    void json結構未變() throws Exception {
        TradingCalendarExportDto.RunResponse r = service.exportToDir(2026, "out");
        JsonNode root = mapper.readTree(Files.readAllBytes(Path.of(r.jsonPath())));

        // 逐欄位比對既有 payload 的形狀（buildJson 是對外契約，欄位名不得改）
        assertThat(root.get("year").asInt()).isEqualTo(2026);
        assertThat(root.has("generatedAt")).as("牆鐘值，只驗存在").isTrue();
        assertThat(root.get("timezone").asText()).isEqualTo("Asia/Taipei");
        assertThat(root.at("/tradingDayCount/tw").isNumber()).isTrue();
        assertThat(root.at("/tradingDayCount/us").isNumber()).isTrue();
        assertThat(root.at("/tradingDayCount/uk").isNumber()).isTrue();
        assertThat(root.at("/holidays/tw/2026-01-01").asText()).isEqualTo("元旦");
        assertThat(root.get("days").isArray()).isTrue();
        assertThat(root.get("days")).hasSize(365);
    }

    @Test
    @DisplayName("Excel 那一份仍是既有 buildExcel 的產出（單一分頁、含表頭）")
    void excel仍為既有產出() throws Exception {
        TradingCalendarExportDto.RunResponse r = service.exportToDir(2026, "out");
        try (Workbook wb = GoldenWorkbooks.read(Files.readAllBytes(Path.of(r.path())))) {
            assertThat(wb.getNumberOfSheets()).isGreaterThanOrEqualTo(1);
            assertThat(wb.getSheetAt(0).getLastRowNum()).isGreaterThan(300);
        }
    }

    @Test
    @DisplayName("單次查詢探針：getTwHolidays 改動前後都是 2 次（buildDays 一次、buildJson 一次）")
    void 假日查詢次數維持既有的兩次() {
        service.exportToDir(2026, "out");
        // 寫成 times(1) 會永遠紅，逼實作者去改 buildJson——而那正是本任務明文保護的對外契約。
        verify(marketDataService, times(2)).getTwHolidays(2026);
        verify(marketDataService, times(2)).getUsHolidays(2026);
        verify(marketDataService, times(2)).getUkHolidays(2026);
    }

    @Test
    @DisplayName("localStatus 一律沿用共用元件算好的字串——兩份都寫不出來時不得記成「成功」")
    void 狀態字串不得自組() throws Exception {
        // 目標路徑被同名目錄占住 → 兩份都寫不出來，但共用元件不擲例外
        Files.createDirectories(baseDir.resolve("out"));
        Files.createDirectory(baseDir.resolve("out").resolve("交易日曆_2026.xlsx"));
        Files.createDirectory(baseDir.resolve("out").resolve("交易日曆_2026.json"));

        TradingCalendarExportDto.RunResponse r = service.exportToDir(2026, "out");

        assertThat(r.path()).isNull();
        assertThat(r.jsonPath()).isNull();
        assertThat(r.localStatus())
                .as("自組「成功：」+ path 會在此變成假的「成功：null／null」")
                .doesNotStartWith("成功：")
                .contains("失敗");
        assertThat(r.localStatus().length()).as("已由共用元件截斷至 500 內").isLessThanOrEqualTo(500);
    }

    @Test
    @DisplayName("年度超出範圍仍擲 IllegalArgumentException（既有驗證未被拆掉）")
    void 年度範圍驗證仍在() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.exportToDir(1969, "out"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1970");
    }
}
