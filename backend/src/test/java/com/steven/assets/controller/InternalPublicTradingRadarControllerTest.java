package com.steven.assets.controller;

import com.steven.assets.service.PublicTradingRadarProjectionService;
import com.steven.assets.service.PublicTradingRadarProjectionService.PublicTradingRadarProjectionException;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 以真實 Spring advice 排序驗證：查無／不合法 selector 必須是 404／400，不可被全域 handler 吞成 500。 */
class InternalPublicTradingRadarControllerTest {
    private static PublicTradingRadarProjectionException error(HttpStatus status) {
        try {
            var c = PublicTradingRadarProjectionException.class.getDeclaredConstructor(HttpStatus.class);
            c.setAccessible(true);
            return c.newInstance(status);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static MockMvc mvc(PublicTradingRadarProjectionService service) {
        var ctx = new GenericWebApplicationContext(new MockServletContext());
        ctx.registerBean("projectionService", PublicTradingRadarProjectionService.class, () -> service);
        new AnnotatedBeanDefinitionReader(ctx).register(WebConfig.class);
        ctx.refresh();
        return MockMvcBuilders.webAppContextSetup(ctx).build();
    }

    @org.springframework.context.annotation.Configuration
    @EnableWebMvc
    static class WebConfig {
        // 全域 handler 先註冊（與正式環境相同），只有 @Order 能讓雷達 advice 優先。
        @org.springframework.context.annotation.Bean GlobalExceptionHandler globalAdvice() {
            return new GlobalExceptionHandler();
        }
        @org.springframework.context.annotation.Bean InternalPublicTradingRadarController controller(
                PublicTradingRadarProjectionService service) {
            return new InternalPublicTradingRadarController(service);
        }
        @org.springframework.context.annotation.Bean InternalPublicTradingRadarExceptionAdvice radarAdvice() {
            return new InternalPublicTradingRadarExceptionAdvice();
        }
    }

    @Test
    void missingStockIs404NotMaskedByGlobalHandler() throws Exception {
        var service = mock(PublicTradingRadarProjectionService.class);
        when(service.stock("ZZZZ", "美股")).thenThrow(error(HttpStatus.NOT_FOUND));
        mvc(service).perform(get("/internal/public-trading-radar/current/stock")
                        .param("stockCode", "ZZZZ").param("market", "美股"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Public trading radar request failed"));
    }

    @Test
    void invalidSelectorIs400() throws Exception {
        var service = mock(PublicTradingRadarProjectionService.class);
        when(service.stock("bad!", "台股")).thenThrow(error(HttpStatus.BAD_REQUEST));
        mvc(service).perform(get("/internal/public-trading-radar/current/stock")
                        .param("stockCode", "bad!").param("market", "台股"))
                .andExpect(status().isBadRequest());
    }
}
