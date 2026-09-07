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
                 "messageHeader":"safe","stackTrace":"trace","occurredAt":"2026-09-06T00:00:00Z",
                 "httpStatus":400,"dedupeKey":null}
                """));

        verify(recorder).record("OPEN_API", "OPEN_QUOTES_LIST", "即時報價清單", "safe", "trace", Instant.parse("2026-09-06T00:00:00Z"), 400, null);

        HttpHeaders duplicate = new HttpHeaders();
        duplicate.add("X-Internal-Service-Token", "only-bff-business");
        duplicate.add("X-Internal-Service-Token", "only-bff-business");
        assertThatThrownBy(() -> controller.ingest(duplicate, json.readTree("{}"))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.ingest(new HttpHeaders(), json.readTree("{}"))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.ingest(headers, json.readTree("""
                {"source":"OPEN_API","operationKey":"OPEN_QUOTES_LIST","apiName":"forged",
                 "messageHeader":"safe","stackTrace":"trace","occurredAt":"2026-09-06T00:00:00Z",
                 "httpStatus":400,"dedupeKey":null}
                """))).isInstanceOf(ResponseStatusException.class);
        verifyNoMoreInteractions(recorder);
    }

    private HttpHeaders validHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Internal-Service-Token", "only-bff-business");
        return headers;
    }

    @Test void payload_missing_http_status_or_dedupe_key_is_rejected_as_the_eight_keys_are_not_all_present() {
        ApiErrorLogRecorder recorder = mock(ApiErrorLogRecorder.class);
        ApiErrorLogInternalController controller = new ApiErrorLogInternalController(recorder, "only-bff-business");

        assertThatThrownBy(() -> controller.ingest(validHeaders(), json.readTree("""
                {"source":"OPEN_API","operationKey":"OPEN_QUOTES_LIST","apiName":"即時報價清單",
                 "messageHeader":"safe","stackTrace":"trace","occurredAt":"2026-09-06T00:00:00Z",
                 "dedupeKey":null}
                """))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.ingest(validHeaders(), json.readTree("""
                {"source":"OPEN_API","operationKey":"OPEN_QUOTES_LIST","apiName":"即時報價清單",
                 "messageHeader":"safe","stackTrace":"trace","occurredAt":"2026-09-06T00:00:00Z",
                 "httpStatus":400}
                """))).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(recorder);
    }

    @Test void http_status_must_be_a_json_integer_within_100_to_599() throws Exception {
        ApiErrorLogRecorder recorder = mock(ApiErrorLogRecorder.class);
        ApiErrorLogInternalController controller = new ApiErrorLogInternalController(recorder, "only-bff-business");

        assertThatThrownBy(() -> controller.ingest(validHeaders(), payload("\"400\"", "null"))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.ingest(validHeaders(), payload("400.5", "null"))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.ingest(validHeaders(), payload("99", "null"))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.ingest(validHeaders(), payload("600", "null"))).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(recorder);

        controller.ingest(validHeaders(), payload("100", "null"));
        controller.ingest(validHeaders(), payload("599", "null"));
        verify(recorder).record("OPEN_API", "OPEN_MARKET_INDEX", "大盤指數", "safe", "trace", Instant.parse("2026-09-06T00:00:00Z"), 100, null);
        verify(recorder).record("OPEN_API", "OPEN_MARKET_INDEX", "大盤指數", "safe", "trace", Instant.parse("2026-09-06T00:00:00Z"), 599, null);
    }

    @Test void dedupe_key_accepts_both_json_null_and_string_and_preserves_it_as_is() throws Exception {
        ApiErrorLogRecorder recorder = mock(ApiErrorLogRecorder.class);
        ApiErrorLogInternalController controller = new ApiErrorLogInternalController(recorder, "only-bff-business");

        controller.ingest(validHeaders(), payload("502", "null"));
        controller.ingest(validHeaders(), payload("502", "\"abc123deadbeef\""));

        verify(recorder).record("OPEN_API", "OPEN_MARKET_INDEX", "大盤指數", "safe", "trace", Instant.parse("2026-09-06T00:00:00Z"), 502, null);
        verify(recorder).record("OPEN_API", "OPEN_MARKET_INDEX", "大盤指數", "safe", "trace", Instant.parse("2026-09-06T00:00:00Z"), 502, "abc123deadbeef");
    }

    @Test void dedupe_key_must_be_null_or_textual_not_some_other_json_type() {
        ApiErrorLogRecorder recorder = mock(ApiErrorLogRecorder.class);
        ApiErrorLogInternalController controller = new ApiErrorLogInternalController(recorder, "only-bff-business");

        assertThatThrownBy(() -> controller.ingest(validHeaders(), payload("502", "12345"))).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> controller.ingest(validHeaders(), payload("502", "true"))).isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(recorder);
    }

    private com.fasterxml.jackson.databind.JsonNode payload(String httpStatusLiteral, String dedupeKeyLiteral) throws Exception {
        return json.readTree("""
                {"source":"OPEN_API","operationKey":"OPEN_MARKET_INDEX","apiName":"大盤指數",
                 "messageHeader":"safe","stackTrace":"trace","occurredAt":"2026-09-06T00:00:00Z",
                 "httpStatus":%s,"dedupeKey":%s}
                """.formatted(httpStatusLiteral, dedupeKeyLiteral));
    }
}
