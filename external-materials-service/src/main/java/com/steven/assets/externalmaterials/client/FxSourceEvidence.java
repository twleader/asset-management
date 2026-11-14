package com.steven.assets.externalmaterials.client;

import com.fasterxml.jackson.databind.*;
import java.math.BigDecimal;
import java.time.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Original public response captured by an existing history writer, never by the 2-second live cache. */
public final class FxSourceEvidence {
 private final String provider,sourceUrl,rawBody,sha;
 private final Instant capturedAt;
 private volatile Map<String,BigDecimal[]> members;
 public FxSourceEvidence(String provider,String sourceUrl,String rawBody,Instant capturedAt) {
  this.provider=Objects.requireNonNull(provider);this.sourceUrl=Objects.requireNonNull(sourceUrl);
  this.rawBody=Objects.requireNonNull(rawBody);this.capturedAt=Objects.requireNonNull(capturedAt);
  URI uri=URI.create(sourceUrl);
  String host=switch(provider){case "FINMIND_TAIWAN_EXCHANGE_RATE"->"api.finmindtrade.com";case "BANK_OF_TAIWAN"->"rate.bot.com.tw";case "MEGA_BANK"->"www.megabank.com.tw";default->throw new IllegalArgumentException("FX_SOURCE_UNSUPPORTED");};
  if(!"https".equals(uri.getScheme())||!host.equals(uri.getHost())||sourceUrl.toLowerCase().matches(".*(token|password|api_key|authorization)=.*"))throw new IllegalArgumentException("FX_SOURCE_URI_INVALID");
  this.sha=hash(rawBody);
 }
 public String provider(){return provider;} public String sourceUrl(){return sourceUrl;} public String rawBody(){return rawBody;}
 public Instant capturedAt(){return capturedAt;} public String sha256(){return sha;} public String rateBasis(){return "BANK_SPOT_SELLING";}
 public boolean matches(String currency,LocalDate date,BigDecimal buy,BigDecimal sell) {
  if(!"USD".equals(currency)||date==null||buy==null||sell==null||buy.signum()<=0||sell.compareTo(buy)<=0||date.isAfter(capturedAt.atZone(ZoneId.of("Asia/Taipei")).toLocalDate()))return false;
  if(!authorityDate(currency,date))return false;
  try { BigDecimal[] nativeValues=members().get(currency+":"+date);return nativeValues!=null&&nativeValues[0].compareTo(buy)==0&&nativeValues[1].compareTo(sell)==0; }
  catch(RuntimeException malformed){return false;}
 }
 private boolean authorityDate(String currency,LocalDate date) {
  var observed=capturedAt.atZone(ZoneId.of("Asia/Taipei")).toLocalDate();
  if(!provider.equals("FINMIND_TAIWAN_EXCHANGE_RATE"))return date.equals(observed);
  try {
   URI uri=URI.create(sourceUrl);if(uri.getUserInfo()!=null||uri.getFragment()!=null||!"/api/v4/data".equals(uri.getPath()))return false;
   Map<String,String> q=new HashMap<>();for(String part:uri.getRawQuery().split("&")){String[] kv=part.split("=",2);if(kv.length!=2)return false;String k=java.net.URLDecoder.decode(kv[0],StandardCharsets.UTF_8),v=java.net.URLDecoder.decode(kv[1],StandardCharsets.UTF_8);if(q.putIfAbsent(k,v)!=null)return false;}
   if(!q.keySet().equals(Set.of("dataset","data_id","start_date","end_date"))||!"TaiwanExchangeRate".equals(q.get("dataset"))||!currency.equals(q.get("data_id")))return false;
   var from=LocalDate.parse(q.get("start_date"));var to=LocalDate.parse(q.get("end_date"));
   return from.toString().equals(q.get("start_date"))&&to.toString().equals(q.get("end_date"))&&!from.isAfter(to)&&!to.isAfter(observed)&&!date.isBefore(from)&&!date.isAfter(to);
  }catch(RuntimeException malformed){return false;}
 }
 private Map<String,BigDecimal[]> members() {
  Map<String,BigDecimal[]> found=members;if(found!=null)return found;
  synchronized(this){if(members!=null)return members;Map<String,BigDecimal[]> out=new HashMap<>();
   try {
    if(provider.equals("FINMIND_TAIWAN_EXCHANGE_RATE")) {
     JsonNode root=new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(rawBody);if(root.path("status").asInt()!=200||!root.path("data").isArray())throw new IllegalArgumentException("FX_RAW_STATUS_INVALID");
     for(JsonNode row:root.path("data"))put(out,row.path("currency").asText(),LocalDate.parse(row.path("date").asText()),value(row.path("spot_buy")),value(row.path("spot_sell")));
    } else if(provider.equals("BANK_OF_TAIWAN")) {
     for(String line:rawBody.replace("\uFEFF","").split("\r?\n")){String[] c=line.trim().split(",");if(c.length>=14&&c[0].trim().equals("USD"))put(out,"USD",capturedAt.atZone(ZoneId.of("Asia/Taipei")).toLocalDate(),new BigDecimal(c[3].trim()),new BigDecimal(c[13].trim()));}
    } else {
     for(JsonNode row:new ObjectMapper().enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(rawBody).path("rates")) {String code=row.path("currKey").asText().split("\\|")[0];Instant time=MegaFxFetchClient.parseUpdate(row.path("update").asText());if(time!=null)put(out,code,time.atZone(ZoneId.of("Asia/Taipei")).toLocalDate(),value(row.path("spot").path("bid")),value(row.path("spot").path("ask")));}
    }
   }catch(java.io.IOException malformed){throw new IllegalArgumentException("FX_RAW_INVALID",malformed);}
   members=Map.copyOf(out);return members;
  }
 }
 private static BigDecimal value(JsonNode n){if(n.isBoolean()||n.isNull()||n.isMissingNode())return null;try{return new BigDecimal(n.asText());}catch(NumberFormatException invalid){return null;}}
 private static void put(Map<String,BigDecimal[]> out,String code,LocalDate date,BigDecimal buy,BigDecimal sell){
  if(!"USD".equals(code)||buy==null||sell==null||buy.signum()<=0||sell.compareTo(buy)<=0||buy.stripTrailingZeros().scale()>4||sell.stripTrailingZeros().scale()>4)return;
  String key=code+":"+date;BigDecimal[] old=out.putIfAbsent(key,new BigDecimal[]{buy,sell});
  if(old!=null)throw new IllegalArgumentException("FX_RAW_DUPLICATE_DATE");
 }
 public String memberSha(String currency,LocalDate date,BigDecimal buy,BigDecimal sell){
  if(!matches(currency,date,buy,sell))throw new IllegalArgumentException("FX_NATIVE_MEMBER_MISMATCH");
  return memberSha(sha,currency,date,buy,sell);
 }
 public static String memberSha(String rawSha,String currency,LocalDate date,BigDecimal buy,BigDecimal sell){
  return hash("FX_SPOT_MEMBER_V1\n"+rawSha+"\n"+currency+"\n"+date+"\n"+buy.stripTrailingZeros().toPlainString()+"\n"+sell.stripTrailingZeros().toPlainString());
 }
 public static String hash(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}}
}
