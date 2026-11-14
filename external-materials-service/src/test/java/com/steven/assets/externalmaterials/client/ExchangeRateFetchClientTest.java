package com.steven.assets.externalmaterials.client;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
class ExchangeRateFetchClientTest {
 static final String URL="https://api.finmindtrade.com/api/v4/data?dataset=TaiwanExchangeRate&data_id=USD&start_date=2024-11-08&end_date=2026-11-13";
 static final Instant AT=Instant.parse("2026-11-14T02:57:54Z");
 static final String RAW="{\"status\":200,\"data\":[{\"date\":\"2026-11-13\",\"currency\":\"USD\",\"cash_buy\":32.44,\"cash_sell\":33.11,\"spot_buy\":32.79,\"spot_sell\":32.89}]}";
 @Test void usdUsesNativeSpotNotCashAndBindsActualRawMember() throws Exception {
  var rows=ExchangeRateFetchClient.parseRates("USD",RAW,URL,AT);assertThat(rows).hasSize(1);
  var row=rows.getFirst();assertThat(row.buyRate()).isEqualByComparingTo("32.79");assertThat(row.sellRate()).isEqualByComparingTo("32.89");
  assertThat(row.evidence().matches("USD",row.rateDate(),row.buyRate(),row.sellRate())).isTrue();
  assertThat(row.evidence().matches("USD",row.rateDate(),row.buyRate(),new BigDecimal("33.11"))).isFalse();
  assertThat(row.evidence().matches("USD",row.rateDate().minusDays(1),row.buyRate(),row.sellRate())).isFalse();
 }
 @Test void missingNegativeAndSyntheticSpotCannotFallBackToCash() throws Exception {
  for(String body:new String[]{RAW.replace("\"spot_buy\":32.79,", ""),RAW.replace("\"spot_sell\":32.89", "\"spot_sell\":-99"),RAW.replace("\"spot_sell\":32.89", "\"spot_sell\":32.79")})assertThat(ExchangeRateFetchClient.parseRates("USD",body,URL,AT)).isEmpty();
 }
 @Test void nonUsdKeepsPriorCashSelectionWithoutSpotClaim() throws Exception {
  var row=ExchangeRateFetchClient.parseRates("EUR",RAW.replace("USD","EUR"),URL.replace("USD","EUR"),AT).getFirst();
  assertThat(row.sellRate()).isEqualByComparingTo("33.11");assertThat(row.evidence()).isNull();
 }
 @Test void memberReceiptUsesDocumentedCrossLanguageCanonicalTuple() {
  assertThat(FxSourceEvidence.memberSha("a".repeat(64),"USD",LocalDate.parse("2026-11-13"),new BigDecimal("32.7900"),new BigDecimal("32.8900"))).isEqualTo("34f61bfc25675e69e5a517c5f6dcb6d3a90a1ea30f7f5e38772d3efd775a421b");
 }
 @Test void identicalRawRetainsDistinctFetchAndQueryAuthorityContexts() {
  String csv="USD,x,32.44,32.79,x,x,x,x,x,x,x,x,33.11,32.89";
  var first=new FxSourceEvidence("BANK_OF_TAIWAN","https://rate.bot.com.tw/xrt/flcsv/0/day",csv,Instant.parse("2026-11-13T02:00:00Z"));
  var second=new FxSourceEvidence("BANK_OF_TAIWAN","https://rate.bot.com.tw/xrt/flcsv/0/day",csv,Instant.parse("2026-11-14T02:00:00Z"));
  assertThat(first.sha256()).isEqualTo(second.sha256());assertThat(first.capturedAt()).isNotEqualTo(second.capturedAt());
  assertThat(first.matches("USD",LocalDate.parse("2026-11-14"),new BigDecimal("32.79"),new BigDecimal("32.89"))).isFalse();
  assertThat(second.matches("USD",LocalDate.parse("2026-11-14"),new BigDecimal("32.79"),new BigDecimal("32.89"))).isTrue();
  var broad=new FxSourceEvidence("FINMIND_TAIWAN_EXCHANGE_RATE",URL,RAW,AT);
  var narrow=new FxSourceEvidence("FINMIND_TAIWAN_EXCHANGE_RATE",URL.replace("2024-11-08","2026-11-13"),RAW,AT);
  assertThat(broad.sha256()).isEqualTo(narrow.sha256());assertThat(broad.sourceUrl()).isNotEqualTo(narrow.sourceUrl());
 }
 @Test void bankOriginalBodiesUseNativeSpotSelectors() {
  String csv="USD,x,32.44,32.79,x,x,x,x,x,x,x,x,33.11,32.89";
  var bot=new FxSourceEvidence("BANK_OF_TAIWAN","https://rate.bot.com.tw/xrt/flcsv/0/day",csv,AT);
  assertThat(bot.matches("USD",LocalDate.parse("2026-11-14"),new BigDecimal("32.79"),new BigDecimal("32.89"))).isTrue();
  String mega="{\"rates\":[{\"currKey\":\"USD|美金\",\"spot\":{\"bid\":32.79,\"ask\":32.89},\"update\":\"20261113160000\"}]}";
  var origin=new FxSourceEvidence("MEGA_BANK","https://www.megabank.com.tw/api/client/ExchangeRate/GetRateData",mega,Instant.parse("2026-11-13T09:00:00Z"));
  assertThat(origin.matches("USD",LocalDate.parse("2026-11-13"),new BigDecimal("32.79"),new BigDecimal("32.89"))).isTrue();
  var stale=new FxSourceEvidence("MEGA_BANK","https://www.megabank.com.tw/api/client/ExchangeRate/GetRateData",mega,AT);
  assertThat(stale.matches("USD",LocalDate.parse("2026-11-13"),new BigDecimal("32.79"),new BigDecimal("32.89"))).isFalse();
 }
}
