package com.steven.assets.bff.publicsrpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.bff.security.AuthConstants;
import com.steven.assets.bff.security.BffUser;
import com.steven.assets.bff.security.BusinessUserClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeoutException;

@Service
class PublicSrppUsReportService {
    private static final Duration OWNER_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration BUSINESS_TIMEOUT = Duration.ofSeconds(5);
    private static final Set<String> PROBLEM_FIELDS = Set.of("type", "title", "status", "detail", "instance", "code", "retryable");
    private final java.time.Clock clock;
    private final BusinessUserClient users;
    private final WebClient business;

    @Autowired
    PublicSrppUsReportService(BusinessUserClient users,
                                  @Qualifier("businessServicesClient") WebClient business,
                                  @Qualifier("srppReportClock") java.time.Clock clock) {
        this.users = users;
        this.business = business;
        this.clock = clock;
    }

    Mono<byte[]> read(ServerHttpRequest request) {
        return Mono.defer(() -> {
            SrppUsReportQuery query = SrppUsReportQuery.parse(request.getQueryParams(), request.getHeaders(), clock);
            return owner(query.email()).flatMap(owner -> fetch(query, owner));
        });
    }

    private Mono<BffUser> owner(String email) {
        Mono<BffUser> lookup = email == null ? Mono.defer(users::configuredAdmin) : Mono.defer(() -> users.byEmail(email));
        return lookup.timeout(OWNER_TIMEOUT)
                .onErrorMap(error -> unavailable(error))
                .switchIfEmpty(Mono.error(() -> unavailable(null)))
                .flatMap(user -> {
                    boolean valid = user != null && user.id() != null && user.id() > 0 && user.id() <= 9007199254740991L && user.role() != null && user.status() != null
                            && user.isActive() && (email != null || user.configuredAdmin());
                    return valid ? Mono.just(user) : Mono.error(unavailable(null));
                });
    }

    private Mono<byte[]> fetch(SrppUsReportQuery query, BffUser owner) {
        return business.get().uri(query.businessUri())
                .header(AuthConstants.HDR_USER_ID, String.valueOf(owner.id()))
                .header(AuthConstants.HDR_USER_ROLE, owner.role())
                .header(AuthConstants.HDR_USER_STATUS, owner.status())
                .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)
                .exchangeToMono(response -> handle(query, owner, response))
                .timeout(BUSINESS_TIMEOUT)
                .onErrorMap(error -> !(error instanceof SrppUsReportProblemException), PublicSrppUsReportService::classify)
                .contextWrite(ctx -> ctx.delete(AuthConstants.CTX_IDENTITY));
    }

    private Mono<byte[]> handle(SrppUsReportQuery query, BffUser owner, ClientResponse response) {
        HttpStatusCode status = response.statusCode();
        MediaType mediaType = response.headers().contentType().orElse(null);
        return response.bodyToMono(byte[].class).defaultIfEmpty(new byte[0]).flatMap(bytes -> {
            if (status.value() == 200) {
                try {
                    SrppUsReportResponseValidator.validate(query, owner.id(), mediaType, bytes);
                    return Mono.just(bytes);
                } catch (RuntimeException invalid) {
                    return Mono.error(new SrppUsReportProblemException(SrppUsReportProblemCatalog.UPSTREAM_INVALID, invalid));
                }
            }
            return Mono.error(translateProblem(query, status.value(), mediaType, bytes));
        });
    }

    static SrppUsReportProblemException translateProblem(SrppUsReportQuery query, int status,
                                                               MediaType contentType, byte[] bytes) {
        try {
            if (contentType == null || !"application".equalsIgnoreCase(contentType.getType())
                    || !"problem+json".equalsIgnoreCase(contentType.getSubtype())) throw new SrppPayloadException("invalid problem media type");
            JsonNode problem = SrppDailyContextResponseValidator.parseStrict(bytes);
            if (!problem.isObject() || problem.size() != PROBLEM_FIELDS.size()) throw new SrppPayloadException("invalid problem fields");
            for (String field : PROBLEM_FIELDS) if (!problem.has(field)) throw new SrppPayloadException("missing problem field");
            if (!"about:blank".equals(problem.path("type").asText()) || !SrppUsReportQuery.PATH.equals(problem.path("instance").asText())
                    || !problem.path("status").isIntegralNumber() || problem.path("status").intValue() != status
                    || !problem.path("title").isTextual() || !problem.path("detail").isTextual()
                    || !problem.path("retryable").isBoolean()) throw new SrppPayloadException("invalid problem values");
            SrppUsReportProblemCatalog code = SrppUsReportProblemCatalog.valueOf(problem.path("code").asText());
            if (code.status().value() != status || problem.path("retryable").booleanValue() != code.retryable()) throw new SrppPayloadException("problem code/status mismatch");
            return new SrppUsReportProblemException(code);
        } catch (RuntimeException invalid) {
            return new SrppUsReportProblemException(SrppUsReportProblemCatalog.UPSTREAM_INVALID, invalid);
        }
    }

    private static Throwable classify(Throwable error) {
        if (error instanceof TimeoutException || error instanceof WebClientRequestException) {
            return new SrppUsReportProblemException(SrppUsReportProblemCatalog.CONTEXT_NOT_READY, error);
        }
        return new SrppUsReportProblemException(SrppUsReportProblemCatalog.UPSTREAM_INVALID, error);
    }
    private static SrppUsReportProblemException unavailable(Throwable cause) {
        return new SrppUsReportProblemException(SrppUsReportProblemCatalog.OWNER_UNAVAILABLE, cause);
    }
}
