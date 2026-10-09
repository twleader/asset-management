package com.steven.assets.service.srpp;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Requirement 184／Task 484.8：已發布 Swagger 身分＝隨 business-services 發布的 classpath resource 位元組 SHA-256，
 * 且必須等於 {@code docs/openapi/9090-api-swagger.md}（三份由 renderer 位元組一致地產生）。
 */
class PublishedSwaggerIdentityTest {

    @Test
    void sha256EqualsLowercaseHexDigestOfPublishedDocsMarkdown() throws Exception {
        Path docs = Path.of("docs/openapi/9090-api-swagger.md");
        if (!Files.exists(docs)) docs = Path.of("../docs/openapi/9090-api-swagger.md");
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(docs)));

        PublishedSwaggerIdentity identity = new PublishedSwaggerIdentity();

        assertThat(identity.sha256()).contains(expected);
        assertThat(identity.sha256().orElseThrow()).matches("[0-9a-f]{64}");
    }

    @Test
    void missingResourceYieldsEmptyIdentitySoCallersFailClosed() {
        PublishedSwaggerIdentity identity = new PublishedSwaggerIdentity(new ClassPathResource("srpp/does-not-exist.md"));

        assertThat(identity.sha256()).isEmpty();
    }
}
