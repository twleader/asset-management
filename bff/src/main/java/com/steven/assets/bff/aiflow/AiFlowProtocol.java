package com.steven.assets.bff.aiflow;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.lang.reflect.*;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;

/** typed decode 前逐層核對閉合欄位及 JSON 型別，避免 Jackson coercion 隱藏下游錯誤。 */
final class AiFlowProtocol {
    static final int MAX_BODY=256*1024;
    static final ObjectMapper JSON=new ObjectMapper().registerModule(timeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    static final Set<String> NULLABLE=Set.of("startedAt","endedAt","occurredAt","reportedOutcome","reasonCode","relation","hash","durationMillis","outcomeLabel");
    static final Set<String> OUTCOMES=Set.of("COMPLETED","PARTIAL","INCOMPLETE","STOPPED_L0","STOPPED_CONTRACT","SKIPPED_NON_TRADING_DAY");
    static final Set<String> STATUSES=Set.of("RUNNING","SUCCEEDED","FAILED","STOPPED","SKIPPED","UNKNOWN");
    private static final Pattern TOOL_NAME=Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}");
    private static final Pattern SENSITIVE=Pattern.compile("(?is)(authorization|bearer\\s|token\\s*[:=]|password|secret|api[_-]?key|private[ \\t\\r\\n\\f\\x0B_-]*reasoning|chain[ \\t\\r\\n\\f\\x0B._-]*of[ \\t\\r\\n\\f\\x0B._-]*thought|\\b(?:curl|wget|printenv|export)\\s|\\$\\(|```|<[^>]+>|[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}|[A-Za-z]:\\\\|/Users/|/home/|\\b(?:NT\\$|USD|TWD)\\s*[0-9]|(?:price|amount|balance|holdings|account|成本|金額|持股|庫存|股數|張數|帳號|帳戶)\\s*[:=：]?\\s*[0-9]|[0-9]{6,})");
    private static final Pattern UNSAFE_PUBLIC_CHOICE_TEXT=Pattern.compile("(?is)(https?://|[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}|authorization|bearer|api[_ -]?key|password|secret|\\btoken\\b|\\$\\(|```|<\\s*script|持股|帳號|[0-9][,0-9]*(?:元|美元|股|張)|(?:NT\\s*\\$|US\\s*\\$|\\b(?:USD|TWD|NTD|EUR|JPY)\\b|[$€£¥])\\s*[-+]?\\s*\\d|\\d[\\d,]*(?:\\.\\d+)?\\s*\\b(?:USD|TWD|NTD|EUR|JPY)\\b|(?:\\b(?:price|amount|balance|holdings?|account|acct|position|quantity|qty|cost|shares?|lots?|portfolio|cash|market_value|total_value)\\b|成本|金額|餘額|持倉|部位|股數|張數|單價|帳戶|帳號)\\s*[\"']?\\s*[:=：]|(?:\\b(?:raw[_ -]*(?:command|shell|response|api|mime|reasoning)|command|cmd|shell)\\b)\\s*[\"']?\\s*[:=：]|(?:<[ \\t\\r\\n\\f\\x0B]*/?[ \\t\\r\\n\\f\\x0B]*(?:analysis|think|thinking|reasoning)[ \\t\\r\\n\\f\\x0B]*>|(?:^|\\n)[ \\t\\r\\n\\f\\x0B]*(?:analysis|thinking|reasoning|private[ \\t\\r\\n\\f\\x0B_-]*(?:analysis|reasoning)|chain[ \\t\\r\\n\\f\\x0B._-]*of[ \\t\\r\\n\\f\\x0B._-]*thought|hidden[ \\t\\r\\n\\f\\x0B_-]*reasoning)[ \\t\\r\\n\\f\\x0B]*[:=]|\\b(?:BEGIN|END)[ \\t\\r\\n\\f\\x0B._-]+(?:PRIVATE|CHAIN[ \\t\\r\\n\\f\\x0B._-]*OF[ \\t\\r\\n\\f\\x0B._-]*THOUGHT|REASONING)\\b|(?:私人推理|內部思考|思考鏈)[ \\t\\r\\n\\f\\x0B]*[:=：])|^\\s*(?:[$#]\\s*)?(?:curl|wget|bash|zsh|sh|pwsh|powershell|python(?:3)?|node|cat|echo|printf)\\s+\\S|(?:/Users/|/private/|/tmp/|~/|[A-Z]:\\\\))");
    private static final Pattern PRIVATE_CHOICE_MARKER=Pattern.compile("(?i)(?:private[ \\t\\r\\n\\f\\x0B_-]*reasoning|chain[ \\t\\r\\n\\f\\x0B._-]*of[ \\t\\r\\n\\f\\x0B._-]*thought|(?:^|[,{\\[ \\t\\r\\n\\f\\x0B\"'])?(?:reasoning|analysis|thinking|thoughts?)[ \\t\\r\\n\\f\\x0B]*[\"']?[ \\t\\r\\n\\f\\x0B]*[:=]|<[ \\t\\r\\n\\f\\x0B]*/?[ \\t\\r\\n\\f\\x0B]*(?:analysis|thinking|reasoning)[ \\t\\r\\n\\f\\x0B]*>|(?:BEGIN|END)[ \\t\\r\\n\\f\\x0B._-]+(?:PRIVATE|CHAIN[ \\t\\r\\n\\f\\x0B._-]*OF[ \\t\\r\\n\\f\\x0B._-]*THOUGHT|REASONING)|私人推理|內部思考|思考鏈)");
    /** 僅觀測協定使用此 mapper；合法 offset 原文只解碼，不改寫 ingress bytes。 */
    private static JavaTimeModule timeModule() {
        var module=new JavaTimeModule();
        module.addDeserializer(Instant.class,new JsonDeserializer<Instant>() {
            @Override public Instant deserialize(JsonParser parser,DeserializationContext context) throws IOException {
                try { return OffsetDateTime.parse(parser.getText()).toInstant(); }
                catch(DateTimeException invalid) { throw context.weirdStringException(parser.getText(),Instant.class,"Expected ISO offset timestamp"); }
            }
        });
        return module;
    }
    record Query(LocalDate date,String consumer,String period) {
        static Query parse(MultiValueMap<String,String> values,boolean detail,Clock clock) {
            Set<String> allowed=detail?Set.of("date","consumer"):Set.of("date","consumer","period");
            if(!allowed.containsAll(values.keySet())) throw AiFlowProblem.invalid();
            for(var entry:values.entrySet()) if(entry.getValue().size()!=1 || entry.getValue().getFirst()==null || entry.getValue().getFirst().isBlank()) throw AiFlowProblem.invalid();
            LocalDate date=AiFlowProtocol.date(values.getFirst("date"));
            String consumer=values.getFirst("consumer"),period=values.getFirst("period");
            window(date,clock); member(consumer,Set.of("Codex","Claude"));
            if(period==null) period="ALL";
            member(period,Set.of("ALL","MORNING","NOON","US"));
            return new Query(date,consumer,period);
        }
    }
    static void window(String date,Clock clock) {
        window(date(date),clock);
    }
    private static LocalDate date(String value) {
        try {
            LocalDate day=LocalDate.parse(value);
            if(!day.toString().equals(value)) throw AiFlowProblem.invalid();
            return day;
        } catch(Exception invalid) { throw AiFlowProblem.invalid(); }
    }
    static void window(LocalDate day,Clock clock) {
        try {
            LocalDate today=LocalDate.now(clock.withZone(ZoneId.of("Asia/Taipei")));
            if(day==null || day.isAfter(today)) throw AiFlowProblem.invalid();
            if(day.isBefore(today.minusDays(89))) throw new AiFlowProblem("RETENTION_EXPIRED");
        } catch(AiFlowProblem e) { throw e; } catch(Exception invalid) { throw AiFlowProblem.invalid(); }
    }
    static <T> T decode(byte[] bytes,Class<T> type,boolean input) {
        try {
            JsonNode node=JSON.readTree(bytes); shape(node,type,input); return JSON.treeToValue(node,type);
        } catch(Exception invalid) { throw new AiFlowProblem(input?"INVALID_REQUEST":"UPSTREAM_INVALID"); }
    }
    private static void shape(JsonNode node,Type type,boolean input) {
        if(type instanceof ParameterizedType pt) {
            if(!node.isArray()) throw AiFlowProblem.invalid();
            for(JsonNode child:node) shape(child,pt.getActualTypeArguments()[0],input);
            return;
        }
        Class<?> cl=(Class<?>)type;
        if(cl.isRecord()) {
            if(!node.isObject()) throw AiFlowProblem.invalid();
            Set<String> expected=new HashSet<>();
            for(var field:cl.getRecordComponents()) {
                expected.add(field.getName()); JsonNode child=node.get(field.getName());
                if(child==null && input && cl==AiFlowDtos.Event.class
                    && Set.of("reportedOutcome","toolCall","choiceSet").contains(field.getName())) continue;
                if((cl==AiFlowDtos.ToolCall.class || cl==AiFlowDtos.ChoiceOption.class) && field.getName().equals("sourceTruncated")) {
                    if(child==null) continue;
                    if(!child.isBoolean() || !child.booleanValue()) throw AiFlowProblem.invalid();
                    continue;
                }
                if(!input && cl==AiFlowDtos.Node.class && field.getName().equals("toolCall")) {
                    if(child==null || !child.isNull()) throw AiFlowProblem.invalid();
                    continue;
                }
                if(!input && cl==AiFlowDtos.Node.class && field.getName().equals("choiceSetSummary")) {
                    if(child==null) throw AiFlowProblem.invalid();
                    if(!child.isNull()) shape(child,field.getGenericType(),false);
                    continue;
                }
                if(!input && cl==AiFlowDtos.ToolCallDetail.class && field.getName().equals("toolCall")) {
                    if(child==null) throw AiFlowProblem.invalid();
                    if(!child.isNull()) shape(child,field.getGenericType(),false);
                    continue;
                }
                if(!input && cl==AiFlowDtos.ChoiceSetDetail.class && field.getName().equals("choiceSet")) {
                    if(child==null) throw AiFlowProblem.invalid();
                    if(!child.isNull()) shape(child,field.getGenericType(),false);
                    continue;
                }
                if(child==null) throw AiFlowProblem.invalid();
                if(child.isNull()) { if(!NULLABLE.contains(field.getName()) || field.getType().isPrimitive()) throw AiFlowProblem.invalid(); }
                else shape(child,field.getGenericType(),input);
            }
            node.fieldNames().forEachRemaining(name->{if(!expected.contains(name)) throw AiFlowProblem.invalid();});
        } else if(cl==String.class) { if(!node.isTextual()) throw AiFlowProblem.invalid(); }
        else if(cl==LocalDate.class) { if(!node.isTextual()) throw AiFlowProblem.invalid(); date(node.textValue()); }
        else if(cl==Instant.class) { if(!node.isTextual()) throw AiFlowProblem.invalid(); OffsetDateTime.parse(node.textValue()); }
        else if(cl==int.class) { if(!node.isIntegralNumber() || !node.canConvertToInt()) throw AiFlowProblem.invalid(); }
        else if(cl==long.class || cl==Long.class) { if(!node.isIntegralNumber() || !node.canConvertToLong()) throw AiFlowProblem.invalid(); }
        else if(cl==double.class) { if(!node.isNumber() || !Double.isFinite(node.doubleValue())) throw AiFlowProblem.invalid(); }
        else if(cl==boolean.class) { if(!node.isBoolean()) throw AiFlowProblem.invalid(); }
        else throw AiFlowProblem.invalid();
    }
    static AiFlowDtos.Batch batch(byte[] bytes,Clock clock) {
        if(bytes.length>MAX_BODY) throw new AiFlowProblem("PAYLOAD_TOO_LARGE");
        var b=decode(bytes,AiFlowDtos.Batch.class,true);
        if(b.schemaVersion()!=1 || b.events().isEmpty() || b.events().size()>100) throw AiFlowProblem.invalid();
        metadata(b.run(),clock); Set<String> ids=new HashSet<>();
        for(var e:b.events()) { event(e); if(!ids.add(e.eventId())) throw AiFlowProblem.invalid(); }
        return b;
    }
    private static void metadata(AiFlowDtos.RunMetadata r,Clock clock) {
        uuid(r.runId()); window(r.reportDate(),clock); member(r.consumer(),Set.of("Codex","Claude"));
        member(r.reportSlot(),Set.of("09:05","12:00","11:40","22:00"));
        member(r.sourceKind(),Set.of("CODEX_ROLLOUT","CLAUDE_ARTIFACT","SCHEDULE_EVENTS"));
        if(r.sourceKind().equals("CODEX_ROLLOUT") && !r.consumer().equals("Codex") || r.sourceKind().equals("CLAUDE_ARTIFACT") && !r.consumer().equals("Claude")) throw AiFlowProblem.invalid();
        opaque(r.sourceRunId(),200); text(r.workflowVersion(),100); instant(r.startedAt(),true);
        automation(r.consumer(),r.reportSlot(),r.automationId());
    }
    private static void automation(String consumer,String slot,String id) {
        if(slot.equals("22:00") || "22-00".equals(id)) {
            if(!slot.equals("22:00") || !consumer.equals("Codex") || !"22-00".equals(id)) throw AiFlowProblem.invalid();
            return;
        }
        member(id,consumer.equals("Codex")?Set.of("codex-srpp-09-30","codex-srpp"):Set.of("srpp-0905-claude","srpp-1150-claude"));
    }
    static void event(AiFlowDtos.Event e) {
        uuid(e.eventId()); if(e.sequence()<0 || e.attemptNo()<1) throw AiFlowProblem.invalid();
        opaque(e.stepId(),100); member(e.eventKind(),Set.of("START","END","OBSERVATION","TERMINAL")); member(e.status(),STATUSES);
        instant(e.occurredAt(),true); text(e.title(),100); textAllowEmpty(e.summary(),500); operation(e.operation()); nullableText(e.reasonCode(),100);
        if(e.parentEventIds().size()>16 || new HashSet<>(e.parentEventIds()).size()!=e.parentEventIds().size()) throw AiFlowProblem.invalid();
        e.parentEventIds().forEach(AiFlowProtocol::uuid); relation(e.relation(),true);
        if(e.evidenceRefs().size()>10) throw AiFlowProblem.invalid();
        for(var ref:e.evidenceRefs()) {
            member(ref.role(),Set.of("CALENDAR","POLICY","API_RECEIPT","VALIDATOR","RESULT","TIMING","DRAFT_RECEIPT","MIME_RECEIPT","APP_TERMINAL","TRACE_COVERAGE"));
            opaque(ref.id(),200); if(ref.hash()!=null && !ref.hash().matches("[0-9a-f]{64}")) throw AiFlowProblem.invalid();
        }
        if(e.reportedOutcome()!=null) member(e.reportedOutcome(),OUTCOMES); toolCall(e.toolCall(),e.eventKind());
        choiceSet(e.choiceSet(),e.eventKind(),e.toolCall());
    }
    private static void operation(AiFlowDtos.Operation o) {
        member(o.method(),Set.of("GET","POST","PUT","PATCH","DELETE","LOCAL","TOOL"));
        if(o.method().equals("LOCAL")||o.method().equals("TOOL")) member(o.path(),Set.of("calendar","policy","validation","render","artifact","tool"));
        else {
            text(o.path(),500);
            if(!o.path().matches("/api(?:/[A-Za-z0-9._~{}-]+)+") || o.path().contains("..")) throw AiFlowProblem.invalid();
        }
    }
    static void list(AiFlowDtos.RunList list,Query query,long owner,Clock clock) {
        require(list.date().equals(query.date()) && list.consumer().equals(query.consumer()) && list.period().equals(query.period())
            && list.timezone().equals("Asia/Taipei") && list.retentionDays()==90
            && list.earliestDate().equals(LocalDate.now(clock.withZone(ZoneId.of("Asia/Taipei"))).minusDays(89)));
        Set<String> ids=new HashSet<>();
        for(var r:list.runs()) { run(r,query,owner,clock); require(ids.add(r.runId()));
            require(switch(query.period()) {
                case "ALL" -> true;
                case "MORNING" -> r.reportSlot().equals("09:05");
                case "NOON" -> Set.of("12:00","11:40").contains(r.reportSlot());
                case "US" -> r.reportSlot().equals("22:00");
                default -> false;
            }); }
    }
    static void detail(AiFlowDtos.Detail d,Query q,long owner,String id,Clock clock) {
        run(d.run(),q,owner,clock); require(d.run().runId().equals(id)); require(d.run().eventCount()==d.nodes().size());
        Set<String> nodes=new HashSet<>(),edges=new HashSet<>();
        for(var n:d.nodes()) {
            require(n.toolCall()==null);
            event(new AiFlowDtos.Event(n.eventId(),n.sequence(),n.stepId(),n.eventKind(),n.status(),n.occurredAt(),n.title(),n.summary(),n.operation(),n.reasonCode(),n.attemptNo(),n.parentEventIds(),n.relation(),n.evidenceRefs(),n.reportedOutcome(),null,null));
            choiceSetSummary(n.choiceSetSummary());
            require(nodes.add(n.eventId())); text(n.statusLabel(),100); text(n.label(),100); text(n.categoryLabel(),100); text(n.timeLabel(),100);
            require(n.durationMillis()==null || n.durationMillis()>=0); require(n.color().matches("#[0-9a-fA-F]{6}"));
        }
        for(var n:d.nodes()) for(String parent:n.parentEventIds()) require(nodes.contains(parent));
        for(var e:d.edges()) { opaque(e.id(),200); require(edges.add(e.id()) && nodes.contains(e.source()) && nodes.contains(e.target())); relation(e.relation(),false); text(e.label(),100); require(Math.abs(e.curveness())<=1); }
        long previous=Long.MAX_VALUE; String previousId="";
        for(var s:d.slowSteps()) { require(nodes.contains(s.nodeId())); opaque(s.stepId(),100); text(s.title(),100); operation(s.operation()); instant(s.startedAt(),false); instant(s.endedAt(),false);
            require(s.durationMillis()>=0 && s.durationMillis()<=previous); if(s.durationMillis()==previous) require(previousId.compareTo(s.stepId())<=0);
            require(Duration.between(s.startedAt(),s.endedAt()).toMillis()==s.durationMillis());
            member(s.status(),STATUSES); text(s.statusLabel(),100); previous=s.durationMillis(); previousId=s.stepId();
        }
    }
    static void toolCallDetail(AiFlowDtos.ToolCallDetail detail,Query query,String runId,String eventId,Clock clock) {
        window(query.date(),clock); uuid(runId); uuid(eventId);
        require(detail.runId().equals(runId) && detail.eventId().equals(eventId));
        if(detail.toolCall()!=null) toolCall(detail.toolCall(),"START");
    }
    static void choiceSetDetail(AiFlowDtos.ChoiceSetDetail detail,Query query,String runId,String eventId,Clock clock) {
        window(query.date(),clock); uuid(runId); uuid(eventId);
        require(detail.runId().equals(runId) && detail.eventId().equals(eventId));
        if(detail.choiceSet()!=null) choiceSetFields(detail.choiceSet());
    }
    /** BFF read-side 的第二道防線：完整候選必逐欄等於同一 actual START toolCall。 */
    static void choiceSetMatchesActual(AiFlowDtos.ChoiceSet set,String eventKind,AiFlowDtos.ToolCall actual) {
        choiceSet(set,eventKind,actual);
    }
    /** 一般 Node 只能回摘要；BFF 以完整 detail 重新證明摘要沒有被替換或自行編造。 */
    static void choiceSetSummaryMatches(AiFlowDtos.ChoiceSet set,AiFlowDtos.ChoiceSetSummary summary) {
        choiceSetFields(set); choiceSetSummary(summary);
        require(set.title().equals(summary.title()) && set.selectedOptionId().equals(summary.selectedOptionId())
            && set.options().size()==summary.options().size());
        for(int index=0;index<set.options().size();index++) {
            var option=set.options().get(index); var projected=summary.options().get(index);
            require(option.optionId().equals(projected.optionId()) && option.label().equals(projected.label())
                && option.toolName().equals(projected.toolName())
                && projected.selected()==option.optionId().equals(set.selectedOptionId()));
        }
    }
    private static void run(AiFlowDtos.RunView r,Query q,long owner,Clock clock) {
        metadata(new AiFlowDtos.RunMetadata(r.runId(),r.consumer(),r.reportDate(),r.reportSlot(),r.sourceKind(),r.sourceRunId(),r.automationId(),r.startedAt(),r.workflowVersion()),clock);
        require(r.ownerUserId()==owner && r.consumer().equals(q.consumer()) && r.reportDate().equals(q.date()));
        member(r.executionState(),Set.of("STARTED","ENDED","UNKNOWN")); require(r.verifiedReportOutcome().equals("UNCONFIRMED"));
        member(r.traceCoverage(),Set.of("FULL_OBSERVED","PARTIAL_OBSERVED","SELF_REPORTED","RAW_TRACE_UNAVAILABLE","UNLINKED"));
        if(r.reportedOutcome()!=null) member(r.reportedOutcome(),OUTCOMES); require(r.eventCount()>=0 && (r.durationMillis()==null || r.durationMillis()>=0));
        instant(r.endedAt(),true); nullableText(r.reasonCode(),100); text(r.slotLabel(),100); text(r.coverageLabel(),100); text(r.executionStateLabel(),100); nullableText(r.outcomeLabel(),100);
    }
    static void ack(AiFlowDtos.Ack a,AiFlowDtos.Batch b,int status) {
        require(a.runId().equals(b.run().runId()) && a.inserted()>=0 && a.replayed()>=0 && a.inserted()+a.replayed()==b.events().size());
        require(new HashSet<>(a.eventIds()).size()==a.eventIds().size() && new HashSet<>(a.eventIds()).equals(b.events().stream().map(AiFlowDtos.Event::eventId).collect(java.util.stream.Collectors.toSet())));
        require(status==(a.inserted()>0?201:200));
    }
    static void jsonMedia(MediaType type) { require(type!=null && type.isCompatibleWith(MediaType.APPLICATION_JSON)); }
    static void require(boolean okay) { if(!okay) throw new AiFlowProblem("UPSTREAM_INVALID"); }
    static void uuid(String s) { try { if(s==null || !UUID.fromString(s).toString().equals(s)) throw AiFlowProblem.invalid(); } catch(Exception e) { throw AiFlowProblem.invalid(); } }
    private static void member(String s,Set<String> allowed) { if(s==null || !allowed.contains(s)) throw AiFlowProblem.invalid(); }
    private static void relation(String s,boolean nullable) { if(s==null && nullable)return; member(s,Set.of("CALL_RESULT","DEPENDS_ON","BRANCH","NEXT_OBSERVED")); }
    private static void instant(Instant value,boolean nullable) { if(value==null && !nullable) throw AiFlowProblem.invalid(); }
    private static void toolCall(AiFlowDtos.ToolCall call,String eventKind) {
        if(call==null) return;
        if(!"START".equals(eventKind)) throw AiFlowProblem.invalid();
        toolName(call.name());
        if(call.innerNames()==null || call.innerNames().size()>8 || new HashSet<>(call.innerNames()).size()!=call.innerNames().size()) throw AiFlowProblem.invalid();
        call.innerNames().forEach(AiFlowProtocol::toolName);
        int count=parameterText(call.parameters());
        if(call.parametersChars()<0 || call.redactedCount()<0 || call.redactedCount()>100000
            || call.parametersChars()<count || call.truncated()!=(call.parametersChars()>count)
            || call.truncated() && count!=16384) throw AiFlowProblem.invalid();
        if(call.sourceTruncated()!=null && !call.sourceTruncated()) throw AiFlowProblem.invalid();
    }
    private static void choiceSet(AiFlowDtos.ChoiceSet set,String eventKind,AiFlowDtos.ToolCall actual) {
        if(set==null) return;
        if(!"START".equals(eventKind) || actual==null || actual.sourceTruncated()!=null) throw AiFlowProblem.invalid();
        choiceSetFields(set);
        var selected=set.options().stream().filter(option->option.optionId().equals(set.selectedOptionId())).toList();
        if(selected.size()!=1) throw AiFlowProblem.invalid();
        var option=selected.getFirst();
        if(!option.toolName().equals(actual.name()) || !option.parameters().equals(actual.parameters())
            || option.parametersChars()!=actual.parametersChars() || option.truncated()!=actual.truncated()
            || option.redactedCount()!=actual.redactedCount() || !Objects.equals(option.sourceTruncated(),actual.sourceTruncated()))
            throw AiFlowProblem.invalid();
    }
    private static void choiceSetFields(AiFlowDtos.ChoiceSet set) {
        if(set==null) throw AiFlowProblem.invalid();
        choiceText(set.title()); toolName(set.selectedOptionId());
        if(set.options()==null || set.options().size()<2 || set.options().size()>8) throw AiFlowProblem.invalid();
        Set<String> ids=new HashSet<>();int total=0;
        for(var option:set.options()) {
            if(option==null) throw AiFlowProblem.invalid();
            toolName(option.optionId());if(!ids.add(option.optionId())) throw AiFlowProblem.invalid();
            choiceText(option.label());toolName(option.toolName());
            int count=parameterText(option.parameters());
            if(PRIVATE_CHOICE_MARKER.matcher(option.parameters()).find() || option.parametersChars()<count || option.redactedCount()<0 || option.redactedCount()>100000
                || option.truncated()!=(option.parametersChars()>count) || option.truncated()&&count!=16384
                || option.sourceTruncated()!=null&&!option.sourceTruncated()) throw AiFlowProblem.invalid();
            total+=count;if(total>32768) throw AiFlowProblem.invalid();
        }
        if(!ids.contains(set.selectedOptionId())) throw AiFlowProblem.invalid();
    }
    private static void choiceSetSummary(AiFlowDtos.ChoiceSetSummary summary) {
        if(summary==null) return;
        choiceText(summary.title());toolName(summary.selectedOptionId());
        if(summary.options()==null || summary.options().size()<2 || summary.options().size()>8) throw AiFlowProblem.invalid();
        Set<String> ids=new HashSet<>();int selected=0;
        for(var option:summary.options()) {
            if(option==null) throw AiFlowProblem.invalid();
            toolName(option.optionId());if(!ids.add(option.optionId())) throw AiFlowProblem.invalid();
            choiceText(option.label());toolName(option.toolName());
            if(option.selected()) {selected++;require(option.optionId().equals(summary.selectedOptionId()));}
        }
        require(ids.contains(summary.selectedOptionId()) && selected==1);
    }
    private static void choiceText(String value) {
        if(value==null || value.isBlank() || value.codePointCount(0,value.length())>100) throw AiFlowProblem.invalid();
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);if(c<=0x1f||c==0x7f) throw AiFlowProblem.invalid();
            if(Character.isHighSurrogate(c)) {if(++i>=value.length()||!Character.isLowSurrogate(value.charAt(i))) throw AiFlowProblem.invalid();}
            else if(Character.isLowSurrogate(c)) throw AiFlowProblem.invalid();
        }
        if(SENSITIVE.matcher(value).find()||UNSAFE_PUBLIC_CHOICE_TEXT.matcher(value).find()) throw AiFlowProblem.invalid();
    }
    private static void toolName(String value) { if(value==null || !TOOL_NAME.matcher(value).matches()) throw AiFlowProblem.invalid(); }
    private static int parameterText(String value) {
        if(value==null) throw AiFlowProblem.invalid();
        int codePoints=value.codePointCount(0,value.length()); if(codePoints>16384) throw AiFlowProblem.invalid();
        for(int i=0;i<value.length();i++) {
            char c=value.charAt(i);
            if((c<=0x1f && c!='\t' && c!='\n' && c!='\r') || c==0x7f) throw AiFlowProblem.invalid();
            if(Character.isHighSurrogate(c)) {
                if(++i>=value.length() || !Character.isLowSurrogate(value.charAt(i))) throw AiFlowProblem.invalid();
            } else if(Character.isLowSurrogate(c)) throw AiFlowProblem.invalid();
        }
        return codePoints;
    }
    private static void text(String s,int max) { if(s==null || s.length()>max || s.isBlank() || s.chars().anyMatch(c->c<32 && c!=10)) throw AiFlowProblem.invalid(); }
    private static void textAllowEmpty(String s,int max) { if(s==null || s.length()>max || s.chars().anyMatch(c->c<32 && c!=10)) throw AiFlowProblem.invalid(); }
    private static void nullableText(String s,int max) { if(s!=null)text(s,max); }
    private static void opaque(String s,int max) { text(s,max); if(s.contains("://") || s.contains("/") || s.contains("\\")) throw AiFlowProblem.invalid(); }
}
