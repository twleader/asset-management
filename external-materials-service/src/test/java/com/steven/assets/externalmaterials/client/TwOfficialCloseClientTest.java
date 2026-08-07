package com.steven.assets.externalmaterials.client;

import com.steven.assets.externalmaterials.service.StockSourceQuery;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class TwOfficialCloseClientTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 7);

    @Test
    void parsesExactDateFromBothOfficialSources() {
        TwOfficialCloseClient client = new TwOfficialCloseClient(url -> url.contains("twse.com.tw")
                ? """
                  {"stat":"OK","date":"20260807","tables":[{
                    "fields":["證券代號","證券名稱","成交股數","開盤價","最高價","最低價","收盤價"],
                    "data":[["2330","台積電","12,345","1200","1210","1195","1205"]]
                  }]}
                  """
                : """
                  [{"Date":"1150807","SecuritiesCompanyCode":"00679B","CompanyName":"元大美債20年",
                    "TradingShares":"456,000","Open":"27.10","High":"27.20","Low":"27.05","Close":"27.18"}]
                  """);

        TwOfficialCloseClient.OfficialCloseBatch batch = client.fetch(DATE);

        assertThat(batch.sourceFailures()).isEmpty();
        assertThat(batch.rows()).containsOnlyKeys("2330", "00679B");
        assertThat(batch.rows().get("2330").source()).isEqualTo(StockSourceQuery.TWSE_MI_INDEX);
        assertThat(batch.rows().get("00679B").source()).isEqualTo(StockSourceQuery.TPEX_DAILY_CLOSE);
        assertThat(batch.rows().get("2330").close()).hasToString("1205");
    }

    @Test
    void rejectsTwseResponseForAnotherDateAndKeepsTpexResult() {
        TwOfficialCloseClient client = new TwOfficialCloseClient(url -> url.contains("twse.com.tw")
                ? "{\"stat\":\"OK\",\"date\":\"20260806\",\"tables\":[]}"
                : "[{\"Date\":\"1150807\",\"SecuritiesCompanyCode\":\"00679B\",\"Close\":\"27.18\"}]");

        TwOfficialCloseClient.OfficialCloseBatch batch = client.fetch(DATE);

        assertThat(batch.rows()).containsOnlyKeys("00679B");
        assertThat(batch.sourceFailures()).singleElement()
                .asString().startsWith(StockSourceQuery.TWSE_MI_INDEX);
    }

    @Test
    void skipsWrongDateAndNonPositiveOrMissingCloseRows() {
        TwOfficialCloseClient client = new TwOfficialCloseClient(url -> url.contains("twse.com.tw")
                ? """
                  {"stat":"OK","date":"20260807","tables":[{
                    "fields":["證券代號","證券名稱","收盤價"],
                    "data":[["2330","台積電","--"],["0050","元大台灣50","0"]]
                  }]}
                  """
                : """
                  [{"Date":"1150806","SecuritiesCompanyCode":"00679B","Close":"27.18"},
                   {"Date":"1150807","SecuritiesCompanyCode":"00719B","Close":"0"}]
                  """);

        TwOfficialCloseClient.OfficialCloseBatch batch = client.fetch(DATE);

        assertThat(batch.sourceFailures()).isEmpty();
        assertThat(batch.rows()).isEmpty();
    }
}
