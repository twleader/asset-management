package com.steven.assets.externalmaterials.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class DividendEventKeyMigrationTest {
    @Test void retiredMigrationNeverReadsOrRewritesV1OrV2ImmutableRowsEvenOnRepeatedStartup() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        var migration = new DividendEventKeyMigration(jdbc);
        migration.migrateEventKeys(); migration.migrateEventKeys();
        verifyNoInteractions(jdbc);
        assertThat(DividendEventKeyMigration.class.getMethod("migrateEventKeys")
                .getAnnotation(jakarta.annotation.PostConstruct.class)).isNull();
    }
}
