package com.steven.assets.service.srpp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Requirement 184／Task 484.4／484.5：capture 專用 problem 目錄與 {@link SrppCaptureProblem} 載體。 */
class SrppCaptureProblemCatalogTest {

    @ParameterizedTest(name = "{0} → {1}，retryable={2}")
    @CsvSource({
            "INVALID_REQUEST,400,false", "UNSUPPORTED_MEDIA_TYPE,415,false", "NON_TRADING_DAY,409,false",
            "POLICY_UNSUPPORTED,409,false", "SWAGGER_MISMATCH,409,false", "OWNER_UNAVAILABLE,503,false",
            "CALENDAR_UNAVAILABLE,503,true", "CONTEXT_NOT_READY,503,true", "UPSTREAM_INVALID,502,false",
            "INTERNAL_ERROR,500,false",
            // Task 481：事件證據擷取新增的三個 code。
            "EVIDENCE_REJECTED,422,false", "BUNDLE_METADATA_MISMATCH,409,false", "BUNDLE_CONTENT_CONFLICT,409,false"})
    void sharedCodesHaveFixedStatusTitleDetailAndRetryable(String code, int status, boolean retryable) {
        SrppCaptureProblemCatalog.Problem problem = SrppCaptureProblemCatalog.get(code);

        assertThat(problem.status()).isEqualTo(status);
        assertThat(problem.retryable()).isEqualTo(retryable);
        assertThat(problem.title()).isNotBlank().matches("[ -~]+");
        assertThat(problem.detail()).isNotBlank();
    }

    @Test
    void unknownCodeIsRejected() {
        assertThatThrownBy(() -> SrppCaptureProblemCatalog.get("MADE_UP")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SrppCaptureProblemCatalog.get(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void twoArgumentConstructorFillsDetailRetryableAndEmptyErrorsFromCatalog() {
        SrppCaptureProblem problem = new SrppCaptureProblem(HttpStatus.SERVICE_UNAVAILABLE, "CONTEXT_NOT_READY");

        assertThat(problem.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(problem.code).isEqualTo("CONTEXT_NOT_READY");
        assertThat(problem.detail).isEqualTo(SrppCaptureProblemCatalog.get("CONTEXT_NOT_READY").detail());
        assertThat(problem.retryable).isTrue();
        assertThat(problem.errors).isEmpty();
    }

    @Test
    void errorsAreCopiedAndStatusMustMatchCatalog() {
        SrppCaptureProblem problem = new SrppCaptureProblem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST",
                List.of(Map.of("code", "X")));
        assertThat(problem.errors).containsExactly(Map.of("code", "X"));
        assertThatThrownBy(() -> problem.errors.add(Map.of())).isInstanceOf(UnsupportedOperationException.class);

        assertThatThrownBy(() -> new SrppCaptureProblem(HttpStatus.CONFLICT, "INVALID_REQUEST"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(problem.truncated).isFalse();
    }

    @Test
    void evidenceRejectedCarriesTruncatedFlag() {
        SrppCaptureProblem problem = new SrppCaptureProblem(HttpStatus.UNPROCESSABLE_ENTITY, "EVIDENCE_REJECTED",
                List.of(Map.of("code", "MISSING_CATEGORY")), true);
        assertThat(problem.truncated).isTrue();
        assertThat(problem.retryable).isFalse();
        assertThatThrownBy(() -> new SrppCaptureProblem(HttpStatus.BAD_REQUEST, "EVIDENCE_REJECTED"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
