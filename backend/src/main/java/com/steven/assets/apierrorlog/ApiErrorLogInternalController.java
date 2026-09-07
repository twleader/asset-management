package com.steven.assets.apierrorlog;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;

/** Private BFF-only ingress. It is intentionally not under a BFF or public gateway route. */
@RestController
@RequestMapping("/internal/api-error-logs")
public class ApiErrorLogInternalController {
    private static final Set<String> FIELDS = Set.of("source","operationKey","apiName","messageHeader","stackTrace","occurredAt","httpStatus","dedupeKey");
    private final ApiErrorLogRecorder recorder; private final byte[] expectedDigest;
    public ApiErrorLogInternalController(ApiErrorLogRecorder recorder,@Value("${API_ERROR_LOG_INGEST_TOKEN:}") String token) { this.recorder=recorder; this.expectedDigest=digest(token); }
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void ingest(@RequestHeader HttpHeaders headers, @RequestBody JsonNode body) {
        List<String> values=headers.getOrEmpty("X-Internal-Service-Token");
        if (expectedDigest.length == 0 || values.size()!=1 || !MessageDigest.isEqual(expectedDigest,digest(values.getFirst()))) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        if (!body.isObject() || !fieldNames(body).equals(FIELDS)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        try {
            String source=text(body,"source"), key=text(body,"operationKey"), name=text(body,"apiName"), header=text(body,"messageHeader"), trace=text(body,"stackTrace");
            Integer httpStatus=httpStatus(body,"httpStatus"); String dedupeKey=nullableText(body,"dedupeKey");
            if (!ApiErrorLogOperationCatalog.OPEN_API.equals(source)) throw new IllegalArgumentException();
            ApiErrorLogOperationCatalog.require(source, key, name);
            recorder.record(source,key,name,header,trace,Instant.parse(text(body,"occurredAt")),httpStatus,dedupeKey);
        } catch (RuntimeException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST); }
    }
    private static String text(JsonNode body,String key){ if(!body.path(key).isTextual()) throw new IllegalArgumentException(); return body.path(key).textValue(); }
    private static Integer httpStatus(JsonNode body,String key){
        JsonNode node=body.path(key);
        if (!node.isInt()) throw new IllegalArgumentException();
        int value=node.intValue();
        if (value<100 || value>599) throw new IllegalArgumentException();
        return value;
    }
    private static String nullableText(JsonNode body,String key){
        JsonNode node=body.path(key);
        if (node.isNull()) return null;
        if (!node.isTextual()) throw new IllegalArgumentException();
        return node.textValue();
    }
    private static Set<String> fieldNames(JsonNode node){ Set<String> out=new HashSet<>(); node.fieldNames().forEachRemaining(out::add); return out; }
    private static byte[] digest(String value){ if(value==null || value.isBlank()) return new byte[0]; try{return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));}catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);} }
}
