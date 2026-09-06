package com.steven.assets.apierrorlog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApiErrorLogInternalControllerTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void accepts_only_one_correct_private_token_and_exact_open_api_schema() throws Exception {
        ApiErrorLogRecorder recorder = mock(ApiErrorLogRecorder.class);
        ApiErrorLogInternalController controller = new ApiErrorLogInternalController(recorder, "only-bff-business");
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Internal-Service-Token", "only-bff-business");

        controller.ingest(headers, json.readTree("""
                {"source":"OPEN_API","operationKey":"OPEN_QUOTES_LIST","apiName":"即時報價清單",
                 "messageHeader":"safe","stackTrace":"trace","occurredAt":"2026-09-06T00:00:00Z"}
                """));

        verify(recorder).record("OPEN_API", "OPEN_QUOTES_LIST", "即時報價清單", "safe", "trace", Instant.parse("2026-09-06T00:00:00Z"));

        HttpHeaders duplicate = new HttpHeaders();
        duplicate.add("X-Internal-Service-Token", "only-bff-business");
        duplicate.add("X-Internal-Service-Token", "only-bff-business");
        assertThatThrownBy(() -> controller.ingest(duplicate, json.readTree("{}"))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.ingest(new HttpHeaders(), json.readTree("{}"))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.ingest(headers, json.readTree("""
                {"source":"OPEN_API","operationKey":"OPEN_QUOTES_LIST","apiName":"forged",
                 "messageHeader":"safe","stackTrace":"trace","occurredAt":"2026-09-06T00:00:00Z"}
                """))).isInstanceOf(ResponseStatusException.class);
        verifyNoMoreInteractions(recorder);
    }
}
