package com.steven.assets.controller;

import com.steven.assets.service.AnnualDividendService;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class AnnualDividendControllerTest {
    @Test void unknownOwnerSelectorsAndDuplicateKeysAreRejectedBeforeService() {
        var service = mock(AnnualDividendService.class);
        var controller = new AnnualDividendController(service);
        for (String key : List.of("owner", "email", "unknown")) {
            var query = new LinkedMultiValueMap<String, String>(); query.add(key, "value");
            assertThatThrownBy(() -> controller.summary(query)).isInstanceOf(IllegalArgumentException.class);
        }
        var query = new LinkedMultiValueMap<String, String>(); query.add("year", "2026"); query.add("year", "2025");
        assertThatThrownBy(() -> controller.summary(query)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(service);
    }
}
