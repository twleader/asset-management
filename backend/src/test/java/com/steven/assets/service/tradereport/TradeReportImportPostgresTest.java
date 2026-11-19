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
@Import({TradeReportFeeSourceLoader.class,JdbcTradeReportFeeEventStore.class,FubonSettlementReceiptRepository.class,FubonSettlementReceiptCapture.class,AssetTransactionService.class,FubonTradeWriter.class,FubonSyncOwnerPolicy.class,FubonSyncOwnerDirectoryAdapter.class,JpaFubonSyncFreshness.class,TradeReportImportService.class,TradeReportParser.class,TradeReportLedgerGuard.class,JdbcTradeReportStore.class,TradeReportImportPostgresTest.Config.class})
@Testcontainers @Transactional(propagation=Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TradeReportImportPostgresTest {
 static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
 static { PG.start(); }
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("spring.datasource.url",PG::getJdbcUrl);r.add("spring.datasource.username",PG::getUsername);r.add("spring.datasource.password",PG::getPassword);}
 @TestConfiguration static class Config{@Bean ObjectMapper reportMapper(){return new ObjectMapper().findAndRegisterModules();}@Bean JdbcTemplate jdbc(DataSource ds){return new JdbcTemplate(ds);}}
 @Autowired TradeReportFeeEventStore events;
 @Autowired JdbcTemplate jdbc;@Autowired TradeReportImportService service;@Autowired AppUserRepository users;@Autowired BrokerRepository brokers;@Autowired AssetTransactionRepository tx;@Autowired PlatformTransactionManager manager;@MockitoSpyBean TradeReportLedgerGuard guard;
 @MockitoSpyBean FubonSyncOwnerPolicy ownerPolicy;
 @MockitoSpyBean FubonSyncOwnerDirectoryAdapter directory;
 @Autowired AssetTransactionService manual; @Autowired FubonTradeWriter writer;
 @MockitoBean UserAdminService admins; @MockitoBean TransactionAssetClassService classification;
 @MockitoSpyBean FubonSettlementReceiptStore settlements; @Autowired FubonSettlementReceiptCapture capture; @MockitoBean FubonSrppSourceStore orders;
 @MockitoBean TenantGuard tenant;@MockitoBean StockMasterService stocks;
 Long owner;
 @BeforeAll void schema()throws Exception{jdbc.execute(Files.readString(Path.of("src/main/resources/db/changelog/changes/v1.168.0-broker-trade-report-import.sql")));jdbc.execute(Files.readString(Path.of("src/main/resources/db/changelog/changes/v1.169.0-fubon-settlement-receipt.sql")));jdbc.execute(Files.readString(Path.of("src/main/resources/db/changelog/changes/v1.170.0-trade-report-fee-event.sql")));}
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
 @Test void savedFeeEvidenceSurvivesLatestFailureAndManualFeeEditConflicts(){
   var imported=apply("fee-replay");long id=imported.rows().getFirst().transactionId();var saved=imported.rows().getFirst().feeProvenance();
   when(settlements.latest(owner)).thenReturn(Optional.of(new FubonSettlementReceiptStore.Receipt(99,owner,TradeReportParserTest.DAY,java.time.Instant.now(),"UNAVAILABLE",null,"invalid",new ObjectMapper().createObjectNode(),"SETTLEMENT_UNAVAILABLE")));
   var replay=service.preview(req("fee-alias",null));assertThat(replay.status()).isEqualTo("ALREADY_IMPORTED");assertThat(replay.rows().getFirst().feeProvenance()).isEqualTo(saved);
   new TransactionTemplate(manager).execute(status->{guard.lock(owner);var ledger=tx.findById(id).orElseThrow();ledger.setFee(new BigDecimal("42"));tx.saveAndFlush(ledger);return null;});
   assertThat(service.preview(req("fee-alias",null)).rows().getFirst().reason()).isEqualTo("LEDGER_CHANGED");assertThat(tx.findById(id).orElseThrow().getFee()).isEqualByComparingTo("42");
 }
 @ParameterizedTest @ValueSource(booleans={true,false}) void sourceAppendAndApplySerializeUnderSameOwner(boolean captureFirst)throws Exception{
   String hash=service.preview(req("source-race",null)).previewHash();
   CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1),started=new CountDownLatch(1);
   org.mockito.stubbing.Answer<Object> pause=inv->{Object result=inv.callRealMethod();if(Thread.currentThread().getName().equals("first-source-writer")){locked.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("timeout");}return result;};
   doAnswer(pause).when(guard).lock(owner);doAnswer(pause).when(ownerPolicy).lockAndRevalidate(owner);
   Runnable append=()->capture.unavailable(owner,java.time.Instant.now(),FubonSettlementOutcome.SETTLEMENT_FAILED);
   Runnable importer=()->service.apply(req("source-race",hash));
   try(var pool=Executors.newFixedThreadPool(2)){
     var a=pool.submit(()->{Thread.currentThread().setName("first-source-writer");(captureFirst?append:importer).run();});
     assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
     var b=pool.submit(()->{Thread.currentThread().setName("second-source-writer");started.countDown();try{(captureFirst?importer:append).run();return (Throwable)null;}catch(Throwable failure){return failure;}});
     assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();try{assertThatThrownBy(()->b.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}finally{release.countDown();}
     a.get(15,TimeUnit.SECONDS);Throwable failure=b.get(15,TimeUnit.SECONDS);
     if(captureFirst)assertThat(failure).isInstanceOf(TradeReportProblem.class).hasMessage("PREVIEW_HASH_MISMATCH");else assertThat(failure).isNull();
   }finally{release.countDown();}
   assertThat(settlements.latest(owner)).isPresent();
   assertThat(jdbc.queryForObject("select count(*) from asset_transaction where owner_user_id=?",Integer.class,owner)).isEqualTo(captureFirst?0:1);
   if(!captureFirst)assertThat(service.preview(req("source-race",null)).status()).isEqualTo("ALREADY_IMPORTED");
 }

 TradeReportImportService.Request lateReq(boolean reconcile,String hash){return new TradeReportImportService.Request("late",TradeReportParserTest.mime(TradeReportParserTest.html(TradeReportParserTest.row("0050","synthetic1")).replace("11 月 17 日","10 月 5 日")),true,true,hash,reconcile);}
 TradeReportImportService.Response applyLate(boolean reconcile){return service.apply(lateReq(reconcile,service.preview(lateReq(reconcile,null)).previewHash()));}
 void seedLateSources(){
   var r=TradeReportFeeResolverTest.receipt(TradeReportFeeResolverTest.settlement());
   new TransactionTemplate(manager).execute(status->{guard.lock(owner);settlements.append(new FubonSettlementReceiptStore.Receipt(0,owner,r.queryDate(),r.observedAt(),r.status(),r.accountFingerprint(),r.payloadHash(),r.payload(),r.reason()));return null;});
   var o=TradeReportFeeResolverTest.snapshot(TradeReportFeeResolverTest.orders());when(orders.latest(owner,o.date())).thenReturn(Optional.of(new FubonSrppSourceStore.Observation(o.id(),owner,o.date(),o.accountFingerprint(),o.capturedAt(),o.status(),o.revision(),o.payload())));
 }
 @Test void threeDifferentUnknownFeeRowsImportOnceAndReplay(){
   when(stocks.resolveNameLocalOnly(anyString(),eq("台股"))).thenReturn("測試ETF");
   String raw=TradeReportParserTest.mime(TradeReportParserTest.html(TradeReportParserTest.row("0050","a1")+TradeReportParserTest.row("00679B","a2")+TradeReportParserTest.row("00878","a3")));
   var request=new TradeReportImportService.Request("three",raw,true,true,null);var preview=service.preview(request);assertThat(preview.status()).isEqualTo("READY");assertThat(preview.rows()).allMatch(r->r.fee()==null&&r.transactionTax()==null);
   var apply=new TradeReportImportService.Request("three",raw,true,true,preview.previewHash());assertThat(service.apply(apply).insertedCount()).isEqualTo(3);assertThat(service.apply(apply).insertedCount()).isZero();
 }
 @Test void realSqlSourceFailureRollsBackOnlyIndependentReader(){
   doAnswer(inv->{jdbc.execute("SELECT * FROM synthetic_missing_fee_source");return Optional.empty();}).when(settlements).latest(owner);
   var preview=service.preview(req("sql-fail",null));assertThat(preview.rows().getFirst().feeProvenance().reason()).isEqualTo("FEE_SOURCE_UNAVAILABLE");
   assertThat(service.apply(req("sql-fail",preview.previewHash())).insertedCount()).isEqualTo(1);
   assertThat(jdbc.queryForObject("select count(*) from asset_transaction where owner_user_id=?",Integer.class,owner)).isEqualTo(1);
 }
 @Test void lateFeeUpdatesOnceWithBoundRetryAndStableReplay(){
   long id=applyLate(false).rows().getFirst().transactionId();String original=jdbc.queryForObject("select canonical_facts from broker_trade_report_import where owner_user_id=?",String.class,owner);
   seedLateSources();var preview=service.preview(lateReq(true,null));assertThat(preview.status()).isEqualTo("FEE_UPDATE_READY");assertThat(preview.rows().getFirst().fee()).isEqualByComparingTo("20");
   var request=lateReq(true,preview.previewHash());var applied=service.apply(request);assertThat(applied.insertedCount()).isZero();assertThat(applied.feeUpdatedCount()).isEqualTo(1);
   assertThat(tx.findById(id).orElseThrow().getFee()).isEqualByComparingTo("20");assertThat(service.apply(request).feeUpdatedCount()).isZero();
   assertThatThrownBy(()->service.apply(lateReq(false,preview.previewHash()))).hasMessage("PREVIEW_HASH_MISMATCH");
   capture.unavailable(owner,java.time.Instant.now(),FubonSettlementOutcome.SETTLEMENT_FAILED);when(orders.latest(owner,TradeReportFeeResolverTest.DATE)).thenReturn(Optional.empty());
   assertThat(service.preview(lateReq(false,null)).status()).isEqualTo("ALREADY_IMPORTED");assertThat(service.apply(request).feeUpdatedCount()).isZero();
   assertThat(jdbc.queryForObject("select canonical_facts from broker_trade_report_import where owner_user_id=?",String.class,owner)).isEqualTo(original);
 }
 @Test void manualZeroThenClearNullPermanentlyBlocksLateFee(){
   long id=applyLate(false).rows().getFirst().transactionId();var original=tx.findById(id).orElseThrow();
   var zero=new AssetTransactionDto.CreateAssetTransactionRequest(original.getTransactionType(),original.getAssetType(),original.getAssetName(),original.getAssetCode(),original.getMarket(),original.getCurrency(),original.getChannel(),original.getTradeDate(),original.getShares(),original.getPrice(),original.getAmount(),BigDecimal.ZERO,null,original.getExchangeRate(),original.getNotes());
   manual.updateAssetTransaction(id,zero);assertThat(service.preview(lateReq(true,null)).rows().getFirst().reason()).isEqualTo("LEDGER_CHANGED");
   var clear=new AssetTransactionDto.CreateAssetTransactionRequest(original.getTransactionType(),original.getAssetType(),original.getAssetName(),original.getAssetCode(),original.getMarket(),original.getCurrency(),original.getChannel(),original.getTradeDate(),original.getShares(),original.getPrice(),original.getAmount(),null,null,original.getExchangeRate(),original.getNotes());
   manual.updateAssetTransaction(id,clear);seedLateSources();var preview=service.preview(lateReq(true,null));assertThat(preview.status()).isEqualTo("ALREADY_IMPORTED");assertThat(preview.rows().getFirst().feeProvenance().reason()).isEqualTo("MANUAL_FEE_CONTROL");assertThat(preview.rows().getFirst().fee()).isNull();
   assertThat(events.manuallyEdited(owner,events.importEvidenceId(owner,id).orElseThrow())).isTrue();
 }
 @Test void lateFeeSourceDriftRejectsPreview(){applyLate(false);seedLateSources();String hash=service.preview(lateReq(true,null)).previewHash();capture.unavailable(owner,java.time.Instant.now(),FubonSettlementOutcome.SETTLEMENT_FAILED);assertThatThrownBy(()->service.apply(lateReq(true,hash))).hasMessage("PREVIEW_HASH_MISMATCH");}

 @Test void twoConcurrentLateAppliesOnlyEnrichOnce()throws Exception{
   applyLate(false);seedLateSources();String hash=service.preview(lateReq(true,null)).previewHash();
   try(var pool=Executors.newFixedThreadPool(2)){var a=pool.submit(()->service.apply(lateReq(true,hash)));var b=pool.submit(()->service.apply(lateReq(true,hash)));assertThat(a.get(15,TimeUnit.SECONDS).feeUpdatedCount()+b.get(15,TimeUnit.SECONDS).feeUpdatedCount()).isEqualTo(1);}
   assertThat(jdbc.queryForObject("select count(*) from broker_trade_report_fee_event where owner_user_id=? and kind='FEE_ENRICHMENT'",Integer.class,owner)).isEqualTo(1);
 }
 @ParameterizedTest @ValueSource(booleans={true,false}) void manualAndLateEnrichmentSerializeBothOrders(boolean manualFirst)throws Exception{
   long id=applyLate(false).rows().getFirst().transactionId();seedLateSources();String hash=service.preview(lateReq(true,null)).previewHash();var t=tx.findById(id).orElseThrow();
   var put=new AssetTransactionDto.CreateAssetTransactionRequest(t.getTransactionType(),t.getAssetType(),t.getAssetName(),t.getAssetCode(),t.getMarket(),t.getCurrency(),t.getChannel(),t.getTradeDate(),t.getShares(),t.getPrice(),t.getAmount(),null,null,t.getExchangeRate(),t.getNotes());
   CountDownLatch locked=new CountDownLatch(1),release=new CountDownLatch(1),started=new CountDownLatch(1);
   doAnswer(inv->{Object result=inv.callRealMethod();if(Thread.currentThread().getName().equals("first-fee-writer")){locked.countDown();if(!release.await(10,TimeUnit.SECONDS))throw new AssertionError("timeout");}return result;}).when(guard).lock(owner);
   Runnable edit=()->manual.updateAssetTransaction(id,put),enrich=()->service.apply(lateReq(true,hash));
   try(var pool=Executors.newFixedThreadPool(2)){
     var a=pool.submit(()->{Thread.currentThread().setName("first-fee-writer");(manualFirst?edit:enrich).run();});assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
     var b=pool.submit(()->{Thread.currentThread().setName("second-fee-writer");started.countDown();try{(manualFirst?enrich:edit).run();return (Throwable)null;}catch(Throwable failure){return failure;}});assertThat(started.await(5,TimeUnit.SECONDS)).isTrue();
     try{assertThatThrownBy(()->b.get(200,TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);}finally{release.countDown();}a.get(15,TimeUnit.SECONDS);Throwable failure=b.get(15,TimeUnit.SECONDS);
     if(manualFirst)assertThat(failure).hasMessage("PREVIEW_HASH_MISMATCH");else assertThat(failure).isNull();
   }finally{release.countDown();}
   assertThat(tx.findById(id).orElseThrow().getFee()).isNull();assertThat(events.manuallyEdited(owner,events.importEvidenceId(owner,id).orElseThrow())).isTrue();
   assertThat(jdbc.queryForObject("select count(*) from broker_trade_report_fee_event where owner_user_id=? and kind='FEE_ENRICHMENT'",Integer.class,owner)).isEqualTo(manualFirst?0:1);
 }
}
