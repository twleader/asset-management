package com.steven.assets.service;

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

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies the historic correction SQL against the same strict Java ledger mathematics. */
class TaiwanLedgerHoldingCostMigrationPostgresTest {
    private static final String V131 = "db/changelog/changes/v1.131.0-ledger-backed-tw-stock-cost.sql";
    private static final String V134 = "db/changelog/changes/v1.134.0-ledger-stock-cost-aggregate-rounding.sql";

    @Test
    void migrationsMatchAllBuyEngineUsePerHoldingAggregateRoundingAndRemainIdempotent() throws Exception {
        try (PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")) {
            postgres.start();
            try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 Statement statement = connection.createStatement()) {
                createSchema(statement);
                seed(statement);

                var engine = new TaiwanLedgerHoldingCostCalculator();
                BigDecimal expectedAllBuy = engine.calculate(List.of(
                        trade(1, "2026-01-01", "買", "2", "10", "20.00"),
                        trade(2, "2026-01-02", "買", "1", "12", "12.00")), new BigDecimal("3"))
                        .orElseThrow().costTwd();

                apply(postgres, V131);
                assertThat(decimal(connection, "SELECT investment_cost FROM stock_holding WHERE stock_code='ALL'"))
                        .isEqualByComparingTo(expectedAllBuy);
                assertThat(decimal(connection, "SELECT investment_cost FROM stock_holding WHERE stock_code='SELL'"))
                        .isEqualByComparingTo("7.50");

                // PostgreSQL's statement snapshot makes v1.131's outer aggregate see pre-update rows (9).
                // v1.134 must recompute after that correction with the Java per-holding rule:
                // round(32)+round(.50)+round(.50)+round(7.50) = 42, not round(40.50) = 41.
                assertThat(decimal(connection, "SELECT total_stock_cost FROM asset_snapshot WHERE id=1"))
                        .isEqualByComparingTo("9");
                apply(postgres, V134);
                assertThat(decimal(connection, "SELECT total_stock_cost FROM asset_snapshot WHERE id=1"))
                        .isEqualByComparingTo("42");

                replaySql(connection, V134);
                assertThat(decimal(connection, "SELECT total_stock_cost FROM asset_snapshot WHERE id=1"))
                        .isEqualByComparingTo("42");
                assertThat(decimal(connection, "SELECT investment_cost FROM stock_holding WHERE stock_code='SELL'"))
                        .isEqualByComparingTo("7.50");
            }
        }
    }

    private static void createSchema(Statement statement) throws Exception {
        statement.execute("CREATE TABLE asset_snapshot (id bigint primary key, owner_user_id bigint not null, snapshot_date date not null, usd_exchange_rate numeric(20,4), total_stock_cost numeric(20,2))");
        statement.execute("CREATE TABLE stock_holding (id bigint primary key, snapshot_id bigint not null, stock_code varchar(20), market varchar(20), currency varchar(10), shares numeric(15,5), investment_cost numeric(20,2), transaction_exchange_rate numeric(20,4))");
        statement.execute("CREATE TABLE asset_transaction (id bigint primary key, owner_user_id bigint not null, transaction_type varchar(10), asset_type varchar(10), asset_code varchar(20), market varchar(20), currency varchar(10), trade_date date not null, shares numeric(15,5), price numeric(17,6), amount numeric(20,2), fee numeric(15,2), transaction_tax numeric(15,2))");
    }

    private static void seed(Statement statement) throws Exception {
        statement.execute("INSERT INTO asset_snapshot VALUES (1, 9, DATE '2026-01-31', 31, 0)");
        statement.execute("INSERT INTO stock_holding VALUES (1, 1, 'ALL', '台股', 'TWD', 3, 1, null)");
        statement.execute("INSERT INTO stock_holding VALUES (2, 1, 'DEC1', '台股', 'TWD', 1, 0, null)");
        statement.execute("INSERT INTO stock_holding VALUES (3, 1, 'DEC2', '台股', 'TWD', 1, 0, null)");
        statement.execute("INSERT INTO stock_holding VALUES (4, 1, 'SELL', '台股', 'TWD', 1, 7.50, null)");
        statement.execute("INSERT INTO asset_transaction VALUES (1,9,'買','股票','ALL','台股','TWD',DATE '2026-01-01',2,10,20,null,null)");
        statement.execute("INSERT INTO asset_transaction VALUES (2,9,'買','股票','ALL','台股','TWD',DATE '2026-01-02',1,12,12,null,null)");
        statement.execute("INSERT INTO asset_transaction VALUES (3,9,'買','股票','DEC1','台股','TWD',DATE '2026-01-01',1,.5,.5,null,null)");
        statement.execute("INSERT INTO asset_transaction VALUES (4,9,'買','股票','DEC2','台股','TWD',DATE '2026-01-01',1,.5,.5,null,null)");
        statement.execute("INSERT INTO asset_transaction VALUES (5,9,'買','股票','SELL','台股','TWD',DATE '2026-01-01',2,5,10,null,null)");
        statement.execute("INSERT INTO asset_transaction VALUES (6,9,'賣','股票','SELL','台股','TWD',DATE '2026-01-02',1,6,6,null,null)");
    }

    private static TaiwanLedgerHoldingCostCalculator.Trade trade(long id, String date, String type, String shares,
                                                                   String price, String amount) {
        return new TaiwanLedgerHoldingCostCalculator.Trade(id, LocalDate.parse(date), type,
                new BigDecimal(shares), new BigDecimal(price), new BigDecimal(amount), null, null);
    }

    private static void apply(PostgreSQLContainer<?> postgres, String migration) throws Exception {
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             ClassLoaderResourceAccessor resources = new ClassLoaderResourceAccessor()) {
            var changeLog = new FormattedSqlChangeLogParser().parse(migration, new ChangeLogParameters(), resources);
            var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase(changeLog, resources, database)) {
                liquibase.update(new Contexts(), new LabelExpression());
            }
        }
    }

    private static void replaySql(Connection connection, String migration) throws Exception {
        try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(migration)) {
            assertThat(input).isNotNull();
            String sql = new String(input.readAllBytes()).lines()
                    .filter(line -> !line.startsWith("--")).reduce("", (left, right) -> left + "\n" + right);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(sql);
            }
        }
    }

    private static BigDecimal decimal(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getBigDecimal(1);
        }
    }
}
