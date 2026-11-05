package com.steven.assets.service;

import com.steven.assets.model.CanonicalDividend.*;
import com.steven.assets.repository.JdbcCanonicalDividendRepository;
import com.steven.assets.repository.JdbcDividendCurrentStateRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers
class CanonicalDividendReconciliationPostgresTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("dividend_fixture").withUsername("fixture").withPassword("fixture-only");
    static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-05T08:00:00Z"),ZoneOffset.UTC);
    JdbcTemplate jdbc;JdbcCanonicalDividendRepository repository;CanonicalDividendReconciliationService service;TransactionTemplate tx;
    @BeforeEach void setup(){
        var source=new DriverManagerDataSource(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));repository=new JdbcCanonicalDividendRepository(jdbc);service=new CanonicalDividendReconciliationService(repository,CLOCK);
        jdbc.execute("DROP SCHEMA public CASCADE");jdbc.execute("CREATE SCHEMA public");
        jdbc.execute("CREATE TABLE stock_dividend_snapshot(id BIGSERIAL PRIMARY KEY,stock_code TEXT,market TEXT,provider TEXT,scope_from DATE,scope_to DATE,content_hash TEXT,normalizer_version INT,amount_basis TEXT,date_groups_complete BOOLEAN)");
        jdbc.execute("CREATE TABLE stock_dividend_fetch_observation(id BIGSERIAL PRIMARY KEY,snapshot_id BIGINT,status TEXT,complete BOOLEAN,observed_at TIMESTAMPTZ,source_available_at TIMESTAMPTZ,scope_from DATE,scope_to DATE)");
        jdbc.execute("CREATE TABLE stock_dividend_snapshot_event(id BIGSERIAL PRIMARY KEY,snapshot_id BIGINT,year INT,ex_dividend_date DATE,ex_rights_date DATE,cash_dividend NUMERIC(30,18),stock_dividend NUMERIC(30,18),cash_payment_date DATE,stock_payment_date DATE,upstream_component_id TEXT,source_available_at TIMESTAMPTZ)");
        jdbc.execute("CREATE TABLE stock_dividend_history(id BIGSERIAL PRIMARY KEY,stock_code TEXT,market TEXT,year INT,cash_dividend NUMERIC(30,18),stock_dividend NUMERIC(30,18),ex_dividend_date DATE,ex_rights_date DATE,cash_payment_date DATE,stock_payment_date DATE,source TEXT,event_key TEXT,event_status TEXT,updated_at TIMESTAMP DEFAULT NOW(),canonical_date DATE,source_snapshot_id BIGINT,resolution_status TEXT DEFAULT 'LEGACY',resolution_reason TEXT,yield_pct NUMERIC,previous_close NUMERIC,fill_days INT)");
        jdbc.execute("CREATE UNIQUE INDEX canonical_active ON stock_dividend_history(stock_code,market,canonical_date) WHERE event_status='ACTIVE' AND canonical_date IS NOT NULL");
    }
    void evidence(int id,String code,String amount){
        jdbc.update("INSERT INTO stock_dividend_snapshot VALUES(?,?,'台股','FinMind[TaiwanStockDividend]','2015-01-01','2026-11-04',?,2,'NOMINAL_PER_EX_DATE',true)",id,code,"hash"+id);
        jdbc.update("INSERT INTO stock_dividend_fetch_observation VALUES(?,?, 'PARTIAL',false,?,NULL,'2015-01-01','2026-11-04')",id,id,java.sql.Timestamp.from(CLOCK.instant().minusSeconds(100-id)));
        jdbc.update("INSERT INTO stock_dividend_snapshot_event(snapshot_id,year,ex_dividend_date,cash_dividend,stock_dividend,upstream_component_id) VALUES(?,2026,'2026-03-17',?,0,'issuer-period-1')",id,new BigDecimal(amount));
    }
    void legacy(String amount){jdbc.update("INSERT INTO stock_dividend_history(stock_code,market,year,ex_dividend_date,cash_dividend,stock_dividend,source,event_status) VALUES('2330','台股',2026,'2026-03-17',?,0,'legacy-composite','ACTIVE')",new BigDecimal(amount));}
    CanonicalDividendReconciliationService.Applied apply(String token){return tx.execute(status->service.apply("2330","台股",token));}

    @Test void previewIsPureApplyPreservesLegacyAmountsAndImmutableEvidenceThenReplaysWithoutWrites(){
        legacy("6");legacy("6.000035");evidence(1,"2330","6.00003573");
        var historyBefore=jdbc.queryForList("SELECT * FROM stock_dividend_history ORDER BY id");var evidenceBefore=jdbc.queryForList("SELECT * FROM stock_dividend_snapshot_event ORDER BY id");
        var plan=service.preview("2330","台股");assertThat(jdbc.queryForList("SELECT * FROM stock_dividend_history ORDER BY id")).isEqualTo(historyBefore);
        assertThat(plan.noOp()).isFalse();assertThat(plan.diff().getFirst().cash()).isEqualByComparingTo("6.00003573");
        var first=apply(plan.planToken());assertThat(first.changedRows()).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT cash_dividend,event_status FROM stock_dividend_history WHERE id<=2 ORDER BY id")).satisfies(rows->{assertThat(rows.get(0).get("cash_dividend")).isEqualTo(new BigDecimal("6.000000000000000000"));assertThat(rows.get(1).get("cash_dividend")).isEqualTo(new BigDecimal("6.000035000000000000"));assertThat(rows).allSatisfy(row->assertThat(row.get("event_status")).isEqualTo("SUPERSEDED"));});
        assertThat(jdbc.queryForList("SELECT * FROM stock_dividend_snapshot_event ORDER BY id")).isEqualTo(evidenceBefore);
        var after=jdbc.queryForList("SELECT * FROM stock_dividend_history ORDER BY id");assertThat(service.preview("2330","台股").noOp()).isTrue();
        assertThat(apply(plan.planToken()).alreadyApplied()).isTrue();assertThat(jdbc.queryForList("SELECT * FROM stock_dividend_history ORDER BY id")).isEqualTo(after);
        assertThatThrownBy(()->apply(plan.planToken().substring(0,plan.planToken().length()-1)+(plan.planToken().endsWith("0")?"1":"0"))).isInstanceOf(CanonicalDividendReconciliationService.StalePlanException.class);
    }
    @Test void newObservationInvalidatesTokenAndRevisionUpdatesStableCanonicalIdWithEnrichmentCleared(){
        legacy("6");evidence(1,"2330","6.00003573");var old=service.preview("2330","台股");apply(old.planToken());
        Long id=jdbc.queryForObject("SELECT id FROM stock_dividend_history WHERE event_status='ACTIVE'",Long.class);
        jdbc.update("UPDATE stock_dividend_history SET previous_close=1000,yield_pct=.6,fill_days=2 WHERE id=?",id);
        evidence(2,"2330","7.00000137");assertThatThrownBy(()->apply(old.planToken())).isInstanceOf(CanonicalDividendReconciliationService.StalePlanException.class);
        apply(service.preview("2330","台股").planToken());
        assertThat(jdbc.queryForObject("SELECT id FROM stock_dividend_history WHERE event_status='ACTIVE'",Long.class)).isEqualTo(id);
        var row=jdbc.queryForMap("SELECT * FROM stock_dividend_history WHERE id=?",id);assertThat(row.get("cash_dividend")).isEqualTo(new BigDecimal("7.000001370000000000"));assertThat(row.get("previous_close")).isNull();assertThat(row.get("yield_pct")).isNull();assertThat(row.get("fill_days")).isNull();
    }
    @Test void explicitNewerUnknownDateReplacesCurrentAmountButPartialAbsencePreservesIt() {
        evidence(1,"2330","6");apply(service.preview("2330","台股").planToken());
        jdbc.update("INSERT INTO stock_dividend_snapshot SELECT 2,stock_code,market,provider,scope_from,scope_to,'empty-partial',normalizer_version,amount_basis,false FROM stock_dividend_snapshot WHERE id=1");
        jdbc.update("INSERT INTO stock_dividend_fetch_observation VALUES(2,2,'PARTIAL',false,?,NULL,'2015-01-01','2026-11-04')",java.sql.Timestamp.from(CLOCK.instant().minusSeconds(98)));
        assertThat(service.preview("2330","台股").noOp()).isTrue();
        jdbc.update("INSERT INTO stock_dividend_snapshot SELECT 3,stock_code,market,provider,scope_from,scope_to,'explicit-unknown',normalizer_version,amount_basis,false FROM stock_dividend_snapshot WHERE id=1");
        jdbc.update("INSERT INTO stock_dividend_fetch_observation VALUES(3,3,'PARTIAL',false,?,NULL,'2015-01-01','2026-11-04')",java.sql.Timestamp.from(CLOCK.instant().minusSeconds(97)));
        jdbc.update("INSERT INTO stock_dividend_snapshot_event(snapshot_id,year,ex_dividend_date,cash_dividend,stock_dividend,upstream_component_id) VALUES(3,2026,'2026-03-17',NULL,NULL,'issuer-period-1')");
        var plan=service.preview("2330","台股");assertThat(plan.noOp()).isFalse();assertThat(plan.diff().getFirst().resolution()).isEqualTo("UNRESOLVED");
        var display=new CanonicalDividendDisplayReadService(repository).read(List.of(new Key("2330","台股")),CLOCK.instant()).get(new Key("2330","台股"));
        assertThat(display.days().getFirst().cash()).isNull();
        apply(plan.planToken());
        assertThat(jdbc.queryForMap("SELECT cash_dividend,stock_dividend,resolution_status FROM stock_dividend_history WHERE event_status='ACTIVE'"))
                .containsEntry("cash_dividend",null).containsEntry("stock_dividend",null).containsEntry("resolution_status","UNRESOLVED");
        assertThat(apply(plan.planToken()).alreadyApplied()).isTrue();
    }
    @Test void immutableOldCancellationIsNotRevivedAndUndatedLegacyCannotLookLikeEmptySuccess(){
        legacy("6");jdbc.update("UPDATE stock_dividend_history SET event_status='CANCELLED'");evidence(1,"2330","6.00003573");var before=jdbc.queryForMap("SELECT * FROM stock_dividend_history");
        apply(service.preview("2330","台股").planToken());assertThat(jdbc.queryForMap("SELECT * FROM stock_dividend_history WHERE id=1")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT resolution_status FROM stock_dividend_history WHERE event_status='ACTIVE'",String.class)).isEqualTo("UNRESOLVED");
        jdbc.update("INSERT INTO stock_dividend_history(stock_code,market,year,cash_dividend,event_status) VALUES('NO_DATE','台股',2026,9,'ACTIVE')");
        var plan=service.preview("NO_DATE","台股");assertThat(plan.noOp()).isFalse();tx.execute(status->service.apply("NO_DATE","台股",plan.planToken()));
        assertThat(jdbc.queryForObject("SELECT cash_dividend FROM stock_dividend_history WHERE stock_code='NO_DATE'",BigDecimal.class)).isEqualByComparingTo("9");
        assertThat(jdbc.queryForObject("SELECT resolution_status FROM stock_dividend_history WHERE stock_code='NO_DATE'",String.class)).isEqualTo("UNRESOLVED");
    }
    @ParameterizedTest
    @CsvSource({"00878,2026-08-18,1.01,2026-09-11","00881,2026-01-20,2.65,2026-02-12","00881,2026-08-18,4.6,2026-09-11"})
    void legacyCashConfirmationPreservesCancelledEvidenceAndBothRepeatedPlansAreZeroWrites(String code,String date,String cash,String pay) {
        evidence(1,code,cash);
        jdbc.update("UPDATE stock_dividend_snapshot_event SET ex_dividend_date=CAST(? AS date),cash_payment_date=CAST(? AS date) WHERE snapshot_id=1",date,pay);
        jdbc.update("INSERT INTO stock_dividend_history(stock_code,market,year,cash_dividend,stock_dividend,ex_dividend_date,cash_payment_date,source,event_status) VALUES(?,'台股',2026,?,NULL,CAST(? AS date),CAST(? AS date),'FUBON_SDK','ACTIVE')",code,new BigDecimal(cash),date,pay);
        jdbc.update("INSERT INTO stock_dividend_history(stock_code,market,year,cash_dividend,stock_dividend,ex_dividend_date,cash_payment_date,source,event_status) VALUES(?,'台股',2026,?,0,CAST(? AS date),CAST(? AS date),'legacy-source','CANCELLED')",code,new BigDecimal(cash),date,pay);
        var cancelledBefore=jdbc.queryForList("SELECT * FROM stock_dividend_history WHERE event_status='CANCELLED'");
        var evidenceBefore=jdbc.queryForList("SELECT * FROM stock_dividend_snapshot_event");
        var plan=service.preview(code,"台股");assertThat(plan.diff().getFirst().reason()).isEqualTo("保留既有ACTIVE現金，依完整主表確認股票股利為零");
        assertThat(tx.execute(status->service.apply(code,"台股",plan.planToken())).changedRows()).isEqualTo(2);
        var historyAfter=jdbc.queryForList("SELECT * FROM stock_dividend_history ORDER BY id");
        assertThat(tx.execute(status->service.apply(code,"台股",plan.planToken())).alreadyApplied()).isTrue();
        var repeated=service.preview(code,"台股");assertThat(repeated.noOp()).isTrue();
        assertThat(tx.execute(status->service.apply(code,"台股",repeated.planToken())).changedRows()).isZero();
        assertThat(jdbc.queryForList("SELECT * FROM stock_dividend_history ORDER BY id")).isEqualTo(historyAfter);
        assertThat(jdbc.queryForList("SELECT * FROM stock_dividend_history WHERE event_status='CANCELLED'")).isEqualTo(cancelledBefore);
        assertThat(jdbc.queryForList("SELECT * FROM stock_dividend_snapshot_event")).isEqualTo(evidenceBefore);
        assertThat(jdbc.queryForMap("SELECT cash_dividend,stock_dividend,resolution_status FROM stock_dividend_history WHERE event_status='ACTIVE'"))
                .containsEntry("cash_dividend",new BigDecimal(cash).setScale(18)).containsEntry("stock_dividend",BigDecimal.ZERO.setScale(18)).containsEntry("resolution_status","RESOLVED");
    }
    @Test void canonicalCancellationIsNeverChangedOrBypassedByLegacyCashConfirmation() {
        evidence(1,"2330","1.01");legacy("1.01");jdbc.update("UPDATE stock_dividend_history SET stock_dividend=NULL");
        jdbc.update("INSERT INTO stock_dividend_history(stock_code,market,year,cash_dividend,stock_dividend,ex_dividend_date,canonical_date,source_snapshot_id,resolution_status,source,event_status,event_key) VALUES('2330','台股',2026,NULL,NULL,'2026-03-17','2026-03-17',99,'RESOLVED','canonical','CANCELLED','tombstone')");
        var before=jdbc.queryForMap("SELECT * FROM stock_dividend_history WHERE event_status='CANCELLED'");
        var plan=service.preview("2330","台股");assertThat(plan.diff().getFirst().resolution()).isEqualTo("UNRESOLVED");apply(plan.planToken());
        assertThat(jdbc.queryForMap("SELECT * FROM stock_dividend_history WHERE event_status='CANCELLED'")).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT resolution_status FROM stock_dividend_history WHERE event_status='ACTIVE'",String.class)).isEqualTo("UNRESOLVED");
    }
    @Test void failedInsertRollsBackEverySupersession(){
        legacy("6");evidence(1,"2330","6.00003573");var before=jdbc.queryForList("SELECT * FROM stock_dividend_history");
        jdbc.execute("CREATE FUNCTION reject_canonical() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.canonical_date IS NOT NULL THEN RAISE EXCEPTION 'fixture rollback'; END IF; RETURN NEW; END $$");
        jdbc.execute("CREATE TRIGGER fixture_reject BEFORE INSERT ON stock_dividend_history FOR EACH ROW EXECUTE FUNCTION reject_canonical()");
        var plan=service.preview("2330","台股");assertThatThrownBy(()->apply(plan.planToken())).isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForList("SELECT * FROM stock_dividend_history")).isEqualTo(before);
    }
    @Test void legacyWriterSharesAdvisoryLockAndCannotAppendOldRevisionAfterCanonicalCommit() throws Exception {
        legacy("6");evidence(1,"2330","6.00003573");var plan=service.preview("2330","台股");
        var oldWriter=new JdbcDividendCurrentStateRepository(jdbc);var executor=Executors.newSingleThreadExecutor();CountDownLatch begun=new CountDownLatch(1);
        Future<?> pending;
        try{
            pending=tx.execute(status->{repository.lock(new Key("2330","台股"));var future=executor.submit(()->{begun.countDown();tx.execute(inner->{oldWriter.upsertHistoricalEvent("2330","台股","OLD",new DividendCurrentStateRepository.ProjectedEvent("old",2026,LocalDate.of(2026,3,17),new BigDecimal("100"),BigDecimal.ZERO,null,null));return null;});});
                try{assertThat(begun.await(2,TimeUnit.SECONDS)).isTrue();Thread.sleep(100);assertThat(future.isDone()).isFalse();}catch(InterruptedException ex){throw new RuntimeException(ex);}service.apply("2330","台股",plan.planToken());return future;});
            pending.get(5,TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_dividend_history WHERE event_status='ACTIVE'",Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT cash_dividend FROM stock_dividend_history WHERE event_status='ACTIVE'",BigDecimal.class)).isEqualByComparingTo("6.00003573");
        }finally{executor.shutdownNow();}
    }
    @Test void observationPageIsBoundedByDistinctSymbolsRatherThanRawRows(){
        evidence(1,"2330","6");for(int i=2;i<=250;i++)jdbc.update("INSERT INTO stock_dividend_fetch_observation VALUES(?,1,'PARTIAL',false,NOW(),NULL,'2015-01-01','2026-11-04')",i);
        evidence(300,"0050","2");var batch=repository.observationsAfter(0,100);assertThat(batch.keys()).containsExactly(new Key("2330","台股"),new Key("0050","台股"));assertThat(batch.throughId()).isEqualTo(300);
        var first=repository.observationsAfter(0,1);assertThat(first.keys()).containsExactly(new Key("2330","台股"));assertThat(first.throughId()).isEqualTo(299);
    }
    @Test void enrichmentRunsOnlyAfterSuccessfulCommitAndNeverOnRepeatedNoOp(){
        legacy("6");evidence(1,"2330","6.00003573");
        var enrichment=org.mockito.Mockito.mock(DividendCashEnrichmentService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"enrichment",enrichment);
        org.mockito.Mockito.doAnswer(call->{assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_dividend_history WHERE event_status='ACTIVE' AND resolution_status='RESOLVED'",Integer.class)).isEqualTo(1);return null;})
                .when(enrichment).enrich(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.any());
        var token=service.preview("2330","台股").planToken();
        tx.execute(status->{service.apply("2330","台股",token);org.mockito.Mockito.verifyNoInteractions(enrichment);return null;});
        org.mockito.Mockito.verify(enrichment,org.mockito.Mockito.times(1)).enrich("2330","台股",CLOCK.instant());
        apply(token);org.mockito.Mockito.verifyNoMoreInteractions(enrichment);
    }
    @Test void completeFutureAbsenceCreatesCancellationTombstoneWhichRemainsCancelledAfterDate(){
        var date=LocalDate.of(2026,12,1);var key=new Key("AAPL","美股");
        for(int id=1;id<=2;id++){
            jdbc.update("INSERT INTO stock_dividend_snapshot VALUES(?,'AAPL','美股','NASDAQ_DIVIDEND_CALENDAR','2026-11-05','2026-12-20',?,2,'NOMINAL_PER_EX_DATE',true)",id,"hash"+id);
            jdbc.update("INSERT INTO stock_dividend_fetch_observation VALUES(?,?,?,true,?,NULL,'2026-11-05','2026-12-20')",id,id,id==1?"COMPLETE":"EMPTY_COMPLETE",java.sql.Timestamp.from(CLOCK.instant().minusSeconds(100-id)));
        }
        jdbc.update("INSERT INTO stock_dividend_snapshot_event(snapshot_id,year,ex_dividend_date,cash_dividend,stock_dividend,upstream_component_id) VALUES(1,2026,'2026-12-01',7,0,'known-next')");
        jdbc.update("INSERT INTO stock_dividend_history(stock_code,market,year,cash_dividend,stock_dividend,ex_dividend_date,canonical_date,source_snapshot_id,resolution_status,event_status,event_key,source) VALUES('AAPL','美股',2026,7,0,'2026-12-01','2026-12-01',1,'RESOLVED','ACTIVE',?,'NASDAQ_DIVIDEND_CALENDAR')",com.steven.assets.model.CanonicalDividendResolver.key(key,date));
        var token=service.preview("AAPL","美股").planToken();tx.execute(status->service.apply("AAPL","美股",token));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_dividend_history WHERE event_status='ACTIVE'",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM stock_dividend_history WHERE event_status='CANCELLED'",Integer.class)).isEqualTo(1);
        assertThat(tx.execute(status->service.apply("AAPL","美股",token)).alreadyApplied()).isTrue();
        var past=Clock.fixed(Instant.parse("2026-12-02T08:00:00Z"),ZoneOffset.UTC);
        assertThat(new CanonicalDividendDisplayReadService(repository).read(List.of(key),past.instant()).get(key).days()).singleElement().satisfies(day->assertThat(day.withdrawn()).isTrue());
        assertThat(new CanonicalDividendReconciliationService(repository,past).preview("AAPL","美股").noOp()).isTrue();
    }

}
