package com.steven.assets.bff.openapi;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenApiContractServiceTest {

    @Test
    void readsThePackagedOpenApiContractAsUtf8Text() {
        String contract = new OpenApiContractService().readContract();

        assertThat(contract)
                .startsWith("openapi: 3.1.0")
                .contains("paths:")
                .contains("/api/public/commodity-prices:")
                .contains("components:");
    }
}
