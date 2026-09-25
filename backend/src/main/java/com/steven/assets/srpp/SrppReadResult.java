package com.steven.assets.srpp;

/**
 * Requirement 163／Task 453：讀取端點的領域結果。
 *
 * <p>service 只回傳 {@link Ok}（已組好的 SUMMARY／EVIDENCE JSON 字串）或 {@link Problem}（problem code），
 * 不決定 HTTP status、不組 problem body；code→status 對照與 problem JSON 由 controller 經
 * {@link SrppProblemCatalog} 產生。
 */
public sealed interface SrppReadResult {

    record Ok(String body) implements SrppReadResult {}

    record Problem(String code) implements SrppReadResult {}

    static SrppReadResult ok(String body) {
        return new Ok(body);
    }

    static SrppReadResult problem(String code) {
        return new Problem(code);
    }
}
