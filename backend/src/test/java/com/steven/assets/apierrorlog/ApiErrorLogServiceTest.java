package com.steven.assets.apierrorlog;

import com.steven.assets.model.AppUser;
import com.steven.assets.security.AdminRequiredException;
import com.steven.assets.security.CurrentUserContext;
import com.steven.assets.security.UnauthenticatedException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ApiErrorLogServiceTest {
    @Test void business_read_model_rejects_missing_user_and_non_admin_before_repository_access() {
        ApiErrorLogRepository repository = mock(ApiErrorLogRepository.class);
        CurrentUserContext currentUser = new CurrentUserContext();
        ApiErrorLogService service = new ApiErrorLogService(repository, currentUser);

        assertThatThrownBy(() -> service.list("ALL", null, "NEWEST")).isInstanceOf(UnauthenticatedException.class);
        currentUser.setEffectiveUserId(7L);
        currentUser.setRole(AppUser.ROLE_USER);
        assertThatThrownBy(() -> service.operations("ALL")).isInstanceOf(AdminRequiredException.class);

        verifyNoInteractions(repository);
    }

    @Test void all_operations_are_grouped_open_api_then_fubon_then_catalog_order() {
        CurrentUserContext currentUser = new CurrentUserContext();
        currentUser.setEffectiveUserId(7L);
        currentUser.setRole(AppUser.ROLE_ADMIN);

        var operations = new ApiErrorLogService(mock(ApiErrorLogRepository.class), currentUser).operations("ALL");

        int firstFubon = java.util.stream.IntStream.range(0, operations.size())
                .filter(index -> ApiErrorLogOperationCatalog.FUBON_API.equals(operations.get(index).source()))
                .findFirst().orElseThrow();
        assertThat(operations.subList(0, firstFubon)).allMatch(operation ->
                ApiErrorLogOperationCatalog.OPEN_API.equals(operation.source()));
        assertThat(operations.subList(firstFubon, operations.size())).allMatch(operation ->
                ApiErrorLogOperationCatalog.FUBON_API.equals(operation.source()));
        assertThat(operations.getFirst().operationKey()).isEqualTo("OPEN_QUOTES_LIST");
        assertThat(operations.get(firstFubon).operationKey()).isEqualTo("FUBON_PORTFOLIO_READ");
    }
}
