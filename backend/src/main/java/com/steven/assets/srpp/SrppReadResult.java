package com.steven.assets.srpp;

/**
 * Requirement 163／Task 453：讀取端點的 HTTP 狀態與已組好的 JSON 字串。
 * {@code code} 非 null 表示 body 是 RFC 9457 problem。
 */
public record SrppReadResult(int status, String code, String body) {

    public static SrppReadResult ok(String body) {
        return new SrppReadResult(200, null, body);
    }

    public static SrppReadResult problem(String code) {
        return new SrppReadResult(SrppProblemCatalog.get(code).status(), code, SrppProblemCatalog.body(code));
    }

    public boolean isProblem() {
        return code != null;
    }
}
