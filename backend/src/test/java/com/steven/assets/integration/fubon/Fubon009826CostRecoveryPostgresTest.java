package com.steven.assets.integration.fubon;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeLogParameters;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.parser.core.formattedsql.FormattedSqlChangeLogParser;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/** Real migration, isolated PostgreSQL and synthetic event matching the authorized recovery shape. */
@Testcontainers
class Fubon009826CostRecoveryPostgresTest {
    private static final String MIGRATION = "db/changelog/changes/v1.159.0-009826-ledger-cost-recovery.sql";
    @Container static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @BeforeEach
    void seed() throws Exception {
        try (Connection c = connection(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS databasechangeloglock, databasechangelog, stock_holding, asset_snapshot, "
                    + "asset_transaction, broker_filled_trade_cost_projection, broker CASCADE");
            s.execute("CREATE TABLE broker (id bigint primary key, code varchar(50), active boolean)");
            s.execute("CREATE TABLE asset_snapshot (id bigint primary key, owner_user_id bigint, snapshot_date date, "
                    + "usd_exchange_rate numeric(10,4),total_stock_cost numeric(20,2))");
            s.execute("CREATE TABLE stock_holding (id bigint primary key, snapshot_id bigint, broker_id bigint, "
                    + "stock_code varchar(20),market varchar(20),currency varchar(3),shares numeric(15,5), "
                    + "investment_cost numeric(20,2), current_value numeric(20,2),transaction_exchange_rate numeric(10,4))");
            s.execute("CREATE TABLE asset_transaction (id bigint primary key,owner_user_id bigint,transaction_type varchar(10),"
                    + "asset_type varchar(10),asset_code varchar(20),market varchar(20),currency varchar(10),channel varchar(30),"
                    + "trade_date date,shares numeric(15,5),price numeric(17,6),amount numeric(20,2),fee numeric(15,2),"
                    + "transaction_tax numeric(15,2),source varchar(20))");
            s.execute("CREATE TABLE broker_filled_trade_cost_projection (owner_user_id bigint,stock_code varchar(20))");
            s.execute("INSERT INTO broker VALUES(1,'fubon',true),(2,'other',true)");
            s.execute("INSERT INTO asset_snapshot VALUES(15,1,DATE '2026-11-02',31,123),(14,1,DATE '2026-11-01',31,777),"
                    + "(16,2,DATE '2026-11-02',31,888)");
            s.execute("INSERT INTO stock_holding VALUES(1,15,1,'009826','台股','TWD',1000,0,10070,null),"
                    + "(2,15,1,'ROUND1','美股','USD',1,0.50,2,1),(3,15,1,'ROUND2','台股','TWD',1,0.50,2,null),"
                    + "(4,14,1,'009826','台股','TWD',1000,0,10070,null),(5,16,1,'009826','台股','TWD',1000,0,10070,null)");
            s.execute("INSERT INTO asset_transaction VALUES(208,1,'買','股票','009826','台股','TWD','富邦證券',"
                    + "DATE '2026-11-02',1000,10.08,10080,20,0,'MANUAL')");
        }
    }

    @Test
    void realLiquibaseCorrectionAndReplayUseNewCostAndPerHoldingRounding() throws Exception {
        apply();
        assertThat(decimal("SELECT investment_cost FROM stock_holding WHERE id=1")).isEqualByComparingTo("10100");
        assertThat(decimal("SELECT total_stock_cost FROM asset_snapshot WHERE id=15")).isEqualByComparingTo("10102");
        assertThat(decimal("SELECT current_value FROM stock_holding WHERE id=1")).isEqualByComparingTo("10070");
        assertThat(decimal("SELECT investment_cost FROM stock_holding WHERE id=4")).isZero();
        assertThat(decimal("SELECT total_stock_cost FROM asset_snapshot WHERE id=14")).isEqualByComparingTo("777");
        assertThat(decimal("SELECT investment_cost FROM stock_holding WHERE id=5")).isZero();
        try (Connection c = connection(); Statement s = c.createStatement();
             var input = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(input).isNotNull();
            s.execute(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertThat(decimal("SELECT investment_cost FROM stock_holding WHERE id=1")).isEqualByComparingTo("10100");
        assertThat(decimal("SELECT total_stock_cost FROM asset_snapshot WHERE id=15")).isEqualByComparingTo("10102");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "UPDATE asset_snapshot SET snapshot_date=DATE '2026-11-03' WHERE id=15",
            "UPDATE asset_snapshot SET owner_user_id=3 WHERE id=15",
            "UPDATE stock_holding SET broker_id=2 WHERE id=1",
            "UPDATE stock_holding SET shares=2000 WHERE id=1",
            "UPDATE stock_holding SET investment_cost=10 WHERE id=1",
            "UPDATE asset_transaction SET fee=0 WHERE id=208",
            "UPDATE asset_transaction SET transaction_tax=NULL WHERE id=208",
            "UPDATE asset_transaction SET channel='其他券商' WHERE id=208",
            "UPDATE asset_transaction SET source='FUBON_SYNC' WHERE id=208",
            "INSERT INTO asset_transaction SELECT 209,owner_user_id,transaction_type,asset_type,asset_code,market,currency,"
                    + "channel,trade_date,shares,price,amount,fee,transaction_tax,source FROM asset_transaction WHERE id=208",
            "INSERT INTO stock_holding SELECT 6,snapshot_id,broker_id,stock_code,market,currency,shares,investment_cost,"
                    + "current_value,transaction_exchange_rate FROM stock_holding WHERE id=1",
            "INSERT INTO broker_filled_trade_cost_projection VALUES(1,'009826')"
    })
    void unprovenOrAlreadyChangedFactsAreNoOp(String mutation) throws Exception {
        try (Connection c = connection(); Statement s = c.createStatement()) { s.execute(mutation); }
        BigDecimal before = decimal("SELECT investment_cost FROM stock_holding WHERE id=1");
        apply();
        assertThat(decimal("SELECT investment_cost FROM stock_holding WHERE id=1")).isEqualByComparingTo(before);
        assertThat(decimal("SELECT total_stock_cost FROM asset_snapshot WHERE id=15")).isEqualByComparingTo("123");
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void apply() throws Exception {
        try (Connection c = connection(); ClassLoaderResourceAccessor resources = new ClassLoaderResourceAccessor()) {
            var changeLog = new FormattedSqlChangeLogParser().parse(MIGRATION, new ChangeLogParameters(), resources);
            var database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c));
            try (Liquibase liquibase = new Liquibase(changeLog, resources, database)) {
                liquibase.update(new Contexts(), new LabelExpression());
            }
        }
    }

    private static BigDecimal decimal(String sql) throws Exception {
        try (Connection c = connection(); Statement s = c.createStatement(); ResultSet rows = s.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getBigDecimal(1);
        }
    }
}
