package com.steven.assets.bff.portfolioadvice;

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
 * 公開讀取「最新資產配置建議」（Requirement 79）：Docker host／Tailscale 免登入可讀的第八條
 * Nginx 9090 路由，轉呼 business 既有的 {@code GET /api/portfolio-advice/latest}。
 *
 * <p>與第七條（今日股市分析，全域參考資料）不同，本條的資料是 <b>owner-scoped</b>，故在 BFF 只做
 * configured-admin bootstrap 與顯式 tenant headers；資料聚合仍在 business。
 *
 * <p><b>絕不觸發 {@code POST /api/portfolio-advice/generate}</b>——那是有 LLM 成本的寫入操作。本服務
 * 只讀既有最新一筆；business 在尚無任何一筆時回 {@code PortfolioAdviceDto.none()}（{@code status="NONE"}
 * 的 HTTP 200 JSON），不代為產生。
 */
@Service
@RequiredArgsConstructor
public class PublicPortfolioAdviceService {

    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "^[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}$", Pattern.CASE_INSENSITIVE);
    private static final int EMAIL_MAX_LENGTH = 254;
    private static final Duration OWNER_LOOKUP_TIMEOUT = Duration.ofSeconds(5);

    private final BusinessUserClient users;
    private final WebClient businessServicesClient;

    public Mono<ResponseEntity<byte[]>> latest(String email) {
        return resolveOwner(email)
                .flatMap(admin ->
                        // 這支對外匿名 API 的 owner 只會是 configured admin 或已驗證 active 的
                        // by-email 帳號。shared WebClient 的 tenant filter 會讀取 Reactor context；
                        // 若保留登入者／代看者身分，會覆寫下面明確指定的 header，讓公開資料錯指向
                        // 呼叫者。只對這個 downstream publisher 清掉 context，bootstrap lookup
                        // 仍維持既有行為。
                        businessServicesClient.get()
                                .uri("/api/portfolio-advice/latest")
                                .header(AuthConstants.HDR_USER_ID, String.valueOf(admin.id()))
                                .header(AuthConstants.HDR_USER_ROLE, admin.role())
                                .header(AuthConstants.HDR_USER_STATUS, admin.status())
                                .accept(MediaType.APPLICATION_JSON)
                                // 金融數值（建議配置比例、金額）不可先 decode 成 Map/Double 再重編碼；原始
                                // JSON bytes、status 與 content type 直接 relay（沿用
                                // {@code LatestAssetsPublicService} 既有理由）。故 business 的非 2xx 屬
                                // relay 範圍、不經 PublicPortfolioAdviceExceptionAdvice 消毒——該 advice
                                // 只負責 bootstrap lookup（走 .retrieve()）擲出的例外與「owner 不可用」。
                                .exchangeToMono(response -> response.toEntity(byte[].class))
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
            // 僅包住 owner lookup；資料 read 的 non-2xx 仍維持既有 byte-relay，不能因 email
            // 分支被錯誤消毒。lookup 的任何 HTTP/transport/codec/timeout 則統一成 canonical 503。
            return Mono.defer(() -> users.byEmail(trimmed))
                    .timeout(OWNER_LOOKUP_TIMEOUT)
                    .onErrorMap(ignored -> new PublicPortfolioAdviceUnavailableException("指定帳號不可用"))
                    .switchIfEmpty(Mono.error(new PublicPortfolioAdviceUnavailableException("指定帳號不可用")))
                    .flatMap(account -> {
                        if (account == null || account.id() == null || account.role() == null
                                || account.status() == null || !account.isActive()) {
                            // 查無帳號與帳號非 ACTIVE 必須回完全相同的訊息，避免此參數成為帳號列舉工具。
                            return Mono.error(new PublicPortfolioAdviceUnavailableException("指定帳號不可用"));
                        }
                        return Mono.just(account);
                    });
        }
        return users.configuredAdmin()
                .switchIfEmpty(Mono.error(new PublicPortfolioAdviceUnavailableException("主要管理者尚未建立")))
                .flatMap(admin -> {
                    if (admin == null || admin.id() == null || !admin.configuredAdmin() || !admin.isActive()) {
                        return Mono.error(new PublicPortfolioAdviceUnavailableException("主要管理者不可用"));
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
            throw new PublicPortfolioAdviceRequestException();
        }
        return trimmed;
    }
}
