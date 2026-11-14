package com.steven.assets.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;
import java.time.Instant;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;

/** Metadata-only mapping: raw_body deliberately is NOT mapped, so history joins cannot repeat batch payloads. */
@Entity @Table(name="exchange_rate_source_capture") @Immutable
@Getter @NoArgsConstructor @AllArgsConstructor @Builder
public class ExchangeRateSourceCapture {
 @Id private Long id;
 private String provider;
 private String rateBasis;
 private String sourceUrl;
 private Instant capturedAt;
 private String sourceSha256;
 public boolean verifiedMetadata() {
  return Set.of("FINMIND_TAIWAN_EXCHANGE_RATE","BANK_OF_TAIWAN","MEGA_BANK").contains(provider == null ? "" : provider)
   && "BANK_SPOT_SELLING".equals(rateBasis) && sourceSha256 != null && sourceSha256.matches("[a-f0-9]{64}")
   && capturedAt != null && sourceUrl != null && sourceUrl.startsWith("https://");
 }
 /** Authority context belongs to the fetch snapshot as well as its raw bytes. */
 public boolean matchesAuthorityDate(String currency,java.time.LocalDate day) {
  if(!verifiedMetadata()||!"USD".equals(currency)||day==null)return false;
  try {
   java.net.URI uri=java.net.URI.create(sourceUrl);
   if(uri.getUserInfo()!=null||uri.getFragment()!=null)return false;
   var observed=capturedAt.atZone(java.time.ZoneId.of("Asia/Taipei")).toLocalDate();
   if(provider.equals("BANK_OF_TAIWAN"))return "rate.bot.com.tw".equals(uri.getHost())&&"/xrt/flcsv/0/day".equals(uri.getPath())&&day.equals(observed);
   if(provider.equals("MEGA_BANK"))return "www.megabank.com.tw".equals(uri.getHost())&&"/api/client/ExchangeRate/GetRateData".equals(uri.getPath())&&day.equals(observed);
   if(!"api.finmindtrade.com".equals(uri.getHost())||!"/api/v4/data".equals(uri.getPath()))return false;
   java.util.Map<String,String> query=new java.util.HashMap<>();
   for(String part:uri.getRawQuery().split("&")){String[] kv=part.split("=",2);if(kv.length!=2)return false;String k=java.net.URLDecoder.decode(kv[0],StandardCharsets.UTF_8),v=java.net.URLDecoder.decode(kv[1],StandardCharsets.UTF_8);if(query.putIfAbsent(k,v)!=null)return false;}
   if(!query.keySet().equals(Set.of("dataset","data_id","start_date","end_date"))||!"TaiwanExchangeRate".equals(query.get("dataset"))||!"USD".equals(query.get("data_id")))return false;
   var from=java.time.LocalDate.parse(query.get("start_date"));var to=java.time.LocalDate.parse(query.get("end_date"));
   return from.toString().equals(query.get("start_date"))&&to.toString().equals(query.get("end_date"))&&!from.isAfter(to)&&!to.isAfter(observed)&&!day.isBefore(from)&&!day.isAfter(to);
  }catch(RuntimeException invalid){return false;}
 }
 public static String memberSha(String rawSha,String currency,java.time.LocalDate date,BigDecimal buy,BigDecimal sell) {
  String tuple="FX_SPOT_MEMBER_V1\n"+rawSha+"\n"+currency+"\n"+date+"\n"+buy.stripTrailingZeros().toPlainString()+"\n"+sell.stripTrailingZeros().toPlainString();
  try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(tuple.getBytes(StandardCharsets.UTF_8))); }
  catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
 }
}
