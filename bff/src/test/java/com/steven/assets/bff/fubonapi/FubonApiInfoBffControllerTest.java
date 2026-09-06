package com.steven.assets.bff.fubonapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 富邦證 API 頁的資料模型守門（Requirement 121 / Task 386，第二版：SDK 全量唯讀查詢盤點）。
 *
 * <p><b>{@code fubon-broker-service} 新增／修改 endpoint，或富邦 SDK 版本升級改變
 * {@code accounting}／{@code stock}／{@code marketdata} 命名空間方法時，本測試與
 * {@link FubonApiInfoBffController#APIS} 需同步依 {@code spec/tasks/t386_fubon_api_documentation_view.md}
 * 「盤點方法」段落的步驟重新核對更新。</b>
 */
class FubonApiInfoBffControllerTest {

    /** 富邦「盤點方法」段落列出的 22 個下單／改單／刪單／批次／條件單／預約圈存／匯撥申請類方法，一律禁止出現。 */
    private static final Set<String> FORBIDDEN = Set.of(
            "sdk.stock.place_order",
            "sdk.stock.cancel_order",
            "sdk.stock.modify_price",
            "sdk.stock.modify_quantity",
            "sdk.stock.batch_place_order",
            "sdk.stock.batch_cancel_order",
            "sdk.stock.batch_modify_price",
            "sdk.stock.batch_modify_quantity",
            "sdk.stock.cancel_condition_orders",
            "sdk.stock.single_condition",
            "sdk.stock.single_condition_day_trade",
            "sdk.stock.single_condition_stop",
            "sdk.stock.multi_condition",
            "sdk.stock.multi_condition_day_trade",
            "sdk.stock.multi_condition_stop",
            "sdk.stock.make_modify_price_obj",
            "sdk.stock.make_modify_quantity_obj",
            "sdk.stock.reserve_cash",
            "sdk.stock.reserve_stock",
            "sdk.stock.transfer_stock",
            "sdk.stock.time_slice_order",
            "sdk.stock.trail_profit"
    );

    /** 已串接 21 筆的 (sdkReference, httpEndpoint) 組合，須與清單完全一致。 */
    private static final Set<String> CONNECTED_PAIRS = Set.of(
            "（本服務自建 meta 端點，非 SDK 方法）|GET /internal/health",
            "（本服務自建 meta 端點，非 SDK 方法）|GET /internal/config",
            "sdk.accounting.inventories|POST /internal/portfolio/read",
            "sdk.accounting.unrealized_gains_and_loses|POST /internal/portfolio/read",
            "sdk.accounting.query_settlement|POST /internal/settlement/read",
            "sdk.accounting.realized_gains_and_loses|POST /internal/realized-gains/read",
            "sdk.stock.filled_history|POST /internal/trades/read",
            "marketdata.rest_client.stock.intraday.quote|POST /internal/market-data/tw-quotes",
            "marketdata.rest_client.stock.intraday.tickers|GET /internal/market-data/taiex-index/stream",
            "marketdata.rest_client.stock.intraday.ticker|POST /internal/market-data/stock-basic/read",
            "marketdata.rest_client.stock.intraday.candles|POST /internal/market-data/intraday-candles/read",
            "marketdata.websocket_client.stock（channel=\"indices\"）|GET /internal/market-data/taiex-index/stream",
            "marketdata.rest_client.stock.ownership.etf_holdings|POST /internal/market-data/etf-holdings",
            "sdk.accounting.bank_remain|POST /internal/bank-balance/read",
            "marketdata.rest_client.stock.corporate_actions.dividends|POST /internal/market-data/dividends/read",
            "marketdata.rest_client.stock.technical.sma|POST /internal/market-data/technical-indicators/read",
            "marketdata.rest_client.stock.technical.rsi|POST /internal/market-data/technical-indicators/read",
            "marketdata.rest_client.stock.technical.kdj|POST /internal/market-data/technical-indicators/read",
            "marketdata.rest_client.stock.technical.macd|POST /internal/market-data/technical-indicators/read",
            "marketdata.rest_client.stock.technical.bb|POST /internal/market-data/technical-indicators/read",
            "marketdata.websocket_client.stock（channel=\"aggregates\"）|GET /internal/market-data/stock-push/stream"
    );

    /** 透過公開查詢方法取清單（APIS 是 private static，刻意不用反射）。 */
    private static List<FubonApiInfoDto> apis() {
        return new FubonApiInfoBffController().list();
    }

    @Test
    @DisplayName("清單恰好列出 52 筆富邦 SDK 唯讀查詢能力")
    void 筆數恰為52() {
        assertThat(apis()).hasSize(52);
    }

    @Test
    @DisplayName("connected=true 恰為 21 筆，且 (sdkReference, httpEndpoint) 組合與清單完全一致")
    void 已串接筆數與組合正確() {
        List<FubonApiInfoDto> connected = apis().stream().filter(FubonApiInfoDto::connected).toList();
        assertThat(connected).hasSize(21);

        Set<String> actual = connected.stream()
                .map(a -> a.sdkReference() + "|" + a.httpEndpoint())
                .collect(Collectors.toSet());
        assertThat(actual).isEqualTo(CONNECTED_PAIRS);
    }

    @Test
    @DisplayName("connected=false 恰為 31 筆，交割與已實現損益均為已串接的嚴格來源投影")
    void 未串接筆數與已串接財務投影明確() {
        List<FubonApiInfoDto> notConnected = apis().stream().filter(a -> !a.connected()).toList();
        assertThat(notConnected).hasSize(31);
        Map<String, String> connectedAccounting = Map.of(
                "sdk.accounting.query_settlement", "POST /internal/settlement/read",
                "sdk.accounting.realized_gains_and_loses", "POST /internal/realized-gains/read");
        assertThat(notConnected).allSatisfy(a -> {
            assertThat(a.httpEndpoint()).isEqualTo("");
        });
        assertThat(apis()).filteredOn(a -> connectedAccounting.containsKey(a.sdkReference()))
                .allSatisfy(a -> assertThat(a.connected()).isTrue());
        assertThat(apis()).filteredOn(a -> "sdk.accounting.query_settlement".equals(a.sdkReference()))
                .singleElement().satisfies(a -> {
                    assertThat(a.httpEndpoint()).isEqualTo(connectedAccounting.get(a.sdkReference()));
                    assertThat(a.description()).contains("future", "nonzero", "TWD transit", "完整結算窗口", "相同", "缺少", "不同");
                    assertThat(a.consumer()).contains("accountBindingExplicit=true", "SDK_RANGE_3D_RETURNED_ROWS", "reason=null")
                            .doesNotContain("需另啟用設定");
                    assertThat(a.responseSummary()).contains("JSON boolean", "SDK_RANGE_3D_RETURNED_ROWS", "reason=null");
                });
        assertThat(apis()).filteredOn(a -> "sdk.accounting.realized_gains_and_loses".equals(a.sdkReference()))
                .singleElement().satisfies(a -> {
                    assertThat(a.httpEndpoint()).isEqualTo(connectedAccounting.get(a.sdkReference()));
                    assertThat(a.description()).contains("調節成本基礎", "不是原始取得成本", "完整 ledger", "略過", "缺少");
                    assertThat(a.consumer()).contains("accountBindingExplicit=true", "手動等價", "manual-equivalence", "source-idempotency")
                            .doesNotContain("需另啟用設定");
                    assertThat(a.responseSummary()).contains("JSON boolean");
                });
    }

    @Test
    @DisplayName("每筆 category 屬於七類之一，且依類別統計筆數正確")
    void 分類與統計正確() {
        Set<String> validCategories = Set.of(
                "連線狀態查詢", "帳戶／庫存查詢", "委託與交易資訊查詢",
                "個股報價查詢", "歷史成交查詢", "行情查詢", "即時推播"
        );
        assertThat(apis()).allSatisfy(a -> assertThat(validCategories).contains(a.category()));

        Map<String, Long> countByCategory = apis().stream()
                .collect(Collectors.groupingBy(FubonApiInfoDto::category, Collectors.counting()));

        assertThat(countByCategory).isEqualTo(Map.of(
                "連線狀態查詢", 2L,
                "帳戶／庫存查詢", 7L,
                "委託與交易資訊查詢", 18L,
                "個股報價查詢", 2L,
                "歷史成交查詢", 1L,
                "行情查詢", 20L,
                "即時推播", 2L
        ));
    }

    @Test
    @DisplayName("每筆 sdkReference／name／description／consumer／requestSummary／responseSummary 皆非空白字串")
    void 欄位皆非空白() {
        assertThat(apis()).allSatisfy(a -> {
            assertThat(a.sdkReference()).isNotBlank();
            assertThat(a.name()).isNotBlank();
            assertThat(a.description()).isNotBlank();
            assertThat(a.consumer()).isNotBlank();
            assertThat(a.requestSummary()).isNotBlank();
            assertThat(a.responseSummary()).isNotBlank();
        });
    }

    @Test
    @DisplayName("每筆 description 皆明確含唯讀措辭「純查詢」或「不影響券商端」")
    void description含唯讀措辭() {
        assertThat(apis()).allSatisfy(a ->
                assertThat(a.description())
                        .as("description of %s", a.sdkReference())
                        .satisfiesAnyOf(
                                d -> assertThat(d).contains("純查詢"),
                                d -> assertThat(d).contains("不影響券商端")
                        ));
    }

    @Test
    @DisplayName("52 筆 sdkReference 沒有任何一筆與禁止的 22 個下單／改單／刪單類方法完整相等")
    void 不含禁止的下單類方法() {
        // 注意：必須用完整字串精確相等比對，不得用子字串 contains／doesNotContain——
        // 合法收錄的查詢方法 sdk.stock.get_time_slice_order 字面上就包含子字串
        // "time_slice_order"，用子字串比對會把這筆合法資料誤判為違規、測試必定紅燈。
        for (FubonApiInfoDto a : apis()) {
            assertThat(FORBIDDEN)
                    .as("sdkReference %s 不得完全等於任一禁止方法", a.sdkReference())
                    .doesNotContain(a.sdkReference());
        }
    }

    @Test
    @DisplayName("52 筆 sdkReference 皆不包含 futopt 字串（期貨選擇權整體排除）")
    void 不含期貨選擇權命名空間() {
        assertThat(apis()).allSatisfy(a -> assertThat(a.sdkReference()).doesNotContain("futopt"));
    }

    @Test
    void etfDocumentsNormalizedDateAndReadOnlyScheduledScope() {
        var etf = apis().stream().filter(api -> api.sdkReference().endsWith("ownership.etf_holdings"))
                .findFirst().orElseThrow();
        assertThat(etf.connected()).isTrue();
        assertThat(etf.consumer()).contains("08:50", "15:30", "交易雷達", "需另啟用設定");
        assertThat(etf.responseSummary()).contains("正規化 JSON", "sourceDate", "decimal 字串")
                .doesNotContain("逐字保存", "尚未核實", "佔位");
        assertThat(apis().stream().filter(api -> "行情查詢".equals(api.category()) && api.connected()).count()).isEqualTo(11);
    }
    @Test
    void cashDividendScopeDoesNotClaimCapitalChangesOrUnverifiedStockDividendAmounts() {
        var dividends = apis().stream().filter(api -> api.sdkReference().endsWith("corporate_actions.dividends")).findFirst().orElseThrow();
        assertThat(dividends.connected()).isTrue();
        assertThat(dividends.description()).contains("PARTIAL", "現金股利", "未包含減資", "未核實配股金額");
        assertThat(dividends.consumer()).contains("POST /internal/dividend/fubon-sync");
        var capital = apis().stream().filter(api -> api.sdkReference().endsWith("corporate_actions.capital_changes")).findFirst().orElseThrow();
        assertThat(capital.connected()).isFalse(); assertThat(capital.httpEndpoint()).isEmpty();
    }

    @Test
    void newMarketRowsDescribeActualWireAndOnlyDedicatedReadbackPaths() {
        var macd = apis().stream().filter(api -> api.sdkReference().endsWith("technical.macd")).findFirst().orElseThrow();
        assertThat(macd.consumer()).contains("13:40", "/internal/technical-indicators/fubon-sync");
        assertThat(macd.responseSummary()).contains("schemaVersion=2", "macdLine", "signalLine", "不捏造 EMA／DIF／OSC");
        var sma = apis().stream().filter(api -> api.sdkReference().endsWith("technical.sma")).findFirst().orElseThrow();
        var rsi = apis().stream().filter(api -> api.sdkReference().endsWith("technical.rsi")).findFirst().orElseThrow();
        var ticker = apis().stream().filter(api -> api.sdkReference().endsWith("intraday.ticker")).findFirst().orElseThrow();
        var candles = apis().stream().filter(api -> api.sdkReference().endsWith("intraday.candles")).findFirst().orElseThrow();
        assertThat(sma.connected()).isTrue();
        assertThat(rsi.connected()).isTrue();
        assertThat(ticker.httpEndpoint()).isEqualTo("POST /internal/market-data/stock-basic/read");
        assertThat(candles.httpEndpoint()).isEqualTo("POST /internal/market-data/intraday-candles/read");
        assertThat(sma.description()).contains("17-profile", "PostgreSQL", "100 秒 BOUND Redis");
        assertThat(rsi.description()).contains("local fallback", "不覆寫富邦歷史");
        assertThat(ticker.responseSummary()).contains("無 Redis／價格 history／latest quote");
        assertThat(candles.responseSummary()).contains("絕不寫 Redis", "daily price history", "latest quote");
        var push = apis().stream().filter(api -> api.sdkReference().contains("aggregates")).findFirst().orElseThrow();
        assertThat(push.connected()).isTrue();
        assertThat(push.responseSummary()).contains("tradeSize", "price", "sourceDate", "volume 為 null");
        assertThat(push.consumer()).contains("subscriptions", "120 秒");
    }

}
