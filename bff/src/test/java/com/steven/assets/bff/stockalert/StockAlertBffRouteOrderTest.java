package com.steven.assets.bff.stockalert;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link StockAlertBffController} 與既有 {@link StockAlertBffRoutes} 萬用 route 的<b>先後順序</b>
 * （Requirement 54 / Task 254）。
 *
 * <p><b>為什麼需要這支測試：</b>既有 route 是 {@code /api/bff/stock-alert/**} → rewrite 成
 * {@code /api/stock-alerts${seg}}，會把 {@code /export-setting/browse-gdrive} 錯誤地轉成
 * {@code /api/stock-alerts/export-setting/browse-gdrive}（business 端沒有這個端點 → 404）。
 * 正確行為靠 WebFlux 的 {@code RequestMappingHandlerMapping}（order 0）先於 Gateway 的
 * {@code RoutePredicateHandlerMapping}（order 1）——<b>一旦落到 route，使用者看到的症狀只是資料夾樹
 * 展不開，不會有任何錯誤指向 BFF 路由順序</b>，所以必須有測試守門。
 *
 * <p><b>刻意不用容器內 curl 驗這件事</b>：(a) {@code asset-bff} 的 runtime 映像沒有 curl（Dockerfile
 * 無任何 {@code apk add}，healthcheck 用的是 busybox wget）；(b) 就算換成 wget 也探不到——
 * {@code SecurityConfig} 是 {@code .anyExchange().authenticated()}，Security filter 在兩個
 * HandlerMapping 之前執行，「controller 接走」與「落到 route → business 404」<b>都回 401</b>，
 * 狀態碼無法區分。直接問 {@code RequestMappingHandlerMapping} 才是這件事的直接證據。
 */
@SpringBootTest
@TestPropertySource(properties = {
        // 本測試只問 handler mapping 的解析結果，不發出任何實際請求
        "business-services.url=http://localhost:1",
        "ADMIN_EMAIL=test@example.com"
})
class StockAlertBffRouteOrderTest {

    /**
     * 必須指名 bean：actuator 另外註冊了一支 {@code controllerEndpointHandlerMapping}，
     * 依型別注入會撞「expected single matching bean but found 2」。
     */
    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    void 本機資料夾瀏覽由controller接走而非落到萬用route() {
        assertHandledByController("/api/bff/stock-alert/export-setting/browse", "browseExportDir");
    }

    @Test
    void Drive資料夾瀏覽由controller接走而非落到萬用route() {
        assertHandledByController("/api/bff/stock-alert/export-setting/browse-gdrive",
                "browseGdriveExportDir");
    }

    @Test
    void 設定讀取由controller接走() {
        assertHandledByController("/api/bff/stock-alert/export-setting", "getExportSetting");
    }

    /**
     * 既有端點<b>不得</b>被新 controller 搶走：警示 CRUD／reorder／check／lookup-name／lookup-code／
     * recipients／groups 全部靠萬用 route passthrough，新 controller 的 {@code @RequestMapping} 寫太寬
     * 就會把它們一起吃掉（症狀是整個警示頁壞掉）。
     */
    @Test
    void 既有警示端點仍留給萬用route() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/bff/stock-alert").build());
        Object handler = handlerMapping.getHandler(exchange).block();
        assertThat(handler).as("列表端點應由 Gateway route passthrough，不該被 controller 接走").isNull();

        MockServerWebExchange lookup = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/bff/stock-alert/lookup-name?code=2330&market=台股").build());
        assertThat(handlerMapping.getHandler(lookup).block())
                .as("lookup-name 應由 Gateway route passthrough").isNull();
    }

    private void assertHandledByController(String path, String expectedMethodName) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get(path).build());
        Object handler = handlerMapping.getHandler(exchange).block();
        assertThat(handler)
                .as("%s 必須由 StockAlertBffController 接走，否則會落到萬用 route 被 rewrite 成 "
                        + "/api/stock-alerts%s 而 404", path, path.replace("/api/bff/stock-alert", ""))
                .isInstanceOf(HandlerMethod.class);
        HandlerMethod hm = (HandlerMethod) handler;
        assertThat(hm.getBeanType()).isEqualTo(StockAlertBffController.class);
        assertThat(hm.getMethod().getName()).isEqualTo(expectedMethodName);
    }
}
