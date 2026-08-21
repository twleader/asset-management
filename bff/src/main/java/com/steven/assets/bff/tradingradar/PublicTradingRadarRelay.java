package com.steven.assets.bff.tradingradar;

/**
 * Business 成功回應的 HTTP-neutral relay value。
 *
 * <p>只攜帶 controller 必須保留的 status code、Content-Type 字串與原始 bytes；不讓 service
 * 組裝 {@code ResponseEntity}，也不提供 Location 等其他 upstream header 的 relay 通道。
 * byte array 在建構與讀取時都複製，避免 record 的內容被外部修改。</p>
 */
public record PublicTradingRadarRelay(int statusCode, String contentType, byte[] body) {

    public PublicTradingRadarRelay {
        if (statusCode < 200 || statusCode >= 300) {
            throw new IllegalArgumentException("relay status must be successful");
        }
        body = body == null ? null : body.clone();
    }

    @Override
    public byte[] body() {
        return body == null ? null : body.clone();
    }
}
