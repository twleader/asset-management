package com.steven.assets.service.srpp.report;

import com.steven.assets.repository.SrppDecisionRepository;
import com.steven.assets.repository.SrppReportRepository;
import com.steven.assets.repository.SrppReportRepository.ReservationRef;
import com.steven.assets.repository.SrppReportRepository.Scope;
import com.steven.assets.srpp.SrppJcs;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import java.nio.charset.StandardCharsets;
import java.sql.Statement;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static com.steven.assets.service.srpp.report.SrppReportFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/** Real PostgreSQL metadata/body split; production tables, services and sources remain untouched. */
@Testcontainers
class SrppReportReservationCachePostgresTest {
    @Container static final PostgreSQLContainer<?> DB=new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("reservation_cache_test").withUsername("assets").withPassword("test-only");
    JdbcTemplate jdbc;DataSourceTransactionManager manager;SrppReportRepository repository;SrppReportPublisher publisher;Scope scope;
    @BeforeEach void setup()throws Exception{
        var ds=new DriverManagerDataSource(DB.getJdbcUrl(),DB.getUsername(),DB.getPassword());jdbc=new JdbcTemplate(ds);manager=new DataSourceTransactionManager(ds);
        jdbc.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public; CREATE TABLE api_error_log_operation(source varchar(16),operation_key varchar(96),operation_label varchar(96),display_order int,PRIMARY KEY(source,operation_key)); CREATE TABLE app_user(id bigint PRIMARY KEY); INSERT INTO app_user VALUES(7),(8); CREATE TABLE srpp_policy_registry(policy_bundle_sha256 char(64) PRIMARY KEY); CREATE FUNCTION reject_srpp_immutable_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'immutable'; END $$;");
        jdbc.update("INSERT INTO srpp_policy_registry VALUES(?)",AUTHORITY.decision().policy().bundleHash());
        jdbc.execute(new ClassPathResource("db/changelog/changes/v1.150.0-srpp-report-calculations.sql").getContentAsString(StandardCharsets.UTF_8));
        repository=spy(new SrppReportRepository(jdbc));publisher=new SrppReportPublisher(repository,AUTHORITY,new PureSrppReportCalculations(),MAPPER,manager,new SrppReportBuildIdentity());scope=new Scope(7,DATE,"09:05",supported().bundleHash(),SWAGGER,SrppReportModuleCalculator.VERSION);
    }
    @Test void repeatedAllHistoryAndLegacyReadsFetchOnlyColdBodiesAndKeepEveryPriorReservation()throws Exception{
        publisher.publish(scope,qualified("09:05",AT,true),supported(),extras(),states(7,"09:05"),AT);
        publisher.publish(scope,qualified("09:05",AT.plusSeconds(60),false),supported(),extras(),states(7,"09:05"),AT.plusSeconds(60));
        var capture=new SrppReportReservationCapture(mock(SrppDecisionRepository.class),repository);
        String first=read(()->SrppJcs.canonicalize(capture.readReportSource(7,DATE,"12:00",scope.policy())));
        assertThat(SrppJcs.parseStrict(first).path("rows")).isNotEmpty();verify(repository,times(2)).reservationBody(any());
        String repeated=read(()->SrppJcs.canonicalize(capture.readReportSource(7,DATE,"12:00",scope.policy())));assertThat(repeated).isEqualTo(first);
        var legacy=read(()->capture.read(7,DATE,"12:00"));assertThat(legacy).hasSize(2);int lots=0;for(var old:legacy)for(var c:old.path("decision").path("candidates"))lots+=c.path("lots").asInt();assertThat(lots).isEqualTo(4);
        verify(repository,times(2)).reservationBody(any());
        assertThat(read(()->capture.readReportSource(8,DATE,"12:00",scope.policy())).path("rows")).isEmpty();verify(repository,times(2)).reservationBody(any());
    }
    @Test void aNewImmutableBaseFetchesOnlyItsNewBodyAndDoesNotInvalidateEarlierActionableReservations()throws Exception{
        publisher.publish(scope,qualified("09:05",AT,true),supported(),extras(),states(7,"09:05"),AT);
        var capture=new SrppReportReservationCapture(mock(SrppDecisionRepository.class),repository);
        var prior=read(()->capture.readReportSource(7,DATE,"12:00",scope.policy()));verify(repository,times(1)).reservationBody(any());
        publisher.publish(scope,qualified("09:05",AT.plusSeconds(60),false),supported(),extras(),states(7,"09:05"),AT.plusSeconds(60));
        var later=read(()->capture.readReportSource(7,DATE,"12:00",scope.policy()));assertThat(later.path("rows")).isEqualTo(prior.path("rows"));verify(repository,times(2)).reservationBody(any());
        read(()->capture.readReportSource(7,DATE,"12:00",scope.policy()));verify(repository,times(2)).reservationBody(any());
    }
    @Test void metadataCursorStaysTransactionalAndClosesOnFailureWhileBodyLookupBindsEveryImmutableSelector()throws Exception{
        publisher.publish(scope,frozen(7,AT,"09:05","first"),supported(),extras(),states(7,"09:05"),AT);
        publisher.publish(scope,frozen(7,AT.plusSeconds(1),"09:05","second"),supported(),extras(),states(7,"09:05"),AT.plusSeconds(1));
        List<Statement> opened=new ArrayList<>();
        JdbcTemplate observed=new JdbcTemplate(jdbc.getDataSource()){
            @Override protected void applyStatementSettings(Statement statement)throws java.sql.SQLException{super.applyStatementSettings(statement);assertThat(statement.getFetchSize()).isEqualTo(1);assertThat(statement.getConnection().getAutoCommit()).isFalse();opened.add(statement);}
        };
        var cursor=new SrppReportRepository(observed);List<ReservationRef> refs=new ArrayList<>();read(()->{cursor.visitUnlinkedReportReservationMetadata(7,DATE,"12:00",refs::add);return true;});assertThat(refs).hasSize(2);for(var statement:opened)assertThat(statement.isClosed()).isTrue();
        assertThatThrownBy(()->read(()->{cursor.visitUnlinkedReportReservationMetadata(7,DATE,"12:00",ref->{throw new IllegalStateException("metadata callback failure");});return true;})).isInstanceOf(IllegalStateException.class).hasMessage("metadata callback failure");for(var statement:opened)assertThat(statement.isClosed()).isTrue();
        assertThatThrownBy(()->cursor.visitUnlinkedReportReservationMetadata(7,DATE,"12:00",ref->{})).isInstanceOf(IllegalStateException.class).hasMessage("REPORT_RESERVATION_TRANSACTION_REQUIRED");
        ReservationRef ref=refs.getFirst();assertThat(repository.reservationBody(ref)).isPresent();
        var changedHash=new ReservationRef(ref.id(),ref.sharedId(),ref.scope(),ref.consumer(),ref.stateHash(),ref.inputHash(),"0".repeat(64),ref.status(),ref.publishedAt());assertThat(repository.reservationBody(changedHash)).isEmpty();
        var changedOwner=new ReservationRef(ref.id(),ref.sharedId(),new Scope(8,ref.scope().date(),ref.scope().slot(),ref.scope().policy(),ref.scope().swagger(),ref.scope().calculator()),ref.consumer(),ref.stateHash(),ref.inputHash(),ref.contentHash(),ref.status(),ref.publishedAt());assertThat(repository.reservationBody(changedOwner)).isEmpty();
        var changedPublished=new ReservationRef(ref.id(),ref.sharedId(),ref.scope(),ref.consumer(),ref.stateHash(),ref.inputHash(),ref.contentHash(),ref.status(),ref.publishedAt().plusSeconds(1));assertThat(repository.reservationBody(changedPublished)).isEmpty();
    }
    private <T> T read(Supplier<T> supplier){TransactionTemplate read=new TransactionTemplate(manager);read.setReadOnly(true);return read.execute(tx->supplier.get());}
}
