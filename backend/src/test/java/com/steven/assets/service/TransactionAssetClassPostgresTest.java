package com.steven.assets.service;

import com.steven.assets.dto.AssetTransactionDto;
import com.steven.assets.model.*;
import com.steven.assets.repository.*;
import com.steven.assets.security.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql = false, properties = {"spring.jpa.hibernate.ddl-auto=create-drop", "spring.liquibase.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AssetTransactionService.class, TransactionAssetClassService.class, InstitutionService.class, AssetClassifier.class})
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TransactionAssetClassPostgresTest {
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        p.add("spring.datasource.username", POSTGRES::getUsername);
        p.add("spring.datasource.password", POSTGRES::getPassword);
    }
    @MockitoBean TenantGuard guard;
    @MockitoBean CurrentUserContext user;
    @MockitoBean StockMasterService stockMaster;
    @MockitoBean SettingsClassificationResolver settings;
    @Autowired AssetTransactionService service;
    @Autowired AssetTransactionRepository ledger;
    @Autowired FundClassOverrideRepository overrides;
    @Autowired AssetClassRepository categories;
    @Autowired PlatformTransactionManager manager;
    @Autowired EntityManager em;
    @BeforeEach void setup() {
        when(guard.requireCurrentUserId()).thenReturn(1L);
        when(user.isAdmin()).thenReturn(true);
        tx(() -> {
            em.createNativeQuery("DROP TRIGGER IF EXISTS reject_ledger_commit ON asset_transaction").executeUpdate();
            em.createNativeQuery("DROP FUNCTION IF EXISTS reject_ledger_commit()").executeUpdate();
            ledger.deleteAllInBatch(); overrides.deleteAllInBatch(); categories.deleteAllInBatch();
            categories.saveAndFlush(AssetClass.builder().code("BOND").displayName("債券").sortOrder(1).active(true).build());
        });
    }
    @Test void sharedAuthorityChangesAtomicallyAndOldClientPreservesClassificationAndProductType() {
        var saved = service.createAssetTransaction(request("收益基金", new AssetTransactionDto.AssetClassChange("BOND")));
        assertThat(saved.assetType()).isEqualTo("基金");
        assertThat(saved.effectiveAssetClass()).isEqualTo("BOND");
        tx(() -> assertThat(overrides.findById("收益基金").orElseThrow().getAssetClass()).isEqualTo("BOND"));
        service.createAssetTransaction(request("收益基金", null));
        assertThat(service.getAssetTransactionsByYear().getFirst().records()).hasSize(2)
                .allSatisfy(row -> assertThat(row.assetClassOverride()).isEqualTo("BOND"));
        when(user.isAdmin()).thenReturn(false);
        var updated = service.updateAssetTransaction(saved.id(), request("收益基金", null));
        assertThat(updated.amount()).isEqualByComparingTo("100");
        assertThat(updated.assetClassOverride()).isEqualTo("BOND");
        assertThatThrownBy(() -> service.updateAssetTransaction(saved.id(), request("收益基金", new AssetTransactionDto.AssetClassChange(null))))
                .isInstanceOf(AdminRequiredException.class);
        tx(() -> assertThat(ledger.findAll()).hasSize(2));
        when(user.isAdmin()).thenReturn(true);
        service.updateAssetTransaction(saved.id(), request("收益基金", new AssetTransactionDto.AssetClassChange(null)));
        tx(() -> assertThat(overrides.findById("收益基金")).isEmpty());
        assertThat(service.getAssetTransactionsByYear().getFirst().records()).hasSize(2)
                .allSatisfy(row -> assertThat(row.assetClassOverride()).isNull());
    }
    @Test void clearingCategoryPreservesOtherSharedFundOverrides() {
        tx(() -> overrides.saveAndFlush(FundClassOverride.builder().fundName("收益基金")
                .assetClass("BOND").stockStyle("INCOME").bondTerm("LONG").build()));
        service.createAssetTransaction(request("收益基金", new AssetTransactionDto.AssetClassChange(null)));
        tx(() -> {
            var row = overrides.findById("收益基金").orElseThrow();
            assertThat(row.getAssetClass()).isNull();
            assertThat(row.getStockStyle()).isEqualTo("INCOME");
            assertThat(row.getBondTerm()).isEqualTo("LONG");
        });
    }
    @Test void deferredLedgerCommitFailureRollsBackGlobalClassification() {
        tx(() -> {
            em.createNativeQuery("CREATE FUNCTION reject_ledger_commit() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RAISE EXCEPTION ''test-only failure''; RETURN NEW; END'").executeUpdate();
            em.createNativeQuery("CREATE CONSTRAINT TRIGGER reject_ledger_commit AFTER INSERT ON asset_transaction DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION reject_ledger_commit()").executeUpdate();
        });
        assertThatThrownBy(() -> service.createAssetTransaction(request("新基金", new AssetTransactionDto.AssetClassChange("BOND"))))
                .isInstanceOf(RuntimeException.class);
        tx(() -> { assertThat(ledger.findAll()).isEmpty(); assertThat(overrides.findById("新基金")).isEmpty(); });
    }
    @Test void invalidClassificationAndForeignOwnerCannotWriteEitherSide() {
        assertThatThrownBy(() -> service.createAssetTransaction(request("新基金", new AssetTransactionDto.AssetClassChange("CASH"))))
                .isInstanceOf(IllegalArgumentException.class);
        var saved = service.createAssetTransaction(request("新基金", null));
        assertThatThrownBy(() -> service.updateAssetTransaction(saved.id(),
                request("改名基金", new AssetTransactionDto.AssetClassChange("CASH"))))
                .isInstanceOf(IllegalArgumentException.class);
        tx(() -> assertThat(ledger.findById(saved.id()).orElseThrow().getAssetName()).isEqualTo("新基金"));
        doThrow(new TenantAccessException()).when(guard).assertOwned(1L);
        assertThatThrownBy(() -> service.updateAssetTransaction(saved.id(), request("新基金", new AssetTransactionDto.AssetClassChange("BOND"))))
                .isInstanceOf(TenantAccessException.class);
        tx(() -> { assertThat(ledger.findAll()).hasSize(1); assertThat(overrides.findAll()).isEmpty(); });
    }
    private AssetTransactionDto.CreateAssetTransactionRequest request(String name, AssetTransactionDto.AssetClassChange change) {
        return new AssetTransactionDto.CreateAssetTransactionRequest("買", "基金", name, null, "台股", "TWD", "銀行",
                LocalDate.of(2026, 11, 7), BigDecimal.ONE, new BigDecimal("100"), new BigDecimal("100"),
                BigDecimal.ZERO, BigDecimal.ZERO, null, null, change);
    }
    private void tx(Runnable work) { new TransactionTemplate(manager).executeWithoutResult(status -> work.run()); }
}
