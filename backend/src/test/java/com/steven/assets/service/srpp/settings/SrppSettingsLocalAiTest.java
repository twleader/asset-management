package com.steven.assets.service.srpp.settings;

import com.fasterxml.jackson.databind.node.*;
import com.steven.assets.dto.SrppSettingsDto.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;

/** Real HTTP verifies the isolated bridge contract, receipts and fail-closed selection boundary. */
class SrppSettingsLocalAiTest {
    @TempDir Path temp;
    HttpServer server;SrppSettingsLocalAi ai;Path token;
    final String revision="f".repeat(64);
    final AtomicInteger requests=new AtomicInteger(),interpretations=new AtomicInteger();
    final AtomicReference<String> reply=new AtomicReference<>(),received=new AtomicReference<>();
    int status=200;ObjectNode caps;
    final AiSelection choice=new AiSelection("ollama","gemma4:31b",null,"f".repeat(64));
    @BeforeEach void setup()throws Exception {
        token=temp.resolve("bridge.token");Files.writeString(token,"a".repeat(48));
        caps=JsonNodeFactory.instance.objectNode().put("schemaVersion",1).put("catalogRevision",revision);
        ObjectNode tool=caps.putArray("tools").addObject().put("id","ollama").put("label","Ollama").put("adapter","ollama")
                .put("billingMode","LOCAL").put("available",true).putNull("code").put("modelSource","RUNTIME");
        tool.putArray("models").addObject().put("id","gemma4:31b").put("label","Gemma 4").putNull("defaultEffort").putArray("efforts");
        reply.set(SettingsCanonicalJson.canonical(SettingsCanonicalJson.tree(new AiResult("依已計算結果解讀。",receipt("SUCCEEDED",null)))));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            requests.incrementAndGet();String response;
            if(!("Bearer "+"a".repeat(48)).equals(exchange.getRequestHeaders().getFirst("Authorization")))throw new AssertionError("token missing");
            if(exchange.getRequestURI().getPath().equals("/capabilities"))response=caps.toString();
            else {interpretations.incrementAndGet();received.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));response=reply.get();}
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(exchange.getRequestURI().getPath().equals("/capabilities")?200:status,bytes.length);
            exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();ai=new SrppSettingsLocalAi(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),token);
    }
    @AfterEach void stop(){server.stop(0);}
    AiExecution receipt(String status,String code){return new AiExecution("ollama","gemma4:31b",null,"gemma4:31b","PROVIDER_REPORTED","LOCAL","1","2026-11-15T01:00:00Z","2026-11-15T01:00:01Z",status,code);}
    ObjectNode result(){return JsonNodeFactory.instance.objectNode().put("calculated","123.45");}
    @Test void productionComponentIsConstructibleWithItsInjectedTokenPath() {
        try(var context=new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("test",java.util.Map.of("srpp.settings.ai-token-file",token.toString())));
            context.register(SrppSettingsLocalAi.class);context.refresh();
            assertThat(context.getBean(SrppSettingsAiPort.class)).isInstanceOf(SrppSettingsLocalAi.class);
        }
    }
    @Test void validSelectionAndReceiptCrossActualHttpWithoutMutatingNumbers() {
        assertThat(ai.capabilities().tools()).hasSize(1);ai.validateSelection(choice);
        ObjectNode frozen=result();String before=frozen.toString();AiResult response=ai.interpret(frozen,choice);
        assertThat(response.text()).contains("解讀");assertThat(response.execution().actualModel()).isEqualTo("gemma4:31b");
        assertThat(frozen.toString()).isEqualTo(before);assertThat(interpretations.get()).isEqualTo(1);
        var body=SettingsCanonicalJson.parse(received.get());assertThat(body.size()).isEqualTo(2);
        assertThat(body.path("selection")).isEqualTo(SettingsCanonicalJson.tree(choice));assertThat(body.path("result")).isEqualTo(frozen);
    }
    @Test void staleUnknownUnsupportedEffortAndBillingBlockNeverSubmitModelRequest() {
        assertThatThrownBy(()->ai.validateSelection(new AiSelection("ollama","gemma4:31b",null,"e".repeat(64))))
                .isInstanceOf(SrppSettingsProblem.class).satisfies(e->assertThat(((SrppSettingsProblem)e).code()).isEqualTo("AI_CATALOG_STALE"));
        for(AiSelection invalid:java.util.List.of(new AiSelection("missing","gemma4:31b",null,revision),
                new AiSelection("ollama","cloud-model",null,revision),new AiSelection("ollama","gemma4:31b","high",revision)))
            assertThatThrownBy(()->ai.validateSelection(invalid)).isInstanceOf(SrppSettingsProblem.class);
        ((ObjectNode)caps.path("tools").get(0)).put("available",false).put("code","AI_EXTRA_USAGE_ENABLED");
        assertThatThrownBy(()->ai.validateSelection(choice)).isInstanceOf(SrppSettingsProblem.class)
                .satisfies(e->assertThat(((SrppSettingsProblem)e).code()).isEqualTo("AI_EXTRA_USAGE_ENABLED"));
        assertThat(interpretations.get()).isZero();
    }
    @Test void effortCannotBeOmittedForAnEffortCapableModel() {
        ObjectNode model=(ObjectNode)caps.at("/tools/0/models/0");model.put("defaultEffort","high");((ArrayNode)model.path("efforts")).add("high");
        assertThatThrownBy(()->ai.validateSelection(choice)).isInstanceOf(SrppSettingsProblem.class);
        assertThatCode(()->ai.validateSelection(new AiSelection("ollama","gemma4:31b","high",revision))).doesNotThrowAnyException();
        assertThat(interpretations.get()).isZero();
    }
    @Test void missingTokenAndMalformedCapabilitiesDisableWithoutLeakingOrFallingBack()throws Exception {
        Files.delete(token);assertThat(ai.capabilities().tools()).isEmpty();assertThat(requests.get()).isZero();
        assertThatThrownBy(()->ai.validateSelection(choice)).isInstanceOf(SrppSettingsProblem.class);assertThat(requests.get()).isZero();
        Files.writeString(token,"a".repeat(48));caps.put("extra","not allowed");assertThat(ai.capabilities().tools()).isEmpty();assertThat(interpretations.get()).isZero();
    }
    @Test void failureReceiptIsSanitizedAndNoAlternativeModelIsInvoked() {
        status=409;ObjectNode error=JsonNodeFactory.instance.objectNode().put("code","AI_EXTRA_USAGE_ENABLED").put("message","unsafe raw provider output");
        error.set("execution",SettingsCanonicalJson.tree(receipt("FAILED","AI_EXTRA_USAGE_ENABLED")));reply.set(error.toString());
        assertThatThrownBy(()->ai.interpret(result(),choice)).isInstanceOf(SrppSettingsAiFailure.class)
                .satisfies(e->{SrppSettingsAiFailure failed=(SrppSettingsAiFailure)e;assertThat(failed.code()).isEqualTo("AI_EXTRA_USAGE_ENABLED");assertThat(failed.execution().status()).isEqualTo("FAILED");assertThat(failed.getMessage()).doesNotContain("unsafe");});
        assertThat(interpretations.get()).isEqualTo(1);
    }
    @Test void forbiddenToolUseFailureRemainsNamedAndNeverRetries() {
        status=409;ObjectNode error=JsonNodeFactory.instance.objectNode().put("code","AI_TOOL_USE_FORBIDDEN").put("message","工具呼叫已拒絕");
        error.set("execution",SettingsCanonicalJson.tree(receipt("FAILED","AI_TOOL_USE_FORBIDDEN")));reply.set(error.toString());
        assertThatThrownBy(()->ai.interpret(result(),choice)).isInstanceOf(SrppSettingsAiFailure.class)
                .satisfies(e->assertThat(((SrppSettingsAiFailure)e).code()).isEqualTo("AI_TOOL_USE_FORBIDDEN"));
        assertThat(interpretations.get()).isEqualTo(1);
    }
    @Test void responseReceiptCannotChangeRequestedIdentityBillingOrClaimUnreportedModel() {
        String valid=reply.get();
        for(String field:java.util.List.of("requestedModel","toolId","billingMode","requestedEffort","modelEvidence","startedAt")) {
            ObjectNode modified=(ObjectNode)SettingsCanonicalJson.parse(valid);((ObjectNode)modified.path("execution")).put(field,"wrong");reply.set(modified.toString());
            assertThatThrownBy(()->ai.interpret(result(),choice)).as(field).isInstanceOf(SrppSettingsAiFailure.class)
                    .satisfies(e->assertThat(((SrppSettingsAiFailure)e).code()).isEqualTo("AI_EXECUTION_FAILED"));
        }
    }
    @Test void responseBodyHasAnEnforcedSizeLimit() {
        reply.set(" ".repeat(2*1024*1024+1));assertThatThrownBy(()->ai.interpret(result(),choice)).isInstanceOf(SrppSettingsAiFailure.class);
        assertThat(interpretations.get()).isEqualTo(1);
    }
    @Test void selectionRequiresExactFourKeysAndRealStringTypes() {
        ObjectNode raw=(ObjectNode)SettingsCanonicalJson.tree(choice);
        assertThat(SettingsAiSelection.parse(raw)).isEqualTo(choice);
        for(String field:java.util.List.of("toolId","model","effort","catalogRevision")) {
            ObjectNode missing=raw.deepCopy();missing.remove(field);assertThatThrownBy(()->SettingsAiSelection.parse(missing)).isInstanceOf(SrppSettingsProblem.class);
            ObjectNode typed=raw.deepCopy();typed.put(field,7);assertThatThrownBy(()->SettingsAiSelection.parse(typed)).isInstanceOf(SrppSettingsProblem.class);
        }
        raw.put("command","anything");assertThatThrownBy(()->SettingsAiSelection.parse(raw)).isInstanceOf(SrppSettingsProblem.class);
    }
}
