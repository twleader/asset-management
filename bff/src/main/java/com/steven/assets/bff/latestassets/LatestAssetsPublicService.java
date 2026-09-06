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

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * 在 BFF 只做 owner bootstrap 與顯式 tenant headers；資料聚合仍在 business。
 *
 * <p>Owner 來源有二（Requirement 140）：{@code email} 省略時沿用既有 configured-admin bootstrap；
 * 帶入合法格式且對應 active 帳號的 {@code email} 時，owner 改由該帳號決定，不必是 configured-admin。
 */
@Service
@RequiredArgsConstructor
public class LatestAssetsPublicService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", Pattern.CASE_INSENSITIVE);
    private static final int EMAIL_MAX_LENGTH = 254;
    private static final Duration OWNER_LOOKUP_TIMEOUT = Duration.ofSeconds(5);

    private final BusinessUserClient users;
    private final WebClient businessServicesClient;
    private final ObjectMapper objectMapper;

    public Mono<ResponseEntity<byte[]>> getLatest(String email) {
        return resolveOwner(email)
                .flatMap(admin ->
                        // 這支對外匿名 API 的 owner 只會是 configured admin 或已驗證 active 的
                        // by-email 帳號。shared WebClient 的 tenant filter 會讀取 Reactor context；
                        // 若保留登入者／代看者身分，會覆寫下面明確指定的 header，讓公開資料錯指向
                        // 呼叫者。只對這個 downstream publisher 清掉 context，bootstrap lookup
                        // 仍維持既有行為。
                        businessServicesClient.get()
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
                                .contextWrite(ctx -> ctx.delete(AuthConstants.CTX_IDENTITY)));
    }

    /**
     * email trim 後非空白時，owner 改由該帳號決定（僅需 {@code id}／{@code role}／{@code status} 非 null
     * 且 active，不必是 configured-admin——與既有 bootstrap 分支的唯一差異）；否則沿用既有
     * configured-admin bootstrap（逐位元組不變，含既有例外訊息與 503 契約）。
     */
    private Mono<BffUser> resolveOwner(String email) {
        String trimmed = normalize(email);
        if (trimmed != null) {
            // 僅把 by-email lookup 的失敗封閉為 owner unavailable。local email validation 已在
            // 此前完成；下游 /api/assets/latest 的錯誤則必須保留既有 relay 契約，不能被誤吞。
            return Mono.defer(() -> users.byEmail(trimmed))
                    .timeout(OWNER_LOOKUP_TIMEOUT)
                    .onErrorMap(ignored -> new LatestAssetsUnavailableException("指定帳號不可用"))
                    .switchIfEmpty(Mono.error(new LatestAssetsUnavailableException("指定帳號不可用")))
                    .flatMap(account -> {
                        if (account == null || account.id() == null || account.role() == null
                                || account.status() == null || !account.isActive()) {
                            // 查無帳號與帳號非 ACTIVE 必須回完全相同的訊息，避免此參數成為帳號列舉工具。
                            return Mono.error(new LatestAssetsUnavailableException("指定帳號不可用"));
                        }
                        return Mono.just(account);
                    });
        }
        return users.configuredAdmin()
                .switchIfEmpty(Mono.error(new LatestAssetsUnavailableException("主要管理者尚未建立")))
                .flatMap(admin -> {
                    if (admin == null || admin.id() == null || !admin.configuredAdmin() || !admin.isActive()) {
                        return Mono.error(new LatestAssetsUnavailableException("主要管理者不可用"));
                    }
                    return Mono.just(admin);
                });
    }

    /** {@code null}／空白回傳 {@code null}（沿用 configured-admin）；格式不合法在呼叫 business 前就地拒絕。 */
    private String normalize(String email) {
        if (email == null) {
            return null;
        }
        String trimmed = email.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > EMAIL_MAX_LENGTH || !EMAIL_PATTERN.matcher(trimmed).matches()) {
            throw new LatestAssetsRequestException();
        }
        return trimmed;
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
