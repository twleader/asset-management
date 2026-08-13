package com.steven.assets.bff.latestassets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.bff.security.AuthConstants;
import com.steven.assets.bff.security.BffUser;
import com.steven.assets.bff.security.BusinessUserClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** 在 BFF 只做 configured-admin bootstrap 與顯式 tenant headers；資料聚合仍在 business。 */
@Service
@RequiredArgsConstructor
public class LatestAssetsPublicService {
    private final BusinessUserClient users;
    private final WebClient businessServicesClient;
    private final ObjectMapper objectMapper;

    public Mono<ResponseEntity<byte[]>> getLatest() {
        return users.configuredAdmin()
                .switchIfEmpty(Mono.error(new LatestAssetsUnavailableException("主要管理者尚未建立")))
                .flatMap(admin -> {
                    if (admin == null || admin.id() == null || !admin.configuredAdmin() || !admin.isActive()) {
                        return Mono.error(new LatestAssetsUnavailableException("主要管理者不可用"));
                    }
                    // 這支對外匿名 API 的 owner 僅能是 business 唯一解析出的 configured admin。
                    // shared WebClient 的 tenant filter 會讀取 Reactor context；若保留登入者／代看者
                    // 身分，會覆寫下面明確指定的 header，讓公開資料錯指向呼叫者。只對這個
                    // downstream publisher 清掉 context，bootstrap lookup 仍維持既有行為。
                    return businessServicesClient.get()
                            .uri("/api/assets/latest")
                            .header(AuthConstants.HDR_USER_ID, String.valueOf(admin.id()))
                            .header(AuthConstants.HDR_USER_ROLE, admin.role())
                            .header(AuthConstants.HDR_USER_STATUS, admin.status())
                            .accept(MediaType.APPLICATION_JSON)
                            // 金融數值不可先 decode 成 Map/Double 再重編碼；原始 JSON bytes、
                            // status 與 content type 直接 relay，避免高精度金額在 BFF 被改寫；
                            // 但 2xx 仍先唯讀驗證 JSON 與兩個 snapshot identity，不能讓 malformed
                            // upstream payload 冒充成功。
                            .exchangeToMono(response -> response.toEntity(byte[].class)
                                    .map(entity -> validateSuccessPayload(entity)))
                            .contextWrite(ctx -> ctx.delete(AuthConstants.CTX_IDENTITY));
                });
    }

    private ResponseEntity<byte[]> validateSuccessPayload(ResponseEntity<byte[]> entity) {
        if (!entity.getStatusCode().is2xxSuccessful()) return entity;
        byte[] body = entity.getBody();
        if (body == null || body.length == 0) {
            throw new LatestAssetsPayloadException("最新資產上游回傳空 payload");
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode snapshotId = root.path("snapshot").path("id");
            JsonNode liveSnapshotId = root.path("liveAssets").path("snapshotId");
            if (!snapshotId.isIntegralNumber() || !liveSnapshotId.isIntegralNumber()
                    || !snapshotId.bigIntegerValue().equals(liveSnapshotId.bigIntegerValue())) {
                throw new LatestAssetsPayloadException("最新資產上游 snapshot identity 不一致");
            }
            return entity;
        } catch (LatestAssetsPayloadException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new LatestAssetsPayloadException("最新資產上游 JSON 無法解析", ex);
        }
    }
}
