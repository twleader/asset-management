package com.steven.assets.bff.commodityprice;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link CommodityPriceBffController} 排程端點的 passthrough 驗證（Requirement 72 / Task 330）。
 *
 * <p>本 controller 刻意以 {@code Map<String,Object>} passthrough（不建鏡像 DTO），故新增的
 * {@code times[]} 欄位理論上不會被 BFF 端寫死的欄位清單裁切——因為根本沒有欄位清單。這支測試
 * 把這個「結構性保證」釘成可回歸的斷言：用 {@link WebClient.Builder#exchangeFunction} 直接餵一段
 * 帶 {@code times[]} 的 JSON（不需要額外的 MockWebServer／WireMock 依賴，bff 模組原本也沒有這類
 * 測試基礎設施），驗證 GET／PUT 回應原封不動地把 {@code times[]} 轉出去。
 *
 * <p>main 上 {@code CommodityPriceBffController} 原本沒有專屬測試檔（其餘匯出頁 BFF controller
 * 亦皆無 passthrough 測試），本檔為新建。
 */
class CommodityPriceBffScheduleTest {

    private static final String SCHEDULE_JSON = """
            {"enabled":true,"outputSubpath":"input","rangeMonths":60,
             "times":[{"id":1,"runHour":9,"runMinute":20,"enabled":true,"lastRunAt":"2026-08-15 09:20:06","lastRunStatus":"成功：/x"},
                       {"id":2,"runHour":18,"runMinute":0,"enabled":false,"lastRunAt":null,"lastRunStatus":null}],
             "lastRunAt":"2026-08-15 09:20:06","lastRunStatus":"成功：/x","baseDir":"/home/steven",
             "gdriveEnabled":true,"gdriveSubpath":"投資理財/油價金價","gdriveRemote":"GDriveOutput",
             "gdriveLastRunAt":null,"gdriveLastStatus":null,"gdriveSelfCheckWarning":null}
            """;

    /** 建一個把任何請求都回固定 JSON 的 WebClient，不需要啟動真正的 HTTP server。 */
    private static WebClient stubClient(String json) {
        return WebClient.builder()
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", "application/json")
                        .body(json)
                        .build()))
                .build();
    }

    @SuppressWarnings("unchecked")
    @Test
    void getExportSchedule_回應保留times陣列與per_time欄位() {
        var controller = new CommodityPriceBffController(stubClient(SCHEDULE_JSON));

        Map<String, Object> body = controller.getExportSchedule().block().getBody();

        assertThat(body).containsEntry("enabled", true).containsEntry("rangeMonths", 60);
        var times = (java.util.List<Map<String, Object>>) body.get("times");
        assertThat(times).hasSize(2);
        assertThat(times.get(0)).containsEntry("runHour", 9).containsEntry("runMinute", 20).containsEntry("enabled", true);
        assertThat(times.get(1)).containsEntry("runHour", 18).containsEntry("enabled", false);
        // 舊契約的 top-level runHour/runMinute 已移除，passthrough 不應憑空補回來
        assertThat(body).doesNotContainKey("runHour").doesNotContainKey("runMinute");
    }

    @SuppressWarnings("unchecked")
    @Test
    void updateExportSchedule_回應保留times陣列() {
        var controller = new CommodityPriceBffController(stubClient(SCHEDULE_JSON));

        Map<String, Object> reqBody = Map.of(
                "enabled", true, "outputSubpath", "input", "rangeMonths", 60,
                "times", java.util.List.of(Map.of("runHour", 9, "runMinute", 20, "enabled", true)));

        Map<String, Object> body = controller.updateExportSchedule(reqBody).block().getBody();

        var times = (java.util.List<Map<String, Object>>) body.get("times");
        assertThat(times).hasSize(2); // 來自 stub 的回應，證明 BFF 沒有攔截、改寫或裁切回應內容
        assertThat(times.get(0)).containsEntry("runHour", 9).containsEntry("runMinute", 20);
    }

    private static final String RUN_NOW_JSON = """
            {"path":"/home/steven/input/油價金價_1_20260815.xlsx","sizeBytes":1234,
             "gdrivePath":null,"gdriveStatus":null,
             "jsonPath":"/home/steven/input/油價金價_1_20260815.json","jsonSizeBytes":567,"jsonGdrivePath":null}
            """;

    @Test
    void runExportNow_回應七欄雙格式契約完整轉出() {
        var controller = new CommodityPriceBffController(stubClient(RUN_NOW_JSON));

        Map<String, Object> body = controller.runExportNow().block().getBody();

        assertThat(body).containsEntry("path", "/home/steven/input/油價金價_1_20260815.xlsx")
                .containsEntry("jsonPath", "/home/steven/input/油價金價_1_20260815.json")
                .containsKey("sizeBytes").containsKey("jsonSizeBytes")
                .containsKey("gdrivePath").containsKey("gdriveStatus").containsKey("jsonGdrivePath");
    }
}
