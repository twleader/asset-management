package com.steven.assets.bff.publicsrpp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.bff.security.BffUser;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Strict transport and immutable receipt validation only; never recomputes investment decisions. */
@Service
class SrppDailyDecisionCapture {
    private final SrppOwnerResolver owners;private final WebClient business;
    SrppDailyDecisionCapture(SrppOwnerResolver owners,@Qualifier("businessServicesClient")WebClient business){this.owners=owners;this.business=business;}
    Mono<ResponseEntity<byte[]>> capture(byte[] bytes){return Mono.defer(()->{
        JsonNode request=parseRequest(bytes);String email=request.has("ownerEmail")?request.get("ownerEmail").textValue():null;
        return owners.resolve(email).flatMap(owner->SrppOwnerResolver.withoutCallerIdentity(business.post().uri("/internal/srpp/daily-decision/evaluate")
            .headers(SrppOwnerResolver.ownerHeaders(owner)).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON,MediaType.APPLICATION_PROBLEM_JSON).bodyValue(bytes)
            .exchangeToMono(r->r.bodyToMono(byte[].class).defaultIfEmpty(new byte[0]).map(body->{MediaType type=r.headers().contentType().orElse(null);
                try{if(r.statusCode().value()==202)require(r.headers().header(HttpHeaders.RETRY_AFTER).equals(List.of("2")));validate(r.statusCode().value(),type,body,request,owner.id());}catch(RuntimeException invalid){throw new SrppCaptureProblemException(SrppCaptureProblemCatalog.UPSTREAM_INVALID,invalid);}
                ResponseEntity.BodyBuilder out=ResponseEntity.status(r.statusCode()).contentType(type).header(HttpHeaders.CACHE_CONTROL,"no-store");
                if(r.statusCode().value()==202)out.header(HttpHeaders.RETRY_AFTER,"2");return out.body(body);}))
            .timeout(Duration.ofSeconds(10)).onErrorMap(e->e instanceof SrppCaptureProblemException?e:new SrppCaptureProblemException(SrppCaptureProblemCatalog.UPSTREAM_INVALID,e))));
    });}
    static JsonNode parseRequest(byte[] bytes){try{JsonNode n=SrppDailyContextResponseValidator.parseStrict(bytes);Set<String> required=Set.of("tradingDate","slot","policyBundleSha256","swaggerSha256");Set<String> actual=fields(n);Set<String> allowed=new HashSet<>(required);allowed.add("ownerEmail");require(actual.containsAll(required)&&allowed.containsAll(actual));
        for(String f:required)text(n.get(f));LocalDate.parse(text(n.get("tradingDate")));require(text(n.get("tradingDate")).matches("\\d{4}-\\d{2}-\\d{2}"));member(n.get("slot"),Set.of("09:05","11:40"));sha(n.get("policyBundleSha256"));sha(n.get("swaggerSha256"));if(n.has("ownerEmail")){String email=text(n.get("ownerEmail"));require(email.length()<=254&&SrppOrchestratedQuery.EMAIL.matcher(email).matches());}return n;
    }catch(RuntimeException invalid){throw new SrppCaptureProblemException(SrppCaptureProblemCatalog.INVALID_REQUEST,invalid);}}
    static void validate(int status,MediaType type,byte[] bytes,JsonNode request,long owner){JsonNode root=SrppDailyContextResponseValidator.parseStrict(bytes);
        if(status!=200&&status!=201&&status!=202){validateProblem(status,type,root);return;}
        require(type!=null&&type.isCompatibleWith(MediaType.APPLICATION_JSON));
        if(status==202){exact(root,"schemaVersion","decisionRunId","status","code","identity");constant(root.get("status"),"CAPTURING");constant(root.get("code"),"DECISION_CAPTURE_IN_PROGRESS");}
        else{exact(root,"schemaVersion","decisionRunId","status","created","authorityRevision","identity","inputSnapshot","decision","decisionContentSha256");constant(root.get("status"),"FINAL");require(root.get("created").isBoolean()&&root.get("created").booleanValue()==(status==201));validateFinal(root);}
        require(root.get("schemaVersion").isIntegralNumber()&&root.get("schemaVersion").intValue()==1);UUID.fromString(text(root.get("decisionRunId")));JsonNode id=root.get("identity");exact(id,"ownerUserId","tradingDate","slot","policyBundleSha256","swaggerSha256");require(id.get("ownerUserId").isIntegralNumber()&&id.get("ownerUserId").longValue()==owner);
        for(String f:List.of("tradingDate","slot","policyBundleSha256","swaggerSha256"))require(id.get(f).equals(request.get(f)));
    }
    private static void validateFinal(JsonNode root){JsonNode ar=root.get("authorityRevision");exact(ar,"serviceBuild","databaseSchema","calculatorVersion","modelInputsSha256","policyManifestSha256");for(String f:List.of("serviceBuild","databaseSchema","calculatorVersion"))text(ar.get(f));constant(ar.get("calculatorVersion"),"SRPP_D130_D195_V1");constant(ar.get("databaseSchema"),"v1.149.0");sha(ar.get("modelInputsSha256"));sha(ar.get("policyManifestSha256"));
        JsonNode snapshot=root.get("inputSnapshot");exact(snapshot,"inputSnapshotSha256","capturedAt","assetSnapshotId","assetGeneratedAt","sourceVector","inputBundleJcs");Instant captured=instant(snapshot.get("capturedAt"));require(!instant(snapshot.get("assetGeneratedAt")).isAfter(captured));require(snapshot.get("assetSnapshotId").isIntegralNumber()&&snapshot.get("assetSnapshotId").longValue()>0);
        String raw=text(snapshot.get("inputBundleJcs"));JsonNode input=SrppDailyContextResponseValidator.parseStrict(raw.getBytes(StandardCharsets.UTF_8));require(raw.equals(SrppJcs.canonicalize(input))&&hash(input).equals(text(snapshot.get("inputSnapshotSha256"))));sha(snapshot.get("inputSnapshotSha256"));
        require(input.path("ownerUserId").equals(root.path("identity").path("ownerUserId"))&&input.path("tradingDate").equals(root.path("identity").path("tradingDate"))&&input.path("slot").equals(root.path("identity").path("slot"))&&input.path("capturedAt").equals(snapshot.get("capturedAt")));
        JsonNode sources=input.get("sources"),vector=snapshot.get("sourceVector");require(fields(sources).equals(fields(vector)));for(String key:fields(vector)){JsonNode v=vector.get(key);exact(v,"sourceId","revision","capturedAt","sha256","coverage","validation");constant(v.get("sourceId"),key);text(v.get("revision"));require(!instant(v.get("capturedAt")).isAfter(captured));sha(v.get("sha256"));require(hash(sources.get(key)).equals(text(v.get("sha256"))));member(v.get("coverage"),Set.of("COMPLETE","PARTIAL","NONE"));member(v.get("validation"),Set.of("VERIFIED","UNAVAILABLE","AVAILABLE","STALE","CONFLICT"));}
        JsonNode d=root.get("decision");exact(d,"executionScope","tradeAuthorization","placesOrders","eventEvidenceIntegrationStatus","eventEvidenceReason","ranking","candidates");constant(d.get("executionScope"),"REPORT_RECOMMENDATION_ONLY");falseFlag(d.get("tradeAuthorization"));falseFlag(d.get("placesOrders"));constant(d.get("eventEvidenceIntegrationStatus"),"NOT_BOUND");constant(d.get("eventEvidenceReason"),"PER_CONSUMER_EVIDENCE");Set<String> codes=Set.of("00865B","00719B","00697B");require(d.get("ranking").isArray()&&d.get("ranking").size()<=3);Set<String> rank=new HashSet<>();for(JsonNode code:d.get("ranking")){member(code,codes);require(rank.add(text(code)));}
        JsonNode cs=d.get("candidates");require(cs.isArray()&&cs.size()==4);Set<String> legs=new HashSet<>();int strategyLots=0;for(JsonNode c:cs){exact(c,"symbol","strategy","status","action","lots","limitPrice","amountTwd","blockingReasons","receipts");member(c.get("symbol"),codes);member(c.get("strategy"),Set.of("STRATEGIC_FUBON","EMERGENCY_CATHAY"));String code=text(c.get("symbol")),strategy=text(c.get("strategy"));require(legs.add(strategy+":"+code));if(strategy.equals("EMERGENCY_CATHAY"))require(code.equals("00865B"));require(c.get("lots").isIntegralNumber());int lots=c.get("lots").intValue();require(lots>=0&&lots<=2);member(c.get("status"),Set.of("ACTIONABLE_FOR_REPORT","BLOCKED"));member(c.get("action"),Set.of("BUY","NONE"));List<String> reasons=sortedStrings(c.get("blockingReasons"));require(c.get("receipts").isArray()&&!c.get("receipts").isEmpty());Set<String> gateIds=new HashSet<>();boolean blocked=false;for(JsonNode r:c.get("receipts")){exact(r,"ruleId","calculatorVersion","inputRefs","status","reason","values");require(gateIds.add(text(r.get("ruleId"))));require(r.get("calculatorVersion").equals(ar.get("calculatorVersion")));member(r.get("status"),Set.of("PASS","BLOCK","UNAVAILABLE","NOT_APPLICABLE"));String reason=text(r.get("reason"));require(r.get("inputRefs").isArray()&&!r.get("inputRefs").isEmpty());Set<String> actualRefs=new HashSet<>();r.get("inputRefs").forEach(v->require(actualRefs.add(text(v))));require(actualRefs.equals(expectedRefs(text(r.get("ruleId")),code)));for(JsonNode ref:r.get("inputRefs")){String key=text(ref);require(vector.has(key));if("PASS".equals(text(r.get("status")))&&Set.of("POSITION","PENDING","SUBACCOUNTS","RADAR","VALUATION","TRADABILITY","FX","BEAR_WINDOW","DEDUP","QUOTE","BOOK").contains(text(r.get("ruleId")))||"PASS".equals(text(r.get("status")))&&"D-195".equals(text(r.get("ruleId")))&&!key.startsWith("quote:")){constant(vector.get(key).get("validation"),"VERIFIED");constant(vector.get(key).get("coverage"),"COMPLETE");}}for(String k:fields(r.get("values")))text(r.get("values").get(k));if(reasons.contains(reason))blocked=true;boolean hard="BLOCK".equals(text(r.get("status")))||"UNAVAILABLE".equals(text(r.get("status")))&&!Set.of("TRADABILITY","BEAR_WINDOW").contains(text(r.get("ruleId")));if(hard)require(reasons.contains(reason));if("INTRADAY".equals(text(r.get("ruleId")))){constant(r.get("status"),"NOT_APPLICABLE");constant(r.get("reason"),"HORIZON_NOT_APPLICABLE");constant(r.get("values").get("scope"),"SHORT_ONLY");}}
            Set<String> mandatory=new HashSet<>(Set.of("POSITION","PENDING","RADAR","RADAR_DIRECTION","VALUATION","TRADABILITY","FX","FX_CAP","BEAR_WINDOW","DEDUP","QUOTE","BOOK","D-195","FUNDS_FLOOR","INTRADAY"));if(code.equals("00865B"))mandatory.add("SUBACCOUNTS");if(strategy.equals("STRATEGIC_FUBON"))mandatory.add("TARGET");else mandatory.add("EMERGENCY_CAPACITY");require(gateIds.containsAll(mandatory));for(String reason:reasons){boolean found=false;for(JsonNode r:c.get("receipts"))if(reason.equals(r.path("reason").asText())&&Set.of("BLOCK","UNAVAILABLE").contains(r.path("status").asText()))found=true;require(found);}
            if(lots>0){String capacityRule=strategy.equals("STRATEGIC_FUBON")?"D-130_CAPACITY":"EMERGENCY_CAPACITY";require(gateIds.contains(capacityRule));for(JsonNode receipt:c.get("receipts"))if(capacityRule.equals(receipt.path("ruleId").asText())){constant(receipt.get("status"),"PASS");constant(receipt.get("values").get("lots"),Integer.toString(lots));}constant(c.get("status"),"ACTIONABLE_FOR_REPORT");constant(c.get("action"),"BUY");require(reasons.isEmpty()&&!blocked);BigDecimal price=decimal(c.get("limitPrice")),amount=decimal(c.get("amountTwd"));require(price.signum()>0&&amount.compareTo(price.multiply(BigDecimal.valueOf(lots*1000L)))==0);if(strategy.equals("STRATEGIC_FUBON")){strategyLots+=lots;require(!d.get("ranking").isEmpty()&&code.equals(text(d.get("ranking").get(0))));}}
            else{constant(c.get("status"),"BLOCKED");constant(c.get("action"),"NONE");require(c.get("limitPrice").isNull()&&c.get("amountTwd").isNull()&&!reasons.isEmpty()&&blocked);}}
        require(strategyLots<=2);ObjectNode content=((ObjectNode)root).deepCopy();content.remove("created");content.remove("decisionContentSha256");sha(root.get("decisionContentSha256"));require(hash(content).equals(text(root.get("decisionContentSha256"))));
    }
    private static void validateProblem(int status,MediaType type,JsonNode n){require(type!=null&&type.isCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));exact(n,"type","title","status","detail","instance","code","retryable");constant(n.get("type"),"about:blank");constant(n.get("instance"),SrppCaptureController.DECISION);require(n.get("status").isIntegralNumber()&&n.get("status").intValue()==status);String code=text(n.get("code"));Problem p=PROBLEMS.get(code);require(p!=null&&p.status()==status);constant(n.get("title"),p.title());constant(n.get("detail"),p.detail());require(n.get("retryable").isBoolean()&&n.get("retryable").booleanValue()==p.retry());}
    private record Problem(int status,String title,String detail,boolean retry){}
    private static final Map<String,Problem> PROBLEMS=new HashMap<>();
    static {
        PROBLEMS.put("RUN_METADATA_MISMATCH",new Problem(409,"SRPP decision run metadata mismatch","同一決策識別已使用不同的規則包或 Swagger 雜湊，不會覆寫。",false));
        PROBLEMS.put("INVALID_REQUEST",new Problem(400,"Invalid SRPP capture request","請求本文不合法，請確認欄位、格式與內容後再試。",false));
        PROBLEMS.put("UNSUPPORTED_MEDIA_TYPE",new Problem(415,"Unsupported SRPP capture media type","請求的 Content-Type 必須是 application/json。",false));
        PROBLEMS.put("NON_TRADING_DAY",new Problem(409,"Not a Taiwan stock trading day","指定日期不是台股交易日。",false));
        PROBLEMS.put("POLICY_UNSUPPORTED",new Problem(409,"SRPP policy bundle is not supported","指定的規則包尚未登錄或未通過驗證。",false));
        PROBLEMS.put("SWAGGER_MISMATCH",new Problem(409,"SRPP Swagger identity mismatch","請求的 Swagger 雜湊與已發布的 9090 Swagger 文件不一致，請同步文件後再試。",false));
        PROBLEMS.put("OWNER_UNAVAILABLE",new Problem(503,"SRPP owner unavailable","無法確認資料擁有者，指定帳號不可用。",false));
        PROBLEMS.put("CALENDAR_UNAVAILABLE",new Problem(503,"Taiwan trading calendar unavailable","台股交易日曆暫時無法確認，請稍後再試。",true));
        PROBLEMS.put("CONTEXT_NOT_READY",new Problem(503,"SRPP context not ready","本時段的計算脈絡尚未就緒，請稍後再試。",true));
        PROBLEMS.put("UPSTREAM_INVALID",new Problem(502,"SRPP upstream response invalid","上游回應格式不合法。",false));
        PROBLEMS.put("INTERNAL_ERROR",new Problem(500,"SRPP internal error","伺服器發生未預期錯誤。",false));
    }
    private static Set<String> expectedRefs(String rule,String code){return switch(rule){
        case "POSITION","SOURCE" -> Set.of("assets");case "PENDING" -> Set.of("pending:"+code);case "SUBACCOUNTS" -> Set.of("subaccounts");
        case "RADAR","RADAR_DIRECTION" -> Set.of("radar:"+code);case "VALUATION" -> Set.of("assets","classifications");case "TRADABILITY" -> Set.of("tradability:"+code);
        case "FX" -> Set.of("fx");case "FX_CAP" -> Set.of("fx","policy");case "BEAR_WINDOW" -> Set.of("bear-window");case "DEDUP" -> Set.of("ledger","reservations");
        case "QUOTE" -> Set.of("quote:"+code);case "BOOK" -> Set.of("book:"+code);case "D-195" -> Set.of("quote:"+code,"daily:"+code,"dividends:"+code,"calendar","policy");
        case "TARGET","FUNDS_FLOOR" -> Set.of("assets","policy");case "INTRADAY" -> Set.of("minute:"+code);
        case "D-130_CAPACITY" -> Set.of("assets","book:"+code,"ledger","reservations","policy");case "D-195_SELECTION" -> Set.of("policy","quote:"+code);
        case "EMERGENCY_CAPACITY" -> Set.of("assets","subaccounts","reservations","policy","book:00865B");default -> throw new SrppPayloadException("Unknown decision gate");};}
    private static List<String> sortedStrings(JsonNode n){require(n!=null&&n.isArray());List<String> list=new ArrayList<>();for(JsonNode v:n)list.add(text(v));List<String> sorted=list.stream().distinct().sorted().toList();require(list.equals(sorted));return list;}
    private static BigDecimal decimal(JsonNode n){String s=text(n);require(s.matches("(?:0|[1-9]\\d*)(?:\\.\\d*[1-9])?"));return new BigDecimal(s);}
    private static String hash(JsonNode n){return SrppJcs.sha256Hex(SrppJcs.canonicalize(n));}
    private static Instant instant(JsonNode n){String s=text(n);require(s.endsWith("Z"));return Instant.parse(s);}
    private static Set<String> fields(JsonNode n){require(n!=null&&n.isObject());Set<String> f=new HashSet<>();n.fieldNames().forEachRemaining(f::add);return f;}
    private static void exact(JsonNode n,String...expected){require(fields(n).equals(Set.of(expected)));}
    private static String text(JsonNode n){require(n!=null&&n.isTextual()&&!n.textValue().isBlank());return n.textValue();}
    private static void constant(JsonNode n,String value){require(value.equals(text(n)));}
    private static void member(JsonNode n,Set<String> values){require(values.contains(text(n)));}
    private static void sha(JsonNode n){require(text(n).matches("[0-9a-f]{64}"));}
    private static void falseFlag(JsonNode n){require(n!=null&&n.isBoolean()&&!n.booleanValue());}
    private static void require(boolean valid){if(!valid)throw new SrppPayloadException("SRPP immutable decision receipt invalid");}
}
