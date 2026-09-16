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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class TwStockMasterNameAuthorityMigrationPostgresTest {
    private static final String MIGRATION = "db/changelog/changes/v1.130.0-tw-stock-master-name-authority.sql";

    @Test
    void correctionOnlyChangesExistingTaiwan006208AndIsIdempotent() throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE stock (code varchar(20), market varchar(20), name varchar(100) NOT NULL, asset_class varchar(20), stock_style varchar(20), bond_term varchar(20), underlying_currency varchar(20), PRIMARY KEY(code, market))");
                statement.execute("INSERT INTO stock VALUES ('006208','台股','FUBON ASSET MANAGEMENT CO LTD F','ETF','INDEX','SHORT','TWD')");
                statement.execute("INSERT INTO stock VALUES ('006208','美股','must retain','OTHER','VALUE','LONG','USD')");
                statement.execute("INSERT INTO stock VALUES ('0050','台股','元大台灣50','ETF','INDEX',NULL,'TWD')");

                apply(postgres);
                assertThat(reapplySql(connection)).isZero();

                assertThat(single(connection, "SELECT name FROM stock WHERE code='006208' AND market='台股'"))
                        .isEqualTo("富邦台50");
                assertThat(single(connection, "SELECT asset_class FROM stock WHERE code='006208' AND market='台股'"))
                        .isEqualTo("ETF");
                assertThat(single(connection, "SELECT stock_style FROM stock WHERE code='006208' AND market='台股'"))
                        .isEqualTo("INDEX");
                assertThat(single(connection, "SELECT bond_term FROM stock WHERE code='006208' AND market='台股'"))
                        .isEqualTo("SHORT");
                assertThat(single(connection, "SELECT underlying_currency FROM stock WHERE code='006208' AND market='台股'"))
                        .isEqualTo("TWD");
                assertThat(single(connection, "SELECT name FROM stock WHERE code='006208' AND market='美股'"))
                        .isEqualTo("must retain");
                assertThat(single(connection, "SELECT name FROM stock WHERE code='0050' AND market='台股'"))
                        .isEqualTo("元大台灣50");
                assertThat(single(connection, "SELECT count(*) FROM stock WHERE code='006208' AND market='台股'"))
                        .isEqualTo("1");
            }
        }
    }

    @Test
    void correctionNeverInsertsAStockMasterWhenTaiwan006208IsAbsent() throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE stock (code varchar(20), market varchar(20), name varchar(100) NOT NULL, asset_class varchar(20), stock_style varchar(20), bond_term varchar(20), underlying_currency varchar(20), PRIMARY KEY(code, market))");
                statement.execute("INSERT INTO stock VALUES ('0050','台股','元大台灣50','ETF','INDEX',NULL,'TWD')");

                apply(postgres);

                assertThat(single(connection, "SELECT count(*) FROM stock WHERE code='006208' AND market='台股'"))
                        .isEqualTo("0");
            }
        }
    }

    /** Executes the migration body outside Liquibase bookkeeping to prove a direct replay is a no-op. */
    private static int reapplySql(Connection connection) throws Exception {
        try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.startsWith("--"))
                    .collect(Collectors.joining("\n"));
            try (Statement statement = connection.createStatement()) {
                return statement.executeUpdate(sql);
            }
        }
    }

    /** Liquibase closes its own connection; fixture replay and readback retain a separate live connection. */
    private static void apply(PostgreSQLContainer<?> postgres) throws Exception {
        try (Connection migrationConnection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             ClassLoaderResourceAccessor resources = new ClassLoaderResourceAccessor()) {
            var changeLog = new FormattedSqlChangeLogParser().parse(MIGRATION, new ChangeLogParameters(), resources);
            var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(migrationConnection));
            try (Liquibase liquibase = new Liquibase(changeLog, resources, database)) {
                liquibase.update(new Contexts(), new LabelExpression());
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
