package com.steven.assets.service;

import liquibase.change.core.RawSQLChange;
import liquibase.changelog.ChangeLogParameters;
import liquibase.database.core.PostgresDatabase;
import liquibase.parser.core.formattedsql.FormattedSqlChangeLogParser;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.statement.SqlStatement;
import liquibase.statement.core.RawSqlStatement;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FubonTechnicalMigrationFormatTest {
    private static final String MIGRATION =
            "db/changelog/changes/v1.122.0-fubon-technical-indicator-and-basic-info.sql";

    @Test
    void liquibaseKeepsTheDollarQuotedTriggerFunctionInOnePostgresStatement() throws Exception {
        try (var resources = new ClassLoaderResourceAccessor()) {
            var changeLog = new FormattedSqlChangeLogParser().parse(
                    MIGRATION, new ChangeLogParameters(), resources);

            assertThat(changeLog.getChangeSets()).hasSize(1);
            assertThat(changeLog.getChangeSets().getFirst().getId())
                    .isEqualTo("v1.122.0-fubon-technical-indicator-and-basic-info");
            assertThat(changeLog.getChangeSets().getFirst().getChanges()).hasSize(1);

            var change = (RawSQLChange) changeLog.getChangeSets().getFirst().getChanges().getFirst();
            assertThat(change.isSplitStatements()).isFalse();

            SqlStatement[] statements = change.generateStatements(new PostgresDatabase());
            assertThat(statements).hasSize(1);
            assertThat(((RawSqlStatement) statements[0]).getSql())
                    .contains("CREATE OR REPLACE FUNCTION reject_fubon_technical_history_mutation()")
                    .contains("RAISE EXCEPTION 'fubon technical history is immutable';")
                    .contains("$$ LANGUAGE plpgsql;")
                    .contains("CREATE TRIGGER trg_stock_technical_indicator_immutable")
                    .contains("CREATE TRIGGER trg_fubon_technical_capture_member_immutable");
        }
    }
}
