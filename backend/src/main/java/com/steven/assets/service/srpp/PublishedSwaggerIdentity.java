package com.steven.assets.service.srpp;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

/**
 * Requirement 184／Task 484.8：「已發布 Swagger 身分」＝隨 business-services 發布的 classpath resource
 * {@code srpp/9090-api-swagger.md} 位元組的小寫 hex SHA-256。
 *
 * <p>啟動時只讀一次；該檔由 {@code scripts/render-9090-openapi-docs.rb} 與 {@code docs/openapi/9090-api-swagger.md}
 * 位元組一致地產生（business 的 Docker build context 只有 {@code ./backend}，讀不到 {@code docs/}）。
 * resource 缺失或讀取失敗時 {@link #sha256()} 為空，呼叫端必須 fail closed（503 {@code CONTEXT_NOT_READY}），
 * 不得放行任何 hash。t481、t482 共用本 bean，不得各自讀檔。
 */
@Component
public class PublishedSwaggerIdentity {
    static final String RESOURCE = "srpp/9090-api-swagger.md";
    private static final Logger log = LoggerFactory.getLogger(PublishedSwaggerIdentity.class);
    private final Optional<String> sha256;

    public PublishedSwaggerIdentity() { this(new ClassPathResource(RESOURCE)); }

    PublishedSwaggerIdentity(Resource resource) { this.sha256 = digest(resource); }

    /** 已發布 Swagger Markdown 的小寫 hex SHA-256；resource 缺失時為空。 */
    public Optional<String> sha256() { return sha256; }

    private static Optional<String> digest(Resource resource) {
        if (resource == null || !resource.exists()) {
            log.warn("SRPP 已發布 Swagger 身分不可用：{} 不存在", resource == null ? RESOURCE : resource.getDescription());
            return Optional.empty();
        }
        try (InputStream in = resource.getInputStream()) {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return Optional.of(HexFormat.of().formatHex(sha.digest(in.readAllBytes())));
        } catch (IOException | NoSuchAlgorithmException e) {
            log.warn("SRPP 已發布 Swagger 身分不可用：讀取 {} 失敗 exception={}", resource.getDescription(), e.getClass().getName());
            return Optional.empty();
        }
    }
}
