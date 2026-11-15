package com.steven.assets.service.srpp.settings;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.steven.assets.dto.SrppSettingsDto.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Fixed host-local text bridge. No vendor SDK, API key, provider URL, or shell enters business code. */
@Component
public final class SrppSettingsLocalAi implements SrppSettingsAiPort {
    private static final int MAX_BYTES=2*1024*1024;
    private static final Set<String> CODES=Set.of("AI_UNAUTHORIZED","AI_AUTH_REQUIRED","AI_AUTH_EXPIRED","AI_INVALID_REQUEST","AI_INVALID_SELECTION",
            "AI_CATALOG_STALE","AI_TOOL_UNAVAILABLE","AI_SUBSCRIPTION_REQUIRED","AI_BILLING_UNVERIFIED",
            "AI_PAID_CREDITS_ENABLED","AI_EXTRA_USAGE_ENABLED","AI_MODEL_UNAVAILABLE","AI_REMOTE_MODEL_FORBIDDEN",
            "AI_BUSY","AI_TIMEOUT","AI_EMPTY_RESPONSE","AI_EXECUTION_FAILED","AI_TOOL_USE_FORBIDDEN");
    private static final Set<String> ADAPTERS=Set.of("codex_cli","claude_cli","ollama","local_openai");
    private final HttpClient client;
    private final URI base;
    private final Path tokenFile;

    @Autowired
    public SrppSettingsLocalAi(@Value("${srpp.settings.ai-token-file:/run/secrets/srpp_local_ai_token}") String tokenFile) {
        this(URI.create("http://host.docker.internal:8766"),Path.of(tokenFile));
    }
    /** Package scope permits isolated HTTP contract tests; production has exactly one fixed address. */
    SrppSettingsLocalAi(URI base,Path tokenFile) {
        this.base=base;this.tokenFile=tokenFile;
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER)
                .proxy(new ProxySelector(){
                    @Override public List<Proxy> select(URI uri){return List.of(Proxy.NO_PROXY);}
                    @Override public void connectFailed(URI uri,SocketAddress address,IOException failure){}
                }).build();
    }
    @Override public AiCapabilities capabilities() {
        try{return readCapabilities();}catch(RuntimeException unavailable){return new AiCapabilities(1,null,List.of());}
    }
    private AiCapabilities readCapabilities() {
        JsonNode raw;
        try {raw=call("/capabilities",null,Duration.ofSeconds(12));}
        catch(BridgeFailure rejected){throw failure(rejected.code);}
        try {
            requireKeys(raw,"schemaVersion","catalogRevision","tools");
            if(!raw.path("schemaVersion").isIntegralNumber()||!raw.path("catalogRevision").isTextual()||!raw.path("tools").isArray())throw new IllegalArgumentException();
            for(JsonNode tool:raw.path("tools")) {
                requireKeys(tool,"id","label","adapter","billingMode","available","code","modelSource","models");
                for(String field:List.of("id","label","adapter","billingMode","modelSource"))if(!tool.path(field).isTextual())throw new IllegalArgumentException();
                if(!tool.path("available").isBoolean()||!nullableString(tool.path("code"))||!tool.path("models").isArray())throw new IllegalArgumentException();
                for(JsonNode model:tool.path("models")) {
                    requireKeys(model,"id","label","efforts","defaultEffort");
                    if(!model.path("id").isTextual()||!model.path("label").isTextual()||!model.path("efforts").isArray()||!nullableString(model.path("defaultEffort")))throw new IllegalArgumentException();
                    for(JsonNode effort:model.path("efforts"))if(!effort.isTextual())throw new IllegalArgumentException();
                }
            }
            AiCapabilities caps=SettingsCanonicalJson.bind(raw,AiCapabilities.class);
            if(caps.schemaVersion()!=1||!hash(caps.catalogRevision())||caps.tools().size()>64)throw new IllegalArgumentException();
            Set<String> ids=new HashSet<>();
            for(AiTool tool:caps.tools()) {
                if(!toolId(tool.id())||!ids.add(tool.id())||!label(tool.label())||tool.adapter()==null||!tool.adapter().matches("[a-z][a-z0-9_]{0,63}")||tool.available()&&!ADAPTERS.contains(tool.adapter())
                        ||!Set.of("SUBSCRIPTION","LOCAL").contains(tool.billingMode())
                        ||!Set.of("RUNTIME","CACHE","CONFIGURED").contains(tool.modelSource())
                        ||tool.available()&&(tool.code()!=null||tool.models().isEmpty())
                        ||!tool.available()&&!CODES.contains(tool.code())||tool.models().size()>256)throw new IllegalArgumentException();
                boolean cli=tool.adapter().endsWith("_cli");
                if(ADAPTERS.contains(tool.adapter())&&cli!=tool.billingMode().equals("SUBSCRIPTION"))throw new IllegalArgumentException();
                Set<String> models=new HashSet<>();
                for(AiModel model:tool.models()) {
                    if(!modelId(model.id())||!models.add(model.id())||!label(model.label())||model.efforts().size()>16
                            ||new HashSet<>(model.efforts()).size()!=model.efforts().size()
                            ||model.efforts().stream().anyMatch(e->!effortId(e))
                            ||model.efforts().isEmpty()&&model.defaultEffort()!=null
                            ||!model.efforts().isEmpty()&&!model.efforts().contains(model.defaultEffort()))throw new IllegalArgumentException();
                }
            }
            return caps;
        }catch(RuntimeException invalid){throw failure("AI_EXECUTION_FAILED");}
    }
    @Override public void validateSelection(AiSelection selection) {
        try{selected(readCapabilities(),selection);}
        catch(SrppSettingsAiFailure unavailable){throw new SrppSettingsProblem(503,unavailable.code(),"AI 工具暫時無法驗證，請重新載入或選擇不呼叫 AI");}
    }
    private static AiTool selected(AiCapabilities caps,AiSelection selection) {
        if(selection==null)throw selectionProblem();
        if(!Objects.equals(caps.catalogRevision(),selection.catalogRevision()))throw new SrppSettingsProblem(409,"AI_CATALOG_STALE","AI 工具或模型清單已變更，請重新載入後選擇");
        AiTool tool=caps.tools().stream().filter(t->t.id().equals(selection.toolId())).findFirst().orElseThrow(SrppSettingsLocalAi::selectionProblem);
        if(!tool.available())throw new SrppSettingsProblem(409,tool.code(),"所選 AI 工具目前無法使用，請查看工具狀態");
        AiModel model=tool.models().stream().filter(m->m.id().equals(selection.model())).findFirst().orElseThrow(SrppSettingsLocalAi::selectionProblem);
        if(model.efforts().isEmpty()?selection.effort()!=null:selection.effort()==null||!model.efforts().contains(selection.effort()))throw selectionProblem();
        return tool;
    }
    @Override public AiResult interpret(JsonNode frozenResult,AiSelection selection) {
        AiTool tool;
        try {tool=selected(readCapabilities(),selection);}
        catch(SrppSettingsProblem invalid){throw failure(safeCode(invalid.code()));}
        ObjectNode body=com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        body.set("selection",SettingsCanonicalJson.tree(selection));body.set("result",frozenResult.deepCopy());
        try {
            JsonNode raw=call("/interpret",body,Duration.ofSeconds(125));
            requireKeys(raw,"text","execution");if(!raw.path("text").isTextual())throw failure("AI_EXECUTION_FAILED");requireReceiptKeys(raw.path("execution"));
            AiResult result=SettingsCanonicalJson.bind(raw,AiResult.class);
            if(result.text()==null||result.text().isBlank()||result.text().length()>20000)throw failure("AI_EMPTY_RESPONSE");
            validateReceipt(result.execution(),selection,tool.billingMode(),"SUCCEEDED",null);
            return result;
        }catch(BridgeFailure rejected) {
            AiExecution receipt=null;
            try {
                if(rejected.body.path("execution").isObject()) {
                    requireReceiptKeys(rejected.body.path("execution"));
                    receipt=SettingsCanonicalJson.bind(rejected.body.path("execution"),AiExecution.class);
                    validateReceipt(receipt,selection,tool.billingMode(),"FAILED",rejected.code);
                }
            }catch(RuntimeException invalid){throw failure("AI_EXECUTION_FAILED");}
            throw new SrppSettingsAiFailure(rejected.code,receipt);
        }catch(SrppSettingsAiFailure failure){throw failure;}
        catch(RuntimeException invalid){throw failure("AI_EXECUTION_FAILED");}
    }
    private static void validateReceipt(AiExecution receipt,AiSelection selection,String billing,String status,String code) {
        if(receipt==null||!Objects.equals(receipt.toolId(),selection.toolId())
                ||!Objects.equals(receipt.requestedModel(),selection.model())||!Objects.equals(receipt.requestedEffort(),selection.effort())
                ||!Objects.equals(receipt.billingMode(),billing)||!"1".equals(receipt.adapterVersion())
                ||!status.equals(receipt.status())||!Objects.equals(code,receipt.code()))throw failure("AI_EXECUTION_FAILED");
        if(receipt.actualModel()==null?!"REQUESTED_ONLY".equals(receipt.modelEvidence())
                :!modelId(receipt.actualModel())||!"PROVIDER_REPORTED".equals(receipt.modelEvidence()))throw failure("AI_EXECUTION_FAILED");
        try {if(Instant.parse(receipt.completedAt()).isBefore(Instant.parse(receipt.startedAt())))throw failure("AI_EXECUTION_FAILED");}
        catch(RuntimeException invalid){throw failure("AI_EXECUTION_FAILED");}
    }
    private JsonNode call(String action,JsonNode body,Duration timeout) {
        try {
            if(!Files.isRegularFile(tokenFile)||Files.size(tokenFile)>1024)throw failure("AI_TOOL_UNAVAILABLE");
            String token=Files.readString(tokenFile,StandardCharsets.UTF_8).strip();
            if(!token.matches("[A-Za-z0-9_-]{32,256}"))throw failure("AI_TOOL_UNAVAILABLE");
            HttpRequest.Builder request=HttpRequest.newBuilder(base.resolve(action)).timeout(timeout)
                    .header("Authorization","Bearer "+token).header("Accept","application/json");
            if(body==null)request.GET();else {
                byte[] encoded=SettingsCanonicalJson.canonical(body).getBytes(StandardCharsets.UTF_8);
                if(encoded.length>MAX_BYTES)throw failure("AI_INVALID_REQUEST");
                request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(encoded));
            }
            HttpResponse<byte[]> response=client.send(request.build(),info->new LimitedBody());
            if(!response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT).startsWith("application/json"))throw failure("AI_EXECUTION_FAILED");
            JsonNode value=SettingsCanonicalJson.parse(new String(response.body(),StandardCharsets.UTF_8));
            if(response.statusCode()!=200)throw new BridgeFailure(safeCode(value.path("code").asText()),value);
            return value;
        }catch(SrppSettingsAiFailure|BridgeFailure known){throw known;}
        catch(HttpTimeoutException timeoutFailure){throw failure("AI_TIMEOUT");}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw failure("AI_TIMEOUT");}
        catch(Exception unavailable){throw failure("AI_TOOL_UNAVAILABLE");}
    }
    private static final class BridgeFailure extends RuntimeException {
        final String code;final JsonNode body;
        BridgeFailure(String code,JsonNode body){this.code=code;this.body=body;}
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> future=new CompletableFuture<>();
        final ByteArrayOutputStream bytes=new ByteArrayOutputStream();Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody(){return future;}
        @Override public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;subscription.request(1);}
        @Override public void onNext(List<ByteBuffer> buffers) {
            for(ByteBuffer buffer:buffers) {
                if(bytes.size()+buffer.remaining()>MAX_BYTES){subscription.cancel();future.completeExceptionally(new IOException("AI_RESPONSE_TOO_LARGE"));return;}
                byte[] part=new byte[buffer.remaining()];buffer.get(part);bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure){future.completeExceptionally(failure);}
        @Override public void onComplete(){future.complete(bytes.toByteArray());}
    }
    private static void requireReceiptKeys(JsonNode value){
        requireKeys(value,"toolId","requestedModel","requestedEffort","actualModel","modelEvidence","billingMode","adapterVersion","startedAt","completedAt","status","code");
        for(String field:List.of("toolId","requestedModel","modelEvidence","billingMode","adapterVersion","startedAt","completedAt","status"))if(!value.path(field).isTextual())throw failure("AI_EXECUTION_FAILED");
        for(String field:List.of("requestedEffort","actualModel","code"))if(!nullableString(value.path(field)))throw failure("AI_EXECUTION_FAILED");
    }
    private static boolean nullableString(JsonNode value){return value.isNull()||value.isTextual();}
    private static void requireKeys(JsonNode value,String... keys) {
        if(!value.isObject()||value.size()!=keys.length)throw failure("AI_EXECUTION_FAILED");
        for(String key:keys)if(!value.has(key))throw failure("AI_EXECUTION_FAILED");
    }
    private static String safeCode(String code){return CODES.contains(code)?code:"AI_EXECUTION_FAILED";}
    private static SrppSettingsAiFailure failure(String code){return new SrppSettingsAiFailure(code,null);}
    private static SrppSettingsProblem selectionProblem(){return new SrppSettingsProblem(400,"AI_INVALID_SELECTION","請選擇有效的 AI 工具、模型與思考程度");}
    private static boolean hash(String value){return value!=null&&value.matches("[a-f0-9]{64}");}
    private static boolean toolId(String value){return value!=null&&value.matches("[a-z0-9][a-z0-9_-]{0,63}");}
    private static boolean modelId(String value){return value!=null&&value.matches("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,159}");}
    private static boolean effortId(String value){return value!=null&&value.matches("[a-z][a-z0-9_-]{0,31}");}
    private static boolean label(String value){return value!=null&&!value.isBlank()&&value.length()<=200;}
}
