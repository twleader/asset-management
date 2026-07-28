package com.steven.assets.repository.projection;

/**
 * 警示寄送目標（Requirement 23 / Task 248）：某條警示「選定 ∩ active=true」的收件人身分投影。
 *
 * <p>本專案第一個 JPQL constructor expression 投影型別（既有多欄投影一律回 {@code List<Object[]>}），
 * 故另立 {@code repository.projection} 子套件——**不放 {@code dto/}**：那裡是 controller 對外回傳的
 * API 型別與 service 結果物件，把持久層投影混進去會讓後人誤以為它是 API 契約而不敢改。
 *
 * <p>為什麼需要 id 而不只是 email：dispatcher 要用 {@code id} 組 ics 的 UID、用 {@code addToCalendar}
 * 決定是否夾帶邀請，而 {@code notification_recipient} 的唯一鍵是複合 {@code (owner_user_id, email)}
 * ——不同使用者可各自使用同一 email，故**不能**用 email 反查收件人（背景排程無 request context、
 * Hibernate {@code ownerFilter} 不啟用，反查會撈到別的租戶或直接丟 {@code IncorrectResultSizeDataAccessException}）。
 */
public record AlertRecipientTarget(Long id, String email, Boolean addToCalendar) {
}
