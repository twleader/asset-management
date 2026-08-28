package com.steven.assets.bff.openapi;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 讀取隨 BFF jar 打包的唯一 9090 OpenAPI 契約。
 */
@Service
public class OpenApiContractService {

    private static final ClassPathResource CONTRACT = new ClassPathResource("docker-external-api.yaml");

    public String readContract() {
        try (InputStream input = CONTRACT.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalStateException("OpenAPI contract is unavailable", exception);
        }
    }
}
