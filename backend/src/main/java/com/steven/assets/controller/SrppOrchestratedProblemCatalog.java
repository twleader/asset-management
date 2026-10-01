package com.steven.assets.controller;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/** Fixed RFC 9457 problem details shared by the three internal SRPP read endpoints. */
final class SrppOrchestratedProblemCatalog {
    private SrppOrchestratedProblemCatalog() {}
    private record Detail(int status, String title, String text, boolean retryable) {}
    private static final Map<String, Detail> DETAILS = Map.of(
            "INVALID_REQUEST", new Detail(400, "Invalid SRPP request", "查詢參數不合法，請修正後再試。", false),
            "CONTEXT_NOT_FOUND", new Detail(404, "SRPP context not found", "查無此擁有者可讀取的計算脈絡。", false),
            "CONTEXT_STALE", new Detail(409, "SRPP context evidence unavailable", "固定來源證據不存在或無法驗證，請重新取得計算脈絡。", false),
            "POLICY_UNSUPPORTED", new Detail(409, "SRPP policy unsupported", "指定規則包未通過伺服器端驗證。", false),
            "OWNER_UNAVAILABLE", new Detail(503, "SRPP owner unavailable", "無法確認資料擁有者。", false),
            "CONTEXT_NOT_READY", new Detail(503, "SRPP context not ready", "本時段的計算脈絡尚未就緒。", true));

    static int status(String code) { return detail(code).status(); }
    static String body(String code, String path) {
        Detail detail = detail(code);
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("type", "about:blank");
        node.put("title", detail.title());
        node.put("status", detail.status());
        node.put("detail", detail.text());
        node.put("instance", path);
        node.put("code", code);
        node.put("retryable", detail.retryable());
        return node.toString();
    }
    private static Detail detail(String code) {
        Detail detail = DETAILS.get(code);
        if (detail == null) throw new IllegalArgumentException("Unknown SRPP problem code");
        return detail;
    }
}
