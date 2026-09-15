package com.steven.assets.integration.fubon;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeLogParameters;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.parser.core.formattedsql.FormattedSqlChangeLogParser;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Regression for the one-time correction left by pre-Requirement-155 user payload authority. */
class TwStockMasterUserInputAuthorityMigrationPostgresTest {
    private static final String MIGRATION = "db/changelog/changes/v1.133.0-tw-stock-master-user-input-authority.sql";

    @Test
    void correctsOnlyTaiwan00850AndDirectReplayIsIdempotent() throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE stock (code varchar(20), market varchar(20), name varchar(100) NOT NULL, PRIMARY KEY(code, market))");
                statement.execute("INSERT INTO stock VALUES ('00850','台股','使用者錯填名稱')");
                statement.execute("INSERT INTO stock VALUES ('00850','美股','must retain')");
                statement.execute("INSERT INTO stock VALUES ('0050','台股','元大台灣50')");

                apply(postgres);

                assertThat(single(connection, "SELECT name FROM stock WHERE code='00850' AND market='台股'"))
                        .isEqualTo("元大臺灣ESG永續");
                assertThat(single(connection, "SELECT name FROM stock WHERE code='00850' AND market='美股'"))
                        .isEqualTo("must retain");
                assertThat(single(connection, "SELECT name FROM stock WHERE code='0050' AND market='台股'"))
                        .isEqualTo("元大台灣50");
                assertThat(replaySql(connection)).isZero();
            }
        }
    }

    private static void apply(PostgreSQLContainer<?> postgres) throws Exception {
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             ClassLoaderResourceAccessor resources = new ClassLoaderResourceAccessor()) {
            var changeLog = new FormattedSqlChangeLogParser().parse(MIGRATION, new ChangeLogParameters(), resources);
            var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(changeLog, resources, database)) {
                liquibase.update(new Contexts(), new LabelExpression());
            }
        }
    }

    private static int replaySql(Connection connection) throws Exception {
        try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.startsWith("--")).collect(Collectors.joining("\n"));
            try (Statement statement = connection.createStatement()) {
                return statement.executeUpdate(sql);
            }
        }
    }

    private static String single(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getString(1);
        }
    }
}
