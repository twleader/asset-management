package com.steven.assets.repository;

import com.steven.assets.model.AssetSnapshot;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Task 449：實際執行 repository 查詢，驗證 owner 隔離與資料庫端的最多兩個 roots。 */
@DataJpaTest(showSql = false, properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.liquibase.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class AssetSnapshotHistoryRepositoryTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired AssetSnapshotRepository snapshots;
    @Autowired EntityManager entityManager;

    @Test
    void selectedAndImmediatePreviousAreReturnedAscendingWithoutOtherOwnersOrLaterRows() {
        save(9L, "2024-01-01");
        AssetSnapshot previous = save(9L, "2024-02-01");
        AssetSnapshot selected = save(9L, "2024-03-01");
        save(9L, "2024-04-01");
        save(88L, "2024-02-29");
        save(88L, "2024-03-01");
        entityManager.flush();
        entityManager.clear();

        var result = snapshots.findHistoryWindow(9L, selected.getId(), selected.getSnapshotDate());

        assertThat(result).extracting(AssetSnapshot::getId).containsExactly(previous.getId(), selected.getId());
        assertThat(result).extracting(AssetSnapshot::getOwnerUserId).containsOnly(9L);
    }

    @Test
    void firstSnapshotDoesNotBorrowEarlierRowsFromAnotherOwner() {
        AssetSnapshot first = save(9L, "2024-03-01");
        save(9L, "2024-04-01");
        save(88L, "2024-02-01");
        entityManager.flush();
        entityManager.clear();

        var result = snapshots.findHistoryWindow(9L, first.getId(), first.getSnapshotDate());

        assertThat(result).extracting(AssetSnapshot::getId).containsExactly(first.getId());
    }

    @Test
    void explicitOwnerPredicateStillProtectsSelectedIdWhenTenantFilterIsAbsent() {
        AssetSnapshot otherOwner = save(88L, "2024-03-01");
        entityManager.flush();
        entityManager.clear();

        assertThat(snapshots.findHistoryWindow(9L, otherOwner.getId(), otherOwner.getSnapshotDate())).isEmpty();
    }

    private AssetSnapshot save(Long ownerId, String date) {
        return snapshots.save(AssetSnapshot.builder().ownerUserId(ownerId)
                .snapshotDate(LocalDate.parse(date)).build());
    }
}
