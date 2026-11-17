package com.steven.assets.service.tradereport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.service.*;
import com.steven.assets.service.fubon.*;
import com.steven.assets.integration.fubon.*;
import com.steven.assets.dto.AssetTransactionDto;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import com.steven.assets.model.*;
import com.steven.assets.repository.*;
import com.steven.assets.security.TenantGuard;
import com.steven.assets.service.StockMasterService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import javax.sql.DataSource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
@DataJpaTest(properties={"spring.jpa.hibernate.ddl-auto=create-drop","spring.liquibase.enabled=false"},showSql=false)
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({AssetTransactionService.class,FubonTradeWriter.class,FubonSyncOwnerPolicy.class,FubonSyncOwnerDirectoryAdapter.class,JpaFubonSyncFreshness.class,TradeReportImportService.class,TradeReportParser.class,TradeReportLedgerGuard.class,JdbcTradeReportStore.class,TradeReportImportPostgresTest.Config.class})
@Testcontainers @Transactional(propagation=Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TradeReportImportPostgresTest {
 static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
 static { PG.start(); }
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",PG::getJdbcUrl);r.add("spring.datasource.username",PG::getUsername);r.add("spring.datasource.password",PG::getPassword);}
 @TestConfiguration static class Config{@Bean ObjectMapper reportMapper(){return new ObjectMapper().findAndRegisterModules();}@Bean JdbcTemplate jdbc(DataSource ds){return new JdbcTemplate(ds);}}
 @Autowired JdbcTemplate jdbc;@Autowired TradeReportImportService service;@Autowired AppUserRepository users;@Autowired BrokerRepository brokers;@Autowired AssetTransactionRepository tx;@Autowired PlatformTransactionManager manager;@MockitoSpyBean TradeReportLedgerGuard guard;
 @MockitoSpyBean FubonSyncOwnerPolicy ownerPolicy;
 @MockitoSpyBean FubonSyncOwnerDirectoryAdapter directory;
 @Autowired AssetTransactionService manual; @Autowired FubonTradeWriter writer;
 @MockitoBean UserAdminService admins; @MockitoBean TransactionAssetClassService classification;
 @MockitoBean TenantGuard tenant;@MockitoBean StockMasterService stocks;
 Long owner;
 @BeforeAll void schema()throws Exception{jdbc.execute(Files.readString(Path.of("src/main/resources/db/changelog/changes/v1.168.0-broker-trade-report-import.sql")));}
 @BeforeEach void setup(){AppUser u=AppUser.builder().email(UUID.randomUUID()+"@example.invalid").name("synthetic").role("ADMIN").status("ACTIVE").build();owner=users.saveAndFlush(u).getId();if(brokers.findByCode("fubon").isEmpty())brokers.saveAndFlush(BrokerEntity.builder().code("fubon").displayName("富邦證券").active(true).build());doReturn(u.getEmail()).when(directory).syncOwnerEmail();when(admins.isConfiguredAdmin(u.getEmail())).thenReturn(true);
 when(classification.resolve(any(),any())).thenReturn(new TransactionAssetClassService.Classification(null,null,false));
 when(tenant.hasUser()).thenReturn(true);when(tenant.requireCurrentUserId()).thenReturn(owner);when(stocks.resolveNameLocalOnly("0050","台股")).thenReturn("測試ETF");}
 TradeReportImportService.Request req(String message,String hash){return new TradeReportImportService.Request(message,TradeReportParserTest.mime(TradeReportParserTest.html(TradeReportParserTest.row("0050","a1"))),true,true,hash);}
 TradeReportImportService.Response apply(String message){return service.apply(req(message,service.preview(req(message,null)).previewHash()));}
 @Test void concurrentApplyExactlyOnceAndAliasReceipts()throws Exception{String hash=service.preview(req("a",null)).previewHash();try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->service.apply(req("a",hash)));var b=pool.submit(()->service.apply(req("a",hash)));assertThat(a.get(20,TimeUnit.SECONDS).insertedCount()+b.get(20,TimeUnit.SECONDS).insertedCount()).isEqualTo(1);}
 assertThat(apply("b").status()).isEqualTo("ALREADY_IMPORTED");assertThat(jdbc.queryForObject("select count(*) from broker_trade_report_receipt where owner_user_id=?",Integer.class,owner)).isEqualTo(2);
 var changed=new TradeReportImportService.Request("b",Base64.getEncoder().encodeToString((new String(Base64.getDecoder().decode(req("b",null).rawMimeBase64()),java.nio.charset.StandardCharsets.UTF_8)+"\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)),true,true,null);assertThat(service.preview(changed).rows().getFirst().reason()).isEqualTo("MESSAGE_CONTENT_CHANGED");
 var changedApply=new TradeReportImportService.Request(changed.sourceMessageId(),changed.rawMimeBase64(),true,true,service.preview(changed).previewHash());assertThatThrownBy(()->service.apply(changedApply)).isInstanceOf(TradeReportImportService.Conflict.class);
 }
 @Test void manualEditAndDeleteNeverReplayOverwrites(){long id=apply("a").rows().getFirst().transactionId();new TransactionTemplate(manager).execute(s->{guard.lock(owner);var t=tx.findById(id).orElseThrow();t.setNotes("manual correction");tx.saveAndFlush(t);return null;});assertThat(service.preview(req("b",null)).rows().getFirst().reason()).isEqualTo("LEDGER_CHANGED");new TransactionTemplate(manager).execute(s->{guard.lock(owner);tx.deleteById(id);tx.flush();return null;});assertThat(service.preview(req("b",null)).rows().getFirst().reason()).isEqualTo("LEDGER_CHANGED");assertThat(jdbc.queryForObject("select transaction_id from broker_trade_report_import where owner_user_id=?",Long.class,owner)).isNull();}
 @Test void provenanceImmutableAndSdkManualGuard(){apply("a");assertThatThrownBy(()->jdbc.update("update broker_trade_report_receipt set raw_sha256=repeat('0',64) where owner_user_id=?",owner)).isInstanceOf(Exception.class);assertThatThrownBy(()->new TransactionTemplate(manager).execute(s->{guard.lock(owner);guard.rejectEmailCollision(owner,TradeReportParserTest.DAY,"0050","買","台股","富邦證券",null);return null;})).hasMessage("POSSIBLE_DUPLICATE");}

 AssetTransactionDto.CreateAssetTransactionRequest manualRequest(){return new AssetTransactionDto.CreateAssetTransactionRequest("買","股票","測試ETF","0050","台股","TWD","富邦證券",TradeReportParserTest.DAY,new BigDecimal("1000"),new BigDecimal("9.95"),new BigDecimal("9950"),null,null,null,null);}
 void sdkInsert(){writer.insert(owner,brokers.findByCode("fubon").orElseThrow().getId(),List.of(new FubonTradeWriter.PreparedTrade("synthetic-fill","買","測試ETF","0050",TradeReportParserTest.DAY,new BigDecimal("1000"),new BigDecimal("9.95"),new BigDecimal("9950.00"))));}
 // Pause only AFTER the production service has acquired its real PostgreSQL owner row lock.
 void race(Runnable first,Runnable second)throws Exception{
   CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1),secondStarted=new CountDownLatch(1);
   org.mockito.stubbing.Answer<Object> pause=inv->{Object result=inv.callRealMethod();if(Thread.currentThread().getName().equals("first-writer")){locked.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("release timeout");}return result;};
   doAnswer(pause).when(guard).lock(owner);doAnswer(pause).when(ownerPolicy).lockAndRevalidate(owner);
   try(var pool=Executors.newFixedThreadPool(2)){
     var a=pool.submit(()->{Thread.currentThread().setName("first-writer");first.run();});
     assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
     var b=pool.submit(()->{Thread.currentThread().setName("second-writer");secondStarted.countDown();try{second.run();return (Throwable)null;}catch(Throwable e){return e;}});
     assertThat(secondStarted.await(5,TimeUnit.SECONDS)).isTrue();
     try{assertThatThrownBy(()->b.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}finally{release.countDown();}
     a.get(15,TimeUnit.SECONDS);Throwable error=b.get(15,TimeUnit.SECONDS);
     assertThat(error).isInstanceOfAny(TradeReportImportService.Conflict.class,TradeReportProblem.class);
     if(error instanceof TradeReportProblem)assertThat(error).hasMessage("POSSIBLE_DUPLICATE");
   }finally{release.countDown();}
   assertThat(jdbc.queryForObject("select count(*) from asset_transaction where owner_user_id=?",Integer.class,owner)).isEqualTo(1);
 }
 @ParameterizedTest @ValueSource(booleans={true,false}) void manualAndImporterSerializeBothOrders(boolean importFirst)throws Exception{
   String hash=service.preview(req("race",null)).previewHash();Runnable importer=()->service.apply(req("race",hash));Runnable other=()->manual.createAssetTransaction(manualRequest());
   race(importFirst?importer:other,importFirst?other:importer);
 }
 @ParameterizedTest @ValueSource(booleans={true,false}) void sdkAndImporterSerializeBothOrders(boolean importFirst)throws Exception{
   jdbc.execute("CREATE UNIQUE INDEX IF NOT EXISTS ux_asset_transaction_owner_broker_filled_no ON asset_transaction(owner_user_id,broker_filled_no) WHERE broker_filled_no IS NOT NULL");
   String hash=service.preview(req("race",null)).previewHash();Runnable importer=()->service.apply(req("race",hash));Runnable other=this::sdkInsert;
   race(importFirst?importer:other,importFirst?other:importer);
 }
 @Test void inactiveOwnerCannotPreviewOrApply(){String hash=service.preview(req("a",null)).previewHash();var user=users.findById(owner).orElseThrow();user.setStatus("DISABLED");users.saveAndFlush(user);assertThatThrownBy(()->service.preview(req("a",null))).hasMessage("OWNER_UNAVAILABLE");assertThatThrownBy(()->service.apply(req("a",hash))).hasMessage("OWNER_UNAVAILABLE");assertThat(jdbc.queryForObject("select count(*) from asset_transaction where owner_user_id=?",Integer.class,owner)).isZero();}
 @Test void sameReportOtherOwnerIsIsolated(){Long first=owner;apply("shared-message");setup();assertThat(owner).isNotEqualTo(first);assertThat(service.preview(req("shared-message",null)).status()).isEqualTo("READY");apply("shared-message");assertThat(jdbc.queryForObject("select count(*) from broker_trade_report_receipt where source_message_id='shared-message'",Integer.class)).isEqualTo(2);}
 @Test void multirowFailureRollsBackLedgerAndAllProvenance(){
   when(stocks.resolveNameLocalOnly("00679B","台股")).thenReturn("測試ETF");
   String raw=TradeReportParserTest.mime(TradeReportParserTest.html(TradeReportParserTest.row("0050","a1")+TradeReportParserTest.row("00679B","a2")));
   var preview=new TradeReportImportService.Request("batch",raw,true,true,null);String hash=service.preview(preview).previewHash();
   jdbc.execute("ALTER TABLE asset_transaction ADD CONSTRAINT synthetic_reject_second CHECK (asset_code <> '00679B')");
   try{assertThatThrownBy(()->service.apply(new TradeReportImportService.Request("batch",raw,true,true,hash))).isInstanceOf(RuntimeException.class);
   for(String table:List.of("asset_transaction","broker_trade_report_import","broker_trade_report_receipt"))assertThat(jdbc.queryForObject("select count(*) from "+table+" where owner_user_id=?",Integer.class,owner)).isZero();
   }finally{jdbc.execute("ALTER TABLE asset_transaction DROP CONSTRAINT synthetic_reject_second");}
 }
 @Test void migrationReapplyRetainsRowsAndImmutableGuards()throws Exception{
   long id=apply("migration-replay").rows().getFirst().transactionId();
   String migration=Files.readString(Path.of("src/main/resources/db/changelog/changes/v1.168.0-broker-trade-report-import.sql"));
   new TransactionTemplate(manager).execute(status->{jdbc.execute(migration);return null;});
   new TransactionTemplate(manager).execute(status->{jdbc.execute(migration);return null;});
   assertThat(service.preview(req("migration-replay",null)).status()).isEqualTo("ALREADY_IMPORTED");
   assertThat(tx.findById(id)).isPresent();
   for(String table:List.of("broker_trade_report_receipt","broker_trade_report_import")){
     assertThat(jdbc.queryForObject("select count(*) from "+table+" where owner_user_id=?",Integer.class,owner)).isEqualTo(1);
     assertThatThrownBy(()->jdbc.update("delete from "+table+" where owner_user_id=?",owner)).hasMessageContaining("REPORT_EVIDENCE_IMMUTABLE");
   }
   assertThat(jdbc.queryForObject("select count(*) from broker_trade_report_receipt_line l join broker_trade_report_receipt r on r.id=l.receipt_id where r.owner_user_id=?",Integer.class,owner)).isEqualTo(1);
   assertThatThrownBy(()->jdbc.update("delete from broker_trade_report_receipt_line where receipt_id in (select id from broker_trade_report_receipt where owner_user_id=?)",owner)).hasMessageContaining("REPORT_EVIDENCE_IMMUTABLE");
 }
}
