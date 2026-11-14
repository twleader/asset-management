package com.steven.assets.model;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
class ExchangeRateHistoryProofTest {
 @Test void canonicalReceiptBindsImmutableMetadataToActualRow() {
  var row=new ExchangeRateHistory(1L,"USD",LocalDate.parse("2026-11-13"),new BigDecimal("32.7900"),new BigDecimal("32.8900"));
  assertThat(row.getRateBasis()).isEqualTo("UNKNOWN");
  var source=ExchangeRateSourceCapture.builder().id(1L).provider("FINMIND_TAIWAN_EXCHANGE_RATE").rateBasis("BANK_SPOT_SELLING").sourceUrl("https://api.finmindtrade.com/api/v4/data?dataset=TaiwanExchangeRate&data_id=USD&start_date=2024-11-08&end_date=2026-11-13").capturedAt(Instant.parse("2026-11-14T02:57:54Z")).sourceSha256("a".repeat(64)).build();
  row.setSourceCapture(source);row.setSourceMemberSha256("34f61bfc25675e69e5a517c5f6dcb6d3a90a1ea30f7f5e38772d3efd775a421b");
  assertThat(row.isSpotBasisVerified()).isTrue();assertThat(row.getRateBasis()).isEqualTo("BANK_SPOT_SELLING");
  row.setSellRate(new BigDecimal("33.11"));assertThat(row.isSpotBasisVerified()).isFalse();assertThat(row.getRateBasis()).isEqualTo("UNKNOWN");
 }
 @Test void identicalUndatedBankBodyCannotReusePreviousDayAuthority() {
  var day=LocalDate.parse("2026-11-14");var buy=new BigDecimal("32.79");var sell=new BigDecimal("32.89");
  var old=ExchangeRateSourceCapture.builder().id(2L).provider("BANK_OF_TAIWAN").rateBasis("BANK_SPOT_SELLING").sourceUrl("https://rate.bot.com.tw/xrt/flcsv/0/day").capturedAt(Instant.parse("2026-11-13T02:00:00Z")).sourceSha256("a".repeat(64)).build();
  var current=ExchangeRateSourceCapture.builder().id(3L).provider("BANK_OF_TAIWAN").rateBasis("BANK_SPOT_SELLING").sourceUrl("https://rate.bot.com.tw/xrt/flcsv/0/day").capturedAt(Instant.parse("2026-11-14T02:00:00Z")).sourceSha256("a".repeat(64)).build();
  var row=new ExchangeRateHistory(1L,"USD",day,buy,sell);row.setSourceMemberSha256(ExchangeRateSourceCapture.memberSha("a".repeat(64),"USD",day,buy,sell));
  row.setSourceCapture(old);assertThat(row.isSpotBasisVerified()).isFalse();row.setSourceCapture(current);assertThat(row.isSpotBasisVerified()).isTrue();
 }
 @Test void rawBatchBodyIsNotPartOfHistoryEntityGraphMapping() {
  assertThat(java.util.Arrays.stream(ExchangeRateSourceCapture.class.getDeclaredFields()).map(java.lang.reflect.Field::getName)).doesNotContain("rawBody","rawPayload");
 }
}
