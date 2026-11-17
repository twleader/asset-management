package com.steven.assets.service.tradereport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.steven.assets.model.*;
import com.steven.assets.dto.TradeReportFeeDto.*;
import com.steven.assets.service.fubon.*;
import com.steven.assets.repository.*;
import com.steven.assets.security.TenantGuard;
import com.steven.assets.service.StockMasterService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class TradeReportImportService {
 public record Request(String sourceMessageId,String rawMimeBase64,Boolean attestedOwnerAccount,Boolean attestedAuthenticReport,String previewHash){}
 public record ResultRow(String stockCode,String stockName,String side,LocalDate tradeDate,BigDecimal shares,BigDecimal price,BigDecimal amount,
   BigDecimal fee,BigDecimal transactionTax,String orderNo,String filledTime,String status,String reason,Long transactionId,FeeProvenance feeProvenance){}
 public record Response(String status,String previewHash,String rawSha256,String parserVersion,String sourceTrust,List<ResultRow> rows,int insertedCount){}
 public static class Conflict extends RuntimeException {private final Response response;public Conflict(Response r){super("IMPORT_CONFLICT");response=r;}public Response response(){return response;}}
 private final TradeReportParser parser;
 private final TradeReportStore store;
 private final AssetTransactionRepository transactions;
 private final AppUserRepository users;
 private final BrokerRepository brokers;
 private final StockMasterService stocks;
 private final TenantGuard tenant;
 private final TradeReportLedgerGuard guard;
 private final ObjectMapper mapper;
 private final FubonSettlementReceiptStore settlementReceipts;
 private final FubonSrppSourceStore officialOrders;
 private final TradeReportFeeResolver feeResolver = new TradeReportFeeResolver();
 private static final String CHANNEL="富邦證券";
 private static final String TRUST="ASSERTED_BY_AUTHENTICATED_IMPORTER";
 @Transactional(readOnly=true)
 public Response preview(Request req){return prepare(req,false).response;}
 @Transactional
 public Response apply(Request req){
   Prepared p=prepare(req,true);
   if(req.previewHash()==null||!req.previewHash().equals(p.response.previewHash()))throw new TradeReportProblem("PREVIEW_HASH_MISMATCH",409);
   if(p.response.status().equals("CONFLICT"))throw new Conflict(p.response);
   int inserted=0;List<ResultRow> result=new ArrayList<>();List<Long> evidenceIds=new ArrayList<>();
   for(int i=0;i<p.parsed.rows().size();i++){
     TradeReportParser.Row r=p.parsed.rows().get(i); ResultRow row=p.response.rows().get(i);
     String identity=identity(p.owner,p.broker,r);TradeReportStore.Evidence existing=store.evidence(p.owner,identity).orElse(null);
     if(existing!=null){evidenceIds.add(existing.id());result.add(row);continue;}
     AssetTransaction tx=AssetTransaction.builder().ownerUserId(p.owner).transactionType(r.side()).assetType("股票")
       .assetName(r.stockName()).assetCode(r.stockCode()).market("台股").currency("TWD").channel(CHANNEL)
       .tradeDate(r.tradeDate()).shares(r.shares()).price(r.price()).amount(r.amount()).fee(row.fee()).transactionTax(row.transactionTax())
       .notes("DERIVED".equals(row.feeProvenance().status())?"富邦成交回報匯入；費稅依官方交割與訂單資料推導（截至 "+row.feeProvenance().asOf()+"），非原始郵件提供":"富邦成交回報匯入；來源與帳戶歸屬由登入匯入者聲明；成交價金不含未知費稅").source("FUBON_EMAIL").build();
     tx=transactions.saveAndFlush(tx);long evidence=store.addEvidence(p.owner,identity,ledgerHash(tx),envelope(r,row),tx.getId());
     evidenceIds.add(evidence);result.add(result(r,"IMPORTED",null,tx.getId(),new Resolution(row.fee(),row.transactionTax(),row.feeProvenance())));inserted++;
   }
   TradeReportStore.Receipt receipt=store.receipt(p.owner,req.sourceMessageId()).orElse(null);
   long receiptId=receipt==null?store.addReceipt(p.owner,req.sourceMessageId(),p.parsed.rawSha256()):receipt.id();
   for(long evidence:evidenceIds)store.link(receiptId,evidence);
   return new Response(inserted==0?"ALREADY_IMPORTED":"IMPORTED",p.response.previewHash(),p.parsed.rawSha256(),TradeReportParser.VERSION,TRUST,List.copyOf(result),inserted);
 }
 private record Prepared(Long owner,Long broker,TradeReportParser.Parsed parsed,Response response){}
 private Prepared prepare(Request req,boolean lock){
   if(!tenant.hasUser()||tenant.requireCurrentUserId()==null)throw new TradeReportProblem("AUTHENTICATION_REQUIRED",403);
   Long owner=tenant.requireCurrentUserId();
   if(lock)guard.lock(owner);else if(users.findById(owner).filter(u->AppUser.STATUS_ACTIVE.equals(u.getStatus())).isEmpty())throw new TradeReportProblem("OWNER_UNAVAILABLE",403);
   if(req==null||req.sourceMessageId()==null||req.sourceMessageId().isBlank()||req.sourceMessageId().length()>200||!req.sourceMessageId().equals(req.sourceMessageId().trim())
       ||req.sourceMessageId().chars().anyMatch(Character::isISOControl)||!Boolean.TRUE.equals(req.attestedOwnerAccount())||!Boolean.TRUE.equals(req.attestedAuthenticReport()))throw new TradeReportProblem("IMPORT_ATTESTATION_REQUIRED",400);
   BrokerEntity broker=brokers.findByCode("fubon").filter(b->Boolean.TRUE.equals(b.getActive())).orElseThrow(()->new TradeReportProblem("BROKER_UNAVAILABLE",400));
   TradeReportParser.Parsed parsed=parser.parse(req.rawMimeBase64(),LocalDate.now(ZoneId.of("Asia/Taipei")));

   boolean messageConflict=store.receipt(owner,req.sourceMessageId()).filter(r->!r.rawSha256().equals(parsed.rawSha256())).isPresent();
   Set<String> seen=new HashSet<>(); List<ResultRow> rows=new ArrayList<>();
   for(TradeReportParser.Row r:parsed.rows()){
     String name=stocks.resolveNameLocalOnly(r.stockCode(),"台股");
     if(name==null||name.equals(r.stockCode())||!name.replaceAll("\\s+","").equals(r.stockName()))throw new TradeReportProblem("STOCK_IDENTITY_UNVERIFIED",400);
     String identity=identity(owner,broker.getId(),r);String reason=messageConflict?"MESSAGE_CONTENT_CHANGED":null;Long txId=null;
     if(!seen.add(identity))reason="DUPLICATE_REPORT_ROW";
     TradeReportStore.Evidence e=store.evidence(owner,identity).orElse(null);
     Resolution fee = null;
     if(e!=null){
       txId=e.transactionId();
       AssetTransaction tx=txId==null?null:transactions.findById(txId).filter(t->owner.equals(t.getOwnerUserId())).orElse(null);
       if(!sameSourceRow(e.canonicalFacts(),r))reason="SOURCE_FACTS_CHANGED";
       else if(tx==null||!e.factsHash().equals(ledgerHash(tx)))reason="LEDGER_CHANGED";
       fee=recordedFee(e.canonicalFacts(),tx);
     }else if(!transactions.findReportCandidates(owner,r.tradeDate(),r.stockCode(),r.side(),CHANNEL).isEmpty()){
       reason="POSSIBLE_DUPLICATE";txId=transactions.findReportCandidates(owner,r.tradeDate(),r.stockCode(),r.side(),CHANNEL).getFirst().getId();
     }
     // Two distinct same-day/code/side rows in a new batch are ambiguous too: no silent merge.
     long similar=parsed.rows().stream().filter(x->x.tradeDate().equals(r.tradeDate())&&x.stockCode().equals(r.stockCode())&&x.side().equals(r.side())).count();
     if(similar>1)reason="POSSIBLE_DUPLICATE";
     if(fee==null) fee=feeResolver.resolve(owner,reason==null?parsed.rows():List.of(),settlementReceipts.latest(owner).orElse(null),officialOrders.latest(owner,r.tradeDate()).orElse(null),Instant.now());
     rows.add(result(r,reason!=null?"CONFLICT":e!=null?"ALREADY_IMPORTED":"NEW",reason,txId,fee));
   }
   String status=rows.stream().anyMatch(r->r.status().equals("CONFLICT"))?"CONFLICT":rows.stream().allMatch(r->r.status().equals("ALREADY_IMPORTED"))?"ALREADY_IMPORTED":"READY";
   String previewHash=hash(owner+"\n"+parsed.rawSha256()+"\n"+req.sourceMessageId()+"\n"+TradeReportParser.VERSION+"\n"+TradeReportFeeResolver.VERSION+"\n"+json(parsed.rows())+"\n"+json(rows.stream().map(r->Arrays.asList(r.fee(),r.transactionTax(),r.feeProvenance())).toList()));
   return new Prepared(owner,broker.getId(),parsed,new Response(status,previewHash,parsed.rawSha256(),TradeReportParser.VERSION,TRUST,List.copyOf(rows),0));
 }
 private ResultRow result(TradeReportParser.Row r,String status,String reason,Long id,Resolution fee){return new ResultRow(r.stockCode(),r.stockName(),r.side(),r.tradeDate(),r.shares(),r.price(),r.amount(),fee.fee(),fee.transactionTax(),r.orderNo(),r.filledTime(),status,reason,id,fee.provenance());}
 private String envelope(TradeReportParser.Row r,ResultRow result){var node=mapper.createObjectNode();node.put("schemaVersion",2);node.set("row",mapper.valueToTree(r));node.set("fee",mapper.valueToTree(result.fee()));node.set("transactionTax",mapper.valueToTree(result.transactionTax()));node.set("feeProvenance",mapper.valueToTree(result.feeProvenance()));return json(node);}
 private boolean sameSourceRow(String evidence,TradeReportParser.Row row){try{var node=mapper.readTree(evidence);if(node.has("schemaVersion")){if(node.path("schemaVersion").asInt()!=2)return false;node=node.path("row");}var old=mapper.treeToValue(node,TradeReportParser.Row.class);return Objects.equals(old.stockCode(),row.stockCode())&&Objects.equals(old.stockName(),row.stockName())&&Objects.equals(old.side(),row.side())&&Objects.equals(old.tradeDate(),row.tradeDate())&&Objects.equals(old.orderNo(),row.orderNo())&&Objects.equals(old.filledTime(),row.filledTime())&&old.shares().compareTo(row.shares())==0&&old.price().compareTo(row.price())==0&&old.amount().compareTo(row.amount())==0;}catch(Exception invalid){return false;}}
 private Resolution recordedFee(String evidence,AssetTransaction tx){try{var node=mapper.readTree(evidence);if(node.path("schemaVersion").asInt()==2){return new Resolution(node.path("fee").isNull()?null:node.path("fee").decimalValue(),node.path("transactionTax").isNull()?null:node.path("transactionTax").decimalValue(),mapper.treeToValue(node.path("feeProvenance"),FeeProvenance.class));}}catch(Exception invalid){throw new TradeReportProblem("STORED_FEE_EVIDENCE_INVALID",409);}
   return new Resolution(tx==null?null:tx.getFee(),tx==null?null:tx.getTransactionTax(),new FeeProvenance("RECORDED","LEGACY_FEE_EVIDENCE_UNAVAILABLE",null,null,null,null,null));}

 private String identity(Long owner,Long broker,TradeReportParser.Row r){return hash(owner+"|"+broker+"|"+r.tradeDate()+"|"+r.orderNo()+"|"+r.filledTime()+"|"+r.stockCode()+"|"+r.side());}
 private String canonical(TradeReportParser.Row r){return json(r);}
 private String ledgerHash(AssetTransaction tx){
   return hash(json(Arrays.asList(tx.getOwnerUserId(),tx.getTransactionType(),tx.getAssetType(),tx.getAssetName(),tx.getAssetCode(),tx.getMarket(),tx.getCurrency(),tx.getChannel(),tx.getTradeDate(),decimal(tx.getShares()),decimal(tx.getPrice()),decimal(tx.getAmount()),decimal(tx.getFee()),decimal(tx.getTransactionTax()),decimal(tx.getExchangeRate()),tx.getSource(),tx.getBrokerFilledNo(),tx.getNotes())));
 }
 private String decimal(BigDecimal d){return d==null?null:d.stripTrailingZeros().toPlainString();}
 private String json(Object v){try{return mapper.writeValueAsString(v);}catch(Exception e){throw new IllegalStateException("CANONICALIZATION_FAILED");}}
 private String hash(String s){return TradeReportParser.hash(s.getBytes(StandardCharsets.UTF_8));}
}
