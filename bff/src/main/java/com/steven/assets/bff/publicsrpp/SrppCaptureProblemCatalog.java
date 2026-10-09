package com.steven.assets.bff.publicsrpp;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;

/**
 * Requirement 184／Task 484.4：BFF 對 SRPP capture／evaluate 兩個 9090 POST <b>自行產生</b>的 RFC 9457 problem
 * 固定文案；business 回應的 problem 本體由 relay 原樣轉送，不經本表。
 *
 * <p>只含 BFF 自己會產生的 code。文案與 business {@code SrppCaptureProblemCatalog} 同一 code 逐字相同，
 * 不含帳號、SQL、URI 或例外訊息；{@code instance} 由呼叫端填入該請求的公開路徑。
 */
enum SrppCaptureProblemCatalog {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "Invalid SRPP capture request",
            "請求本文不合法，請確認欄位、格式與內容後再試。", false),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported SRPP capture media type",
            "請求的 Content-Type 必須是 application/json。", false),
    OWNER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "SRPP owner unavailable",
            "無法確認資料擁有者，指定帳號不可用。", false),
    UPSTREAM_INVALID(HttpStatus.BAD_GATEWAY, "SRPP upstream response invalid",
            "上游回應格式不合法。", false),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "SRPP internal error",
            "伺服器發生未預期錯誤。", false);

    private final HttpStatus status;
    private final String title;
    private final String detail;
    private final boolean retryable;

    SrppCaptureProblemCatalog(HttpStatus status, String title, String detail, boolean retryable) {
        this.status = status; this.title = title; this.detail = detail; this.retryable = retryable;
    }
    HttpStatus status() { return status; }
    String title() { return title; }
    String detail() { return detail; }
    boolean retryable() { return retryable; }

    /** 七欄 problem 本體（欄位順序固定）；instance 為該請求的公開路徑。 */
    byte[] body(String instance) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("type", "about:blank");
        node.put("title", title);
        node.put("status", status.value());
        node.put("detail", detail);
        node.put("instance", instance);
        node.put("code", name());
        node.put("retryable", retryable);
        return node.toString().getBytes(StandardCharsets.UTF_8);
    }
}
