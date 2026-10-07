package com.steven.assets.service.srpp;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.steven.assets.model.SrppDailyReportMail;
import com.steven.assets.repository.SrppDailyReportMailRepository;
import com.steven.assets.srpp.SrppJcs;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.function.Supplier;

/** 先驗證／render，才建立 lease；SMTP 前後以 CAS 防止不確定結果被重寄。 */
@Service @RequiredArgsConstructor
public class DailyReportMailService {
    private static final ObjectMapper STRICT_JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final SrppDailyReportMailRepository repository; private final DailyReportMailSender sender; private final DailyReportMailSettings settings; private final PlatformTransactionManager transactionManager;
    public record Response(String status,String messageId,Instant sentAt,String from,List<String> to,String subject,String htmlSha256,String textSha256,boolean idempotentReplay) {}

    public Response submit(String rawJson) {
        JsonNode root;
        try { root=STRICT_JSON.readTree(rawJson); if(root==null) throw new IllegalArgumentException("empty"); }
        catch(Exception e) { throw new DailyReportMailProblem(HttpStatus.BAD_REQUEST, e.getMessage()!=null&&e.getMessage().contains("Duplicate field")?"DUPLICATE_JSON_MEMBER":"INVALID_JSON"); }
        if(!root.isObject()) throw new DailyReportMailProblem(HttpStatus.BAD_REQUEST,"INVALID_JSON");
        DailyReportRenderer.Rendered rendered=DailyReportRenderer.validateAndRender(root);
        String requestHash;
        try { requestHash=SrppJcs.hash(root); } catch(IllegalArgumentException e) { throw new DailyReportMailProblem(HttpStatus.BAD_REQUEST,"INVALID_JSON"); }
        String expectedHtml=root.path("expectedHtmlSha256").asText(), expectedText=root.path("expectedTextSha256").asText();
        if(!rendered.htmlSha256().equals(expectedHtml)||!rendered.textSha256().equals(expectedText)) throw new DailyReportMailProblem(HttpStatus.CONFLICT,"RENDER_HASH_MISMATCH",Map.of("expectedHtmlSha256",expectedHtml,"actualHtmlSha256",rendered.htmlSha256(),"expectedTextSha256",expectedText,"actualTextSha256",rendered.textSha256()));
        DailyReportMailSettings.Settings mailSettings=settings.requireEnabled();
        String key=root.path("idempotencyKey").asText(); Lease lease=acquire(key,requestHash);
        if(lease.replay()!=null) return lease.replay();
        if(beginSubmitting(key,lease.id())!=1) throw new DailyReportMailProblem(HttpStatus.CONFLICT,"IN_PROGRESS");
        try {
            String id=sender.send(mailSettings.from(),mailSettings.to(),rendered.subject(),rendered.text(),rendered.html());
            if(markSent(key,lease.id(),id,mailSettings,rendered)!=1) throw new DailyReportMailProblem(HttpStatus.CONFLICT,"OUTCOME_UNKNOWN");
            return new Response("SENT",id,now(),mailSettings.from(),List.of(mailSettings.to()),rendered.subject(),rendered.htmlSha256(),rendered.textSha256(),false);
        } catch (DailyReportMailProblem e) { markUnknown(key,lease.id()); throw e; }
        catch (RuntimeException e) { markUnknown(key,lease.id()); throw new DailyReportMailProblem(HttpStatus.BAD_GATEWAY,"MAIL_SEND_FAILED"); }
    }
    public Response get(String key) {
        if(key==null||!key.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,199}")) throw new DailyReportMailProblem(HttpStatus.BAD_REQUEST,"INVALID_IDEMPOTENCY_KEY");
        SrppDailyReportMail row=repository.findById(key).orElseThrow(()->new DailyReportMailProblem(HttpStatus.NOT_FOUND,"NOT_FOUND"));
        if(row.state==SrppDailyReportMail.State.SUBMITTING && expired(row)) { expireSubmitting(key); row=repository.findById(key).orElse(row); }
        if(row.state==SrppDailyReportMail.State.SENT) return response(row,true);
        if(row.state==SrppDailyReportMail.State.OUTCOME_UNKNOWN) throw new DailyReportMailProblem(HttpStatus.CONFLICT,"OUTCOME_UNKNOWN");
        throw new DailyReportMailProblem(HttpStatus.CONFLICT,"IN_PROGRESS");
    }
    private Lease acquire(String key,String hash){return inTx(()->acquireTx(key,hash));}
    private Lease acquireTx(String key,String hash){ Instant now=now(),until=now.plusSeconds(90); UUID lease=UUID.randomUUID(); SrppDailyReportMail row=repository.findById(key).orElse(null);if(row==null){SrppDailyReportMail n=new SrppDailyReportMail();n.idempotencyKey=key;n.requestSha256=hash;n.state=SrppDailyReportMail.State.PROCESSING;n.leaseId=lease;n.leaseExpiresAt=until;n.createdAt=now;n.updatedAt=now;try{repository.saveAndFlush(n);return new Lease(lease,null);}catch(DataIntegrityViolationException e){row=repository.findById(key).orElseThrow(()->e);}}if(!hash.equals(row.requestSha256))throw new DailyReportMailProblem(HttpStatus.CONFLICT,"IDEMPOTENCY_KEY_CONFLICT");if(row.state==SrppDailyReportMail.State.SENT)return new Lease(null,response(row,true));if(row.state==SrppDailyReportMail.State.OUTCOME_UNKNOWN)throw new DailyReportMailProblem(HttpStatus.CONFLICT,"OUTCOME_UNKNOWN");if(row.state==SrppDailyReportMail.State.FAILED){if(repository.retryFailed(key,lease,until,now)==1)return new Lease(lease,null);return reacquire(key,hash);}if(row.state==SrppDailyReportMail.State.PROCESSING&&expired(row)){if(repository.reclaimProcessing(key,lease,until,now)==1)return new Lease(lease,null);return reacquire(key,hash);}if(row.state==SrppDailyReportMail.State.SUBMITTING&&expired(row))repository.expireSubmitting(key,now);throw new DailyReportMailProblem(HttpStatus.CONFLICT,row.state==SrppDailyReportMail.State.SUBMITTING&&expired(row)?"OUTCOME_UNKNOWN":"IN_PROGRESS"); }
    private Lease reacquire(String key,String hash){SrppDailyReportMail current=repository.findById(key).orElseThrow();if(!hash.equals(current.requestSha256))throw new DailyReportMailProblem(HttpStatus.CONFLICT,"IDEMPOTENCY_KEY_CONFLICT");if(current.state==SrppDailyReportMail.State.SENT)return new Lease(null,response(current,true));if(current.state==SrppDailyReportMail.State.OUTCOME_UNKNOWN)throw new DailyReportMailProblem(HttpStatus.CONFLICT,"OUTCOME_UNKNOWN");throw new DailyReportMailProblem(HttpStatus.CONFLICT,"IN_PROGRESS");}
    private int beginSubmitting(String key,UUID lease){return inTx(()->repository.beginSubmitting(key,lease,now()));} private int markSent(String key,UUID lease,String id,DailyReportMailSettings.Settings s,DailyReportRenderer.Rendered r){return inTx(()->repository.markSent(key,lease,id,now(),s.from(),s.to(),r.subject(),r.htmlSha256(),r.textSha256()));} private void markUnknown(String key,UUID lease){inTx(()->{repository.markUnknown(key,lease,now());return null;});} private void expireSubmitting(String key){inTx(()->{repository.expireSubmitting(key,now());return null;});}
    private <T>T inTx(Supplier<T> work){return new TransactionTemplate(transactionManager).execute(s->work.get());} private static boolean expired(SrppDailyReportMail r){return r.leaseExpiresAt!=null&&r.leaseExpiresAt.isBefore(now());} private static Instant now(){return Instant.now();} private static Response response(SrppDailyReportMail m,boolean replay){return new Response("SENT",m.messageId,m.sentAt,m.fromAddress,List.of(m.toAddress),m.subject,m.htmlSha256,m.textSha256,replay);} private record Lease(UUID id,Response replay){}
}
