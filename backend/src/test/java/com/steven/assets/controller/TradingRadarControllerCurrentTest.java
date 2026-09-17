package com.steven.assets.controller;

import com.steven.assets.dto.TradingRadarDto;
import com.steven.assets.repository.AssetSnapshotRepository;
import com.steven.assets.service.TradingRadarExportScheduleService;
import com.steven.assets.service.TradingRadarNotificationSettingService;
import com.steven.assets.service.TradingRadarRefreshService;
import com.steven.assets.service.TradingRadarService;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Requirement 86：business current controller 只能委派 service。 */
class TradingRadarControllerCurrentTest {

    private static TradingRadarController controller(TradingRadarService service) {
        return new TradingRadarController(service,
                mock(TradingRadarNotificationSettingService.class),
                mock(TradingRadarExportScheduleService.class),
                mock(TradingRadarRefreshService.class));
    }

    @Test
    void current只委派service且controller不持有repository或webClient() {
        TradingRadarService service = mock(TradingRadarService.class);
        TradingRadarDto.Response expected = new TradingRadarDto.Response(
                "TW_RULES_V20", "EVIDENCE_GATE_V1", "2026-08-21T00:00:00Z",
                null, null, List.of(), 0, List.of());
        when(service.getCurrent()).thenReturn(expected);
        TradingRadarController controller = controller(service);

        assertThat(controller.getCurrent()).isSameAs(expected);
        verify(service).getCurrent();
        verifyNoMoreInteractions(service);
        assertThat(List.of(TradingRadarController.class.getDeclaredFields()))
                .noneMatch(field -> AssetSnapshotRepository.class.isAssignableFrom(field.getType())
                        || WebClient.class.isAssignableFrom(field.getType()));
    }

    @Test
    void list與單檔detail只委派各自的service入口() {
        TradingRadarService service = mock(TradingRadarService.class);
        TradingRadarDto.ListResponse list = new TradingRadarDto.ListResponse(
                "TW_RULES_V20", "EVIDENCE_GATE_V1", "2026-09-12T00:00:00Z",
                null, null, List.of(), 0, List.of());
        TradingRadarDto.StockDetailResponse detail = new TradingRadarDto.StockDetailResponse(
                "TW_RULES_V20", "EVIDENCE_GATE_V1", "2026-09-12T00:00:00Z", null);
        when(service.getList()).thenReturn(list);
        when(service.getStockDetail("2330", "台股")).thenReturn(detail);
        TradingRadarController controller = controller(service);

        assertThat(controller.list()).isSameAs(list);
        assertThat(controller.stock("2330", "台股")).isSameAs(detail);

        verify(service).getList();
        verify(service).getStockDetail("2330", "台股");
        verifyNoMoreInteractions(service);
    }
}
