package com.steven.assets.bff.exchangerate;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Requirement 145 / Task 423：Map passthrough 不得裁切 times[]。 */
class ExchangeRateBffScheduleTest {

    private static final String SCHEDULE_JSON = """
            {"enabled":true,"outputSubpath":"input","rangeMonths":60,
             "times":[{"id":1,"runHour":9,"runMinute":20,"enabled":true,"lastRunAt":"2026-09-11 09:20:00","lastRunStatus":"成功"},
                       {"id":2,"runHour":18,"runMinute":0,"enabled":false,"lastRunAt":null,"lastRunStatus":null}],
             "lastRunAt":"2026-09-11 09:20:00","lastRunStatus":"成功","baseDir":"/home/steven"}
            """;

    private static WebClient stubClient(String json) {
        return WebClient.builder().exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json").body(json).build())).build();
    }

    @SuppressWarnings("unchecked")
    @Test
    void getAndPutSchedule_完整保留多時間陣列且不補回舊topLevel時間() {
        var controller = new ExchangeRateBffController(stubClient(SCHEDULE_JSON));
        Map<String, Object> get = controller.getExportSchedule().block().getBody();
        Map<String, Object> put = controller.updateExportSchedule(Map.of("enabled", true, "times",
                java.util.List.of(Map.of("runHour", 9, "runMinute", 20, "enabled", true)))).block().getBody();

        for (Map<String, Object> body : java.util.List.of(get, put)) {
            var times = (java.util.List<Map<String, Object>>) body.get("times");
            assertThat(times).hasSize(2);
            assertThat(times.get(0)).containsEntry("runHour", 9).containsEntry("runMinute", 20);
            assertThat(times.get(1)).containsEntry("enabled", false);
            assertThat(body).doesNotContainKeys("runHour", "runMinute");
        }
    }

    @Test
    void runNow_保留既有雙格式回應() {
        String response = """
                {"path":"/tmp/a.xlsx","sizeBytes":1,"gdrivePath":null,"gdriveStatus":null,
                 "jsonPath":"/tmp/a.json","jsonSizeBytes":2,"jsonGdrivePath":null}
                """;
        Map<String, Object> body = new ExchangeRateBffController(stubClient(response)).runExportNow().block().getBody();
        assertThat(body).containsKeys("path", "sizeBytes", "gdrivePath", "gdriveStatus", "jsonPath", "jsonSizeBytes", "jsonGdrivePath");
    }
}
