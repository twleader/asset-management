package com.steven.assets.controller;

import com.steven.assets.service.ExcelExportService;
import com.steven.assets.service.MacroHistoryService;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MacroHistoryControllerTpexCompatibilityTest {

    private final MacroHistoryService macro = mock(MacroHistoryService.class);
    private final MacroHistoryController controller = new MacroHistoryController(macro, mock(ExcelExportService.class));

    @Test
    void 相容GET不套頁面白名單故SP500TR仍可查詢() {
        when(macro.getUsDaily("SP500TR", null, null)).thenReturn(List.of());

        assertThat(controller.getUsDaily("SP500TR", null, null)).isEmpty();

        verify(macro).getUsDaily("SP500TR", null, null);
    }

    @Test
    void TPEX與SP500TR都可經相容refresh但只有TPEX進頁面匯出白名單() {
        when(macro.refreshUsIndexDaily("TPEX")).thenReturn(Map.of("code", "TPEX", "upserted", 1));
        when(macro.refreshUsIndexDaily("SP500TR")).thenReturn(Map.of("code", "SP500TR", "upserted", 1));

        assertThat(controller.refreshUsDaily("TPEX")).containsEntry("code", "TPEX");
        assertThat(controller.refreshUsDaily("SP500TR")).containsEntry("code", "SP500TR");
        assertThat(MacroHistoryService.DAILY_INDEX_CODES).contains("TWSE", "TPEX").doesNotContain("SP500TR");
        assertThat(MacroHistoryService.PAGE_CODED_INDEX_CODES)
                .containsExactly("TPEX", "DJI", "SPX", "IXIC", "SOX", "FTSE", "DAX", "KOSPI", "N225");
        assertThat(ExcelExportService.indexLabel("TPEX")).isEqualTo("台股櫃買市場");
    }

    @Test
    void TPEX可走既有日線匯出白名單() throws Exception {
        LocalDate day = LocalDate.of(2026, 8, 20);
        ExcelExportService export = mock(ExcelExportService.class);
        MacroHistoryController exportController = new MacroHistoryController(macro, export);
        when(export.exportIndexDaily("TPEX", day, day)).thenReturn(new byte[] {1, 2, 3});

        assertThat(exportController.exportIndexDaily("TPEX", day, day).getBody().getByteArray())
                .containsExactly(1, 2, 3);

        verify(export).exportIndexDaily("TPEX", day, day);
    }
}
