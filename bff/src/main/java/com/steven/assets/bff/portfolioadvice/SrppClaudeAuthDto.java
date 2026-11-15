package com.steven.assets.bff.portfolioadvice;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.net.URI;
import java.util.*;
/** Closed authentication protocol. No credential or native output is exposed. */
public final class SrppClaudeAuthDto {
 private SrppClaudeAuthDto(){}
 public static final ObjectMapper JSON=JsonMapper.builder().addModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
 public static final Set<String> CODES=Set.of("AUTH_BUSY","AUTH_FAILED","AUTH_TIMEOUT","AUTH_INVALID_CODE","AUTH_SESSION_NOT_FOUND","AI_INVALID_REQUEST","AI_INVALID_SELECTION","AI_AUTH_REQUIRED","AI_AUTH_EXPIRED","AI_AUTH_IN_PROGRESS","AI_UNAUTHORIZED","AI_TOOL_UNAVAILABLE");
 public record AuthLocalState(int schemaVersion,String toolId,String loginState,@com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) Instant expiresAt,String code){}
 public record AuthFlow(int schemaVersion,String flowId,String toolId,String status,String authorizationUrl,@com.fasterxml.jackson.annotation.JsonFormat(shape=com.fasterxml.jackson.annotation.JsonFormat.Shape.STRING) Instant expiresAt,String code){@Override public String toString(){return "AuthFlow[redacted]";}}
 public record BootstrapResponse(int schemaVersion,String csrfToken,AuthLocalState login){@Override public String toString(){return "BootstrapResponse[redacted]";}}
 public record ProblemResponse(String code,String message){}
 public static boolean opaque(String s){return s!=null&&s.matches("[A-Za-z0-9_-]{43}");}
 public static boolean tool(String s){return s!=null&&s.matches("[a-z0-9][a-z0-9_-]{0,63}");}
 public static void keys(JsonNode n,String...fields){if(n==null||!n.isObject()||n.size()!=fields.length)throw new IllegalArgumentException();for(String f:fields)if(!n.has(f))throw new IllegalArgumentException();}
 public static JsonNode parse(String raw){try{if(raw==null||raw.length()>16384)throw new IllegalArgumentException();return JSON.readTree(raw);}catch(Exception e){throw new IllegalArgumentException("Invalid authentication request");}}
 public static String text(JsonNode n,String k){if(!n.path(k).isTextual())throw new IllegalArgumentException();return n.path(k).textValue();}
 private static boolean iso(String s){try{return s!=null&&s.endsWith("Z")&&Instant.parse(s)!=null;}catch(Exception e){return false;}}
 private static void types(JsonNode n,String...required){if(n.has("expiresAt")&&!n.path("expiresAt").isNull()&&(!n.path("expiresAt").isTextual()||!iso(n.path("expiresAt").textValue())))throw new IllegalArgumentException();if(!n.path("schemaVersion").isIntegralNumber())throw new IllegalArgumentException();for(String f:required)if(!n.path(f).isTextual())throw new IllegalArgumentException();for(String f:List.of("expiresAt","code","authorizationUrl"))if(n.has(f)&&!n.path(f).isNull()&&!n.path(f).isTextual())throw new IllegalArgumentException();}
 public static AuthLocalState local(JsonNode n){keys(n,"schemaVersion","toolId","loginState","expiresAt","code");try{types(n,"toolId","loginState");AuthLocalState s=JSON.treeToValue(n,AuthLocalState.class);if(s.schemaVersion()!=1||!tool(s.toolId())||!Set.of("VALID","REQUIRED","EXPIRED","UNKNOWN","AUTHENTICATING").contains(s.loginState())||s.code()!=null&&!CODES.contains(s.code()))throw new IllegalArgumentException();if(!localMeaning(s))throw new IllegalArgumentException();return s;}catch(Exception e){throw new IllegalArgumentException("Invalid authentication response");}}
 public static AuthFlow flow(JsonNode n){keys(n,"schemaVersion","flowId","toolId","status","authorizationUrl","expiresAt","code");try{types(n,"flowId","toolId","status");AuthFlow s=JSON.treeToValue(n,AuthFlow.class);if(s.schemaVersion()!=1||!opaque(s.flowId())||!tool(s.toolId())||!Set.of("WAITING_FOR_URL","WAITING_FOR_CODE","VERIFYING","SUCCEEDED","FAILED","CANCELLED","EXPIRED").contains(s.status())||s.expiresAt()==null||s.code()!=null&&!CODES.contains(s.code())||s.authorizationUrl()!=null&&(!"WAITING_FOR_CODE".equals(s.status())||!official(s.authorizationUrl())))throw new IllegalArgumentException();if(!flowMeaning(s))throw new IllegalArgumentException();return s;}catch(Exception e){throw new IllegalArgumentException("Invalid authentication response");}}
 private static boolean localMeaning(AuthLocalState s){return switch(s.loginState()){
 case "VALID" -> s.code()==null&&s.expiresAt()!=null&&s.expiresAt().isAfter(Instant.now());
 case "EXPIRED" -> "AI_AUTH_EXPIRED".equals(s.code())&&s.expiresAt()!=null&&!s.expiresAt().isAfter(Instant.now());
 case "AUTHENTICATING" -> "AI_AUTH_IN_PROGRESS".equals(s.code())&&s.expiresAt()==null;
 case "REQUIRED","UNKNOWN" -> "AI_AUTH_REQUIRED".equals(s.code())&&s.expiresAt()==null;
 default -> false;};}
 private static boolean flowMeaning(AuthFlow s){return switch(s.status()){
 case "WAITING_FOR_CODE" -> s.code()==null&&s.authorizationUrl()!=null;
 case "WAITING_FOR_URL","VERIFYING","SUCCEEDED","CANCELLED" -> s.code()==null&&s.authorizationUrl()==null;
 case "FAILED" -> s.code()!=null&&CODES.contains(s.code())&&s.authorizationUrl()==null;
 case "EXPIRED" -> "AUTH_TIMEOUT".equals(s.code())&&s.authorizationUrl()==null;
 default -> false;};}
 public static boolean official(String value){try{if(value.length()>8192)return false;URI u=URI.create(value);if(!"https".equals(u.getScheme())||!"claude.com".equals(u.getHost())||!"/cai/oauth/authorize".equals(u.getRawPath())||u.getUserInfo()!=null||u.getPort()!=-1||u.getFragment()!=null)return false;Map<String,String> q=new HashMap<>();for(String p:u.getRawQuery().split("&")){String[]kv=p.split("=",2);String k=java.net.URLDecoder.decode(kv[0],java.nio.charset.StandardCharsets.UTF_8),v=kv.length==2?java.net.URLDecoder.decode(kv[1],java.nio.charset.StandardCharsets.UTF_8):"";if(q.put(k,v)!=null)return false;}Set<String> required=Set.of("code","response_type","redirect_uri","client_id","scope","code_challenge","code_challenge_method","state");if(!q.keySet().containsAll(required)||q.keySet().stream().anyMatch(k->!required.contains(k)&&!k.equals("orgUUID")))return false;String[] scopes=q.get("scope").split(" ");return "true".equals(q.get("code"))&&"code".equals(q.get("response_type"))&&"https://platform.claude.com/oauth/code/callback".equals(q.get("redirect_uri"))&&"9d1c250a-e61b-44d9-88ed-5944d1962f5e".equals(q.get("client_id"))&&"S256".equals(q.get("code_challenge_method"))&&q.get("code_challenge").matches("[A-Za-z0-9_-]{43}")&&q.get("state").matches("[A-Za-z0-9_-]{16,256}")&&scopes.length==8&&Set.of(scopes).equals(Set.of("org:create_api_key","user:profile","user:inference","user:sessions:claude_code","user:mcp_servers","user:file_upload","user:plugins","user:privacy_consents:write"))&&(!q.containsKey("orgUUID")||q.get("orgUUID").matches("[a-fA-F0-9]{8}(-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}"));}catch(Exception e){return false;}}
 public static String safe(String code){return code!=null&&CODES.contains(code)?code:"AUTH_FAILED";}
 public static boolean validCode(String code){return code!=null&&code.length()<=4096&&code.matches("[!-~]+#[!-~]+")&&code.indexOf('#')==code.lastIndexOf('#');}
}
