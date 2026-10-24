package com.steven.assets.service;

import com.steven.assets.dto.NotificationRecipientDto;
import com.steven.assets.model.NotificationRecipient;
import com.steven.assets.repository.NotificationRecipientRepository;
import com.steven.assets.repository.StockAlertGroupRecipientRepository;
import com.steven.assets.repository.StockAlertRecipientRepository;
import com.steven.assets.security.TenantGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Task 509：名稱正規化僅測 service，不啟動 Spring 或資料庫。 */
@ExtendWith(MockitoExtension.class)
class NotificationRecipientServiceTest {

    @Mock private NotificationRecipientRepository repo;
    @Mock private StockAlertRecipientRepository alertRecipientRepo;
    @Mock private StockAlertGroupRecipientRepository groupRecipientRepo;
    @Mock private TenantGuard tenantGuard;

    @InjectMocks private NotificationRecipientService service;

    @Test
    void create會trim名稱並在response保留名稱() {
        when(repo.findByEmail("recipient@example.com")).thenReturn(Optional.empty());
        when(tenantGuard.requireCurrentUserId()).thenReturn(21L);
        when(repo.save(any(NotificationRecipient.class))).thenAnswer(invocation -> invocation.getArgument(0));

        NotificationRecipientDto.Response response = service.create(
                new NotificationRecipientDto.CreateRequest(" recipient@example.com ", "  王小明  ", true));

        ArgumentCaptor<NotificationRecipient> saved = ArgumentCaptor.forClass(NotificationRecipient.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("王小明");
        assertThat(response.name()).isEqualTo("王小明");
    }

    @Test
    void update會trim名稱() {
        NotificationRecipient existing = NotificationRecipient.builder()
                .id(8L).ownerUserId(21L).email("recipient@example.com").name("舊名稱").build();
        when(repo.findById(8L)).thenReturn(Optional.of(existing));
        when(repo.save(existing)).thenReturn(existing);

        NotificationRecipientDto.Response response = service.update(8L,
                new NotificationRecipientDto.UpdateRequest("recipient@example.com", "  新名稱  "));

        verify(tenantGuard).assertOwned(21L);
        assertThat(existing.getName()).isEqualTo("新名稱");
        assertThat(response.name()).isEqualTo("新名稱");
    }

    @Test
    void update會把空白名稱正規化為null() {
        NotificationRecipient existing = NotificationRecipient.builder()
                .id(9L).ownerUserId(21L).email("recipient@example.com").name("原有名稱").build();
        when(repo.findById(9L)).thenReturn(Optional.of(existing));
        when(repo.save(existing)).thenReturn(existing);

        NotificationRecipientDto.Response response = service.update(9L,
                new NotificationRecipientDto.UpdateRequest("recipient@example.com", "   "));

        assertThat(existing.getName()).isNull();
        assertThat(response.name()).isNull();
    }
}
