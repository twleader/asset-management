package com.steven.assets.service;

import com.steven.assets.model.CanonicalDividend.*;
import com.steven.assets.model.CanonicalDividendResolver;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;

@Service
public class CanonicalDividendReconciliationService {
    public record Preview(String code,String market,String planToken,boolean noOp,List<Day> diff,
                          int currentRows,int targetDays,String note) {}
    public record Applied(boolean alreadyApplied,int changedRows,Preview preview) {}
    public static class StalePlanException extends RuntimeException { public StalePlanException(){super("配息修復計畫已變更，請重新取得預覽");} }
    private final CanonicalDividendRepository repository;
    private final Clock clock;
    private DividendCashEnrichmentService enrichment;
    private final byte[] secret=new byte[32];
    @Autowired public CanonicalDividendReconciliationService(CanonicalDividendRepository repository,DividendCashEnrichmentService enrichment){this(repository,Clock.systemUTC());this.enrichment=enrichment;}
    public CanonicalDividendReconciliationService(CanonicalDividendRepository repository){this(repository,Clock.systemUTC());}
    CanonicalDividendReconciliationService(CanonicalDividendRepository repository,Clock clock){this.repository=repository;this.clock=clock;new SecureRandom().nextBytes(secret);}
    @Transactional(readOnly=true) public Preview preview(String code,String market){
        Key key=validate(code,market);Instant now=clock.instant();return plan(repository.load(key,now),now);
    }
    @Transactional(timeout=15) public Applied apply(String code,String market,String token){
        Key key=validate(code,market);verify(token);repository.lock(key);Instant now=clock.instant();Facts facts=repository.load(key,now);
        Preview next=plan(facts,now);
        if(!MessageDigest.isEqual(next.planToken().getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8))){
            String[] supplied=token.split("\\.");String[] actual=next.planToken().split("\\.");
            if(next.noOp()&&supplied[1].equals(actual[1])&&supplied[3].equals(actual[3]))return new Applied(true,0,next);
            throw new StalePlanException();
        }
        if(next.noOp())return new Applied(true,0,next);
        int changes=repository.apply(facts,next.diff());
        if(changes>0&&enrichment!=null&&org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
                @Override public void afterCommit(){try{enrichment.enrich(code,market,now);}catch(RuntimeException ex){org.slf4j.LoggerFactory.getLogger(CanonicalDividendReconciliationService.class).warn("配息基礎資料已完成投影；本機價格資訊尚待補齊：{}",ex.getClass().getSimpleName());}}
            });
        return new Applied(false,changes,next);
    }
    private Preview plan(Facts facts,Instant now){
        LocalDate today=now.atZone(ZoneId.of("Asia/Taipei")).toLocalDate();
        List<Day> target=CanonicalDividendResolver.resolve(facts,now,today,false).days();
        String evidence=CanonicalDividendResolver.sha256(facts.key()+"|"+facts.evidence());
        String before=CanonicalDividendResolver.sha256(facts.current().toString());
        // Pure target contains only selected facts; revision ids are bound independently by evidenceDigest.
        var preserved=facts.current().stream().filter(row->row.canonicalDate()==null)
                .map(row->row.id()+"|"+row.year()+"|"+row.cashDate()+"|"+row.stockDate()+"|"+row.cash()+"|"+row.stock()+"|"+row.cashPayment()+"|"+row.stockPayment()+"|"+row.source()+"|"+row.eventKey()+"|"+("CANCELLED".equals(row.status())?"CANCELLED":"PRESERVED"))
                .sorted().toList();
        String desired=CanonicalDividendResolver.sha256(facts.key()+"|"+target+"|"+preserved);
        String body="v2."+evidence+"."+before+"."+desired;
        return new Preview(facts.key().code(),facts.key().market(),body+"."+sign(body),repository.matches(facts,target),target,
                facts.current().size(),target.size(),"只採用可驗原始日期組；來源未提供明示撤回時不由缺席或零值推定撤回");
    }
    private void verify(String token){
        if(token==null||!token.matches("v2(\\.[0-9a-f]{64}){4}"))throw new StalePlanException();
        String body=token.substring(0,token.lastIndexOf('.'));
        if(!MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.UTF_8),token.substring(token.lastIndexOf('.')+1).getBytes(StandardCharsets.UTF_8)))throw new StalePlanException();
    }
    private String sign(String value){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(secret,"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static Key validate(String code,String market){if(code==null||code.isBlank()||code.length()>20||market==null||market.isBlank()||market.length()>20)throw new IllegalArgumentException("股票與市場不得為空白或超過長度限制");return new Key(code,market);}
}
