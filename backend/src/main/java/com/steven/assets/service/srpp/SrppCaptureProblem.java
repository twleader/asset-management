package com.steven.assets.service.srpp;

import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;

/**
 * SRPP capture／evaluate 的應用錯誤（Task 483 起；Task 484.5 擴充為 RFC 9457 七欄所需的載體）。
 *
 * <p>{@code detail}、{@code retryable} 一律由 {@link SrppCaptureProblemCatalog} 依 code 補齊；
 * status 必須與目錄一致，否則拒絕建構（避免同一 code 出現兩種狀態碼）。{@code errors} 預設空，
 * 只有需要逐項錯誤的 code（由 t481／t482 定義）才帶入；{@code truncated} 為 true 表示 {@code errors}
 * 已截斷（Task 481.5：422 最多 200 項）。
 */
public class SrppCaptureProblem extends RuntimeException {
    public final HttpStatus status; public final String code; public final String detail; public final boolean retryable;
    public final List<Map<String, Object>> errors;
    public final boolean truncated;

    /** Task 483 既有建構子：其餘欄位由目錄補齊。 */
    public SrppCaptureProblem(HttpStatus status, String code) { this(status, code, List.of()); }

    public SrppCaptureProblem(HttpStatus status, String code, List<Map<String, Object>> errors) {
        this(status, code, errors, false);
    }

    public SrppCaptureProblem(HttpStatus status, String code, List<Map<String, Object>> errors, boolean truncated) {
        super(code);
        SrppCaptureProblemCatalog.Problem problem = SrppCaptureProblemCatalog.get(code);
        if (status == null || status.value() != problem.status()) throw new IllegalArgumentException("SRPP capture problem status/code mismatch");
        this.status = status; this.code = code; this.detail = problem.detail(); this.retryable = problem.retryable();
        this.errors = errors == null ? List.of() : List.copyOf(errors);
        this.truncated = truncated;
    }
}
