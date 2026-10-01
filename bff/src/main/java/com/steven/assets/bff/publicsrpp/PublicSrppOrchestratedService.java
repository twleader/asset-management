package com.steven.assets.bff.publicsrpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.steven.assets.bff.security.AuthConstants;
import com.steven.assets.bff.security.BffUser;
import com.steven.assets.bff.security.BusinessUserClient;
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
class PublicSrppOrchestratedService {
    private static final Duration OWNER_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration BUSINESS_TIMEOUT = Duration.ofSeconds(5);
    private static final Set<String> PROBLEM_FIELDS = Set.of("type", "title", "status", "detail", "instance", "code", "retryable");
    private final BusinessUserClient users;
    private final WebClient business;

    PublicSrppOrchestratedService(BusinessUserClient users,
                                  @Qualifier("businessServicesClient") WebClient business) {
        this.users = users;
        this.business = business;
    }

    Mono<byte[]> read(ServerHttpRequest request, SrppOrchestratedQuery.Route route) {
        return Mono.defer(() -> {
            SrppOrchestratedQuery query = SrppOrchestratedQuery.parse(route, request.getQueryParams(), request.getHeaders());
            return owner(query.email()).flatMap(owner -> fetch(query, owner));
        });
    }

    private Mono<BffUser> owner(String email) {
        Mono<BffUser> lookup = email == null ? Mono.defer(users::configuredAdmin) : Mono.defer(() -> users.byEmail(email));
        return lookup.timeout(OWNER_TIMEOUT)
                .onErrorMap(error -> unavailable(error))
                .switchIfEmpty(Mono.error(() -> unavailable(null)))
                .flatMap(user -> {
                    boolean valid = user != null && user.id() != null && user.role() != null && user.status() != null
                            && user.isActive() && (email != null || user.configuredAdmin());
                    return valid ? Mono.just(user) : Mono.error(unavailable(null));
                });
    }

    private Mono<byte[]> fetch(SrppOrchestratedQuery query, BffUser owner) {
        return business.get().uri(query.businessUri())
                .header(AuthConstants.HDR_USER_ID, String.valueOf(owner.id()))
                .header(AuthConstants.HDR_USER_ROLE, owner.role())
                .header(AuthConstants.HDR_USER_STATUS, owner.status())
                .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON)
                .exchangeToMono(response -> handle(query, response))
                .timeout(BUSINESS_TIMEOUT)
                .onErrorMap(error -> !(error instanceof SrppOrchestratedProblemException), PublicSrppOrchestratedService::classify)
                .contextWrite(ctx -> ctx.delete(AuthConstants.CTX_IDENTITY));
    }

    private Mono<byte[]> handle(SrppOrchestratedQuery query, ClientResponse response) {
        HttpStatusCode status = response.statusCode();
        MediaType mediaType = response.headers().contentType().orElse(null);
        return response.bodyToMono(byte[].class).defaultIfEmpty(new byte[0]).flatMap(bytes -> {
            if (status.is2xxSuccessful()) {
                try {
                    SrppOrchestratedResponseValidator.validate(query, mediaType, bytes);
                    return Mono.just(bytes);
                } catch (RuntimeException invalid) {
                    return Mono.error(new SrppOrchestratedProblemException(SrppOrchestratedProblemCatalog.UPSTREAM_INVALID, invalid));
                }
            }
            return Mono.error(translateProblem(query, status.value(), mediaType, bytes));
        });
    }

    static SrppOrchestratedProblemException translateProblem(SrppOrchestratedQuery query, int status,
                                                               MediaType contentType, byte[] bytes) {
        try {
            if (contentType == null || !"application".equalsIgnoreCase(contentType.getType())
                    || !"problem+json".equalsIgnoreCase(contentType.getSubtype())) throw new SrppPayloadException("invalid problem media type");
            JsonNode problem = SrppDailyContextResponseValidator.parseStrict(bytes);
            if (!problem.isObject() || problem.size() != PROBLEM_FIELDS.size()) throw new SrppPayloadException("invalid problem fields");
            for (String field : PROBLEM_FIELDS) if (!problem.has(field)) throw new SrppPayloadException("missing problem field");
            if (!"about:blank".equals(problem.path("type").asText()) || !query.route().path.equals(problem.path("instance").asText())
                    || !problem.path("status").isIntegralNumber() || problem.path("status").intValue() != status
                    || !problem.path("title").isTextual() || !problem.path("detail").isTextual()
                    || !problem.path("retryable").isBoolean()) throw new SrppPayloadException("invalid problem values");
            SrppOrchestratedProblemCatalog code = SrppOrchestratedProblemCatalog.valueOf(problem.path("code").asText());
            if (code.status().value() != status || !code.relayableAt(status)) throw new SrppPayloadException("problem code/status mismatch");
            return new SrppOrchestratedProblemException(code);
        } catch (RuntimeException invalid) {
            return new SrppOrchestratedProblemException(SrppOrchestratedProblemCatalog.UPSTREAM_INVALID, invalid);
        }
    }

    private static Throwable classify(Throwable error) {
        if (error instanceof TimeoutException || error instanceof WebClientRequestException) {
            return new SrppOrchestratedProblemException(SrppOrchestratedProblemCatalog.CONTEXT_NOT_READY, error);
        }
        return new SrppOrchestratedProblemException(SrppOrchestratedProblemCatalog.UPSTREAM_INVALID, error);
    }
    private static SrppOrchestratedProblemException unavailable(Throwable cause) {
        return new SrppOrchestratedProblemException(SrppOrchestratedProblemCatalog.OWNER_UNAVAILABLE, cause);
    }
}
