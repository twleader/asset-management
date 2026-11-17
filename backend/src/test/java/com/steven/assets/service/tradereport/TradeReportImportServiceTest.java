package com.steven.assets.service.tradereport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.model.*;
import com.steven.assets.service.fubon.*;
import com.steven.assets.repository.*;
import com.steven.assets.security.TenantGuard;
import com.steven.assets.service.StockMasterService;
import org.junit.jupiter.api.*;
import org.mockito.*;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class TradeReportImportServiceTest {
 TradeReportStore store=mock(TradeReportStore.class); AssetTransactionRepository tx=mock(AssetTransactionRepository.class);
 AppUserRepository users=mock(AppUserRepository.class);BrokerRepository brokers=mock(BrokerRepository.class);
 StockMasterService stocks=mock(StockMasterService.class);TenantGuard tenant=mock(TenantGuard.class);TradeReportLedgerGuard guard=mock(TradeReportLedgerGuard.class);
 FubonSettlementReceiptStore settlements=mock(FubonSettlementReceiptStore.class); FubonSrppSourceStore orders=mock(FubonSrppSourceStore.class);
 TradeReportImportService service;
 @BeforeEach void setup(){service=new TradeReportImportService(new TradeReportParser(),store,tx,users,brokers,stocks,tenant,guard,new ObjectMapper().findAndRegisterModules(),settlements,orders);
 when(tenant.hasUser()).thenReturn(true);when(tenant.requireCurrentUserId()).thenReturn(1L);when(users.findById(1L)).thenReturn(Optional.of(AppUser.builder().id(1L).status("ACTIVE").build()));
 when(brokers.findByCode("fubon")).thenReturn(Optional.of(BrokerEntity.builder().id(1L).code("fubon").active(true).build()));when(stocks.resolveNameLocalOnly("0050","台股")).thenReturn("測試ETF");
 when(store.receipt(any(),any())).thenReturn(Optional.empty());when(store.evidence(any(),any())).thenReturn(Optional.empty());when(tx.findReportCandidates(any(),any(),any(),any(),any())).thenReturn(List.of());
 }
 TradeReportImportService.Request request(String hash){return new TradeReportImportService.Request("synthetic-message",TradeReportParserTest.mime(TradeReportParserTest.html(TradeReportParserTest.row("0050","a1"))),true,true,hash);}
 @Test void previewDoesNotWriteAndUnknownFeesRemainNull(){var r=service.preview(request(null));assertThat(r.status()).isEqualTo("READY");assertThat(r.rows().getFirst().fee()).isNull();verify(store,never()).addReceipt(any(),any(),any());verify(tx,never()).saveAndFlush(any());verify(guard,never()).lock(any());}
 @Test void applyRevalidatesHashBeforeWrite(){assertThatThrownBy(()->service.apply(request("bad"))).isInstanceOf(TradeReportProblem.class).hasMessage("PREVIEW_HASH_MISMATCH");verify(tx,never()).saveAndFlush(any());}
 @Test void noAuthAndInactiveRejected(){when(tenant.hasUser()).thenReturn(false);assertThatThrownBy(()->service.preview(request(null))).hasMessage("AUTHENTICATION_REQUIRED");when(tenant.hasUser()).thenReturn(true);when(users.findById(1L)).thenReturn(Optional.empty());assertThatThrownBy(()->service.preview(request(null))).hasMessage("OWNER_UNAVAILABLE");}
 @Test void messageHashAndManualConflict(){when(store.receipt(any(),any())).thenReturn(Optional.of(new TradeReportStore.Receipt(1,"wrong")));assertThat(service.preview(request(null)).rows().getFirst().reason()).isEqualTo("MESSAGE_CONTENT_CHANGED");when(store.receipt(any(),any())).thenReturn(Optional.empty());when(tx.findReportCandidates(any(),any(),any(),any(),any())).thenReturn(List.of(AssetTransaction.builder().id(8L).build()));var r=service.preview(request(null));assertThat(r.rows().getFirst().reason()).isEqualTo("POSSIBLE_DUPLICATE");assertThatThrownBy(()->service.apply(request(r.previewHash()))).isInstanceOf(TradeReportImportService.Conflict.class);verify(tx,never()).saveAndFlush(any());}
 @Test void insertOnlyLedgerAndSource(){when(tx.saveAndFlush(any())).thenAnswer(i->{AssetTransaction t=i.getArgument(0);t.setId(9L);return t;});when(store.addEvidence(any(),any(),any(),any(),any())).thenReturn(7L);when(store.addReceipt(any(),any(),any())).thenReturn(6L);
 var result=service.apply(request(service.preview(request(null)).previewHash()));assertThat(result.insertedCount()).isEqualTo(1);ArgumentCaptor<AssetTransaction> captor=ArgumentCaptor.forClass(AssetTransaction.class);verify(tx).saveAndFlush(captor.capture());assertThat(captor.getValue().getSource()).isEqualTo("FUBON_EMAIL");assertThat(captor.getValue().getBrokerFilledNo()).isNull();assertThat(captor.getValue().getFee()).isNull();verify(store).link(6,7);}
 @Test void latestFailedAttemptInvalidatesPreviewWithoutWriting(){
   String hash=service.preview(request(null)).previewHash();
   var payload=new ObjectMapper().createObjectNode().put("reason","SYNTHETIC_FAILURE");
   when(settlements.latest(1L)).thenReturn(Optional.of(new FubonSettlementReceiptStore.Receipt(5L,1L,TradeReportParserTest.DAY,java.time.Instant.now(),"UNAVAILABLE",null,"synthetic-hash",payload,"SETTLEMENT_UNAVAILABLE")));
   assertThatThrownBy(()->service.apply(request(hash))).hasMessage("PREVIEW_HASH_MISMATCH");verify(tx,never()).saveAndFlush(any());
 }
 @Test void legacyReplayUsesRecordedFeesAndDoesNotReadNewSources()throws Exception{
   var mapper=new ObjectMapper().findAndRegisterModules();
   when(tx.saveAndFlush(any())).thenAnswer(i->{AssetTransaction t=i.getArgument(0);t.setId(9L);return t;});
   when(store.addEvidence(any(),any(),any(),any(),any())).thenReturn(7L);when(store.addReceipt(any(),any(),any())).thenReturn(6L);
   service.apply(request(service.preview(request(null)).previewHash()));
   ArgumentCaptor<String> hash=ArgumentCaptor.forClass(String.class),facts=ArgumentCaptor.forClass(String.class);verify(store).addEvidence(eq(1L),anyString(),hash.capture(),facts.capture(),eq(9L));
   ArgumentCaptor<AssetTransaction> ledger=ArgumentCaptor.forClass(AssetTransaction.class);verify(tx).saveAndFlush(ledger.capture());
   String legacy=mapper.writeValueAsString(mapper.readTree(facts.getValue()).path("row"));
   var source=new TradeReportParser().parse(request(null).rawMimeBase64(),TradeReportParserTest.DAY).rows().getFirst();
   var method=TradeReportImportService.class.getDeclaredMethod("sameSourceRow",String.class,TradeReportParser.Row.class);method.setAccessible(true);assertThat(method.invoke(service,legacy,source)).as("legacy=%s source=%s",legacy,source).isEqualTo(true);
   when(store.evidence(any(),any())).thenReturn(Optional.of(new TradeReportStore.Evidence(7,hash.getValue(),legacy,9L)));when(tx.findById(9L)).thenReturn(Optional.of(ledger.getValue()));
   clearInvocations(settlements,orders);
   var replay=service.preview(request(null));assertThat(replay.status()).isEqualTo("ALREADY_IMPORTED");assertThat(replay.rows().getFirst().feeProvenance().status()).isEqualTo("RECORDED");assertThat(replay.rows().getFirst().fee()).isNull();verifyNoInteractions(settlements,orders);
 }
 @Test void derivedFeeSavedWithProvenanceAndReplayNeverRecomputes()throws Exception{
   when(settlements.latest(1L)).thenReturn(Optional.of(TradeReportFeeResolverTest.receipt(TradeReportFeeResolverTest.settlement())));
   when(orders.latest(eq(1L),any())).thenReturn(Optional.of(TradeReportFeeResolverTest.snapshot(TradeReportFeeResolverTest.orders())));
   String raw=TradeReportParserTest.mime(TradeReportParserTest.html(TradeReportParserTest.row("0050","synthetic1")).replace("11 月 17 日","10 月 5 日"));
   var request=new TradeReportImportService.Request("derived",raw,true,true,null);var preview=service.preview(request);
   assertThat(preview.rows().getFirst().fee()).isEqualByComparingTo("20");assertThat(preview.rows().getFirst().transactionTax()).isEqualByComparingTo("0");
   when(tx.saveAndFlush(any())).thenAnswer(i->{AssetTransaction t=i.getArgument(0);t.setId(9L);return t;});when(store.addEvidence(any(),any(),any(),any(),any())).thenReturn(7L);when(store.addReceipt(any(),any(),any())).thenReturn(6L);
   var applied=service.apply(new TradeReportImportService.Request("derived",raw,true,true,preview.previewHash()));
   ArgumentCaptor<AssetTransaction> saved=ArgumentCaptor.forClass(AssetTransaction.class);verify(tx).saveAndFlush(saved.capture());assertThat(saved.getValue().getFee()).isEqualByComparingTo("20");assertThat(saved.getValue().getNotes()).contains("非原始郵件提供");
   ArgumentCaptor<String> hash=ArgumentCaptor.forClass(String.class),facts=ArgumentCaptor.forClass(String.class);verify(store).addEvidence(eq(1L),anyString(),hash.capture(),facts.capture(),eq(9L));
   when(store.evidence(any(),any())).thenReturn(Optional.of(new TradeReportStore.Evidence(7,hash.getValue(),facts.getValue(),9L)));when(tx.findById(9L)).thenReturn(Optional.of(saved.getValue()));clearInvocations(settlements,orders);
   var replay=service.preview(request);assertThat(replay.status()).isEqualTo("ALREADY_IMPORTED");assertThat(replay.rows().getFirst().feeProvenance()).isEqualTo(applied.rows().getFirst().feeProvenance());assertThat(replay.rows().getFirst().fee()).isEqualByComparingTo("20");verifyNoInteractions(settlements,orders);
 }
}
