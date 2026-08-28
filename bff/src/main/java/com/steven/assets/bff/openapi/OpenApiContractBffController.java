package com.steven.assets.bff.openapi;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

/**
 * OpenApiView 專屬、沿用既有登入保護的 BFF 文件端點。
 */
@RestController
@RequestMapping("/api/bff/open-api")
public class OpenApiContractBffController {

    private static final MediaType YAML_UTF8 = new MediaType("application", "yaml", StandardCharsets.UTF_8);

    private final OpenApiContractService contractService;

    public OpenApiContractBffController(OpenApiContractService contractService) {
        this.contractService = contractService;
    }

    @GetMapping(value = "/contract", produces = "application/yaml;charset=UTF-8")
    public ResponseEntity<String> contract() {
        return ResponseEntity.ok()
                .contentType(YAML_UTF8)
                .body(contractService.readContract());
    }
}
