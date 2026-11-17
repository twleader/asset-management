package com.steven.assets.service.tradereport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.model.*;
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
 TradeReportImportService service;
 @BeforeEach void setup(){service=new TradeReportImportService(new TradeReportParser(),store,tx,users,brokers,stocks,tenant,guard,new ObjectMapper().findAndRegisterModules());
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
}
