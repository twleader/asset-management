package com.steven.assets.bff.openapi;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpenApiContractBffControllerTest {

    @Test
    void exposesOnlyTheExactGetRouteAndDelegatesToTheService() throws NoSuchMethodException {
        OpenApiContractService service = mock(OpenApiContractService.class);
        when(service.readContract()).thenReturn("openapi: 3.1.0\n");

        ResponseEntity<String> response = new OpenApiContractBffController(service).contract();

        RequestMapping classMapping = OpenApiContractBffController.class.getAnnotation(RequestMapping.class);
        GetMapping methodMapping = OpenApiContractBffController.class
                .getMethod("contract")
                .getAnnotation(GetMapping.class);
        assertThat(classMapping.value()).containsExactly("/api/bff/open-api");
        assertThat(methodMapping.value()).containsExactly("/contract");
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString())
                .isEqualTo("application/yaml;charset=UTF-8");
        assertThat(response.getBody()).isEqualTo("openapi: 3.1.0\n");
        verify(service).readContract();
    }
}
