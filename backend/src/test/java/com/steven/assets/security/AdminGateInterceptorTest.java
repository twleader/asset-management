package com.steven.assets.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** configured-admin bootstrap 必須只放行 exact GET，不能意外放寬整個 internal users namespace。 */
class AdminGateInterceptorTest {

    @Test
    void configuredAdminExactGetIsAllowedWithoutIdentity() {
        CurrentUserContext current = mock(CurrentUserContext.class);
        when(current.hasUser()).thenReturn(false);

        assertThat(new AdminGateInterceptor(current).preHandle(request("GET", "/internal/users/configured-admin"),
                mock(HttpServletResponse.class), new Object())).isTrue();
    }

    @Test
    void configuredAdminPostAndDescendantAndOtherUsersEndpointsRequireIdentity() {
        CurrentUserContext current = mock(CurrentUserContext.class);
        when(current.hasUser()).thenReturn(false);
        AdminGateInterceptor gate = new AdminGateInterceptor(current);

        assertBlocked(gate, "POST", "/internal/users/configured-admin");
        assertBlocked(gate, "GET", "/internal/users/configured-admin/child");
        assertBlocked(gate, "GET", "/internal/users");
        assertBlocked(gate, "PATCH", "/internal/users/1/status");
    }

    private static void assertBlocked(AdminGateInterceptor gate, String method, String path) {
        assertThatThrownBy(() -> gate.preHandle(request(method, path), mock(HttpServletResponse.class), new Object()))
                .isInstanceOf(UnauthenticatedException.class);
    }

    private static HttpServletRequest request(String method, String path) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(path);
        return request;
    }
}
