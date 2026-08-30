# [t386] 系統資訊「富邦證 API」頁——SDK 全量唯讀查詢盤點

**對應 Requirements:** Requirement 121（系統資訊新增「富邦證 API」頁，列出富邦官方 SDK 全部已驗證存在的唯讀查詢能力，並標示每一筆是否已被本系統串接，可展開看請求／回應明細；一律排除任何下單／改單／撤單等寫入方法）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

`fubon-broker-service` 是本系統對富邦證券的唯一整合服務（Python，隔離的富邦官方 Linux SDK `fubon_neo` 2.2.9，Docker 內以 hash-verified wheel 安裝），接手排程更新後有 14 支內部 HTTP endpoint，僅允許帳務讀取、行情讀取與訂閱管理；交割款／已實現損益的 adapter 已可解析，但正常財務寫入仍未完成：

| method | path | 用途 |
|---|---|---|
| GET | `/internal/health` | 服務存活與設定狀態（供 Docker healthcheck） |
| GET | `/internal/config` | 憑證掛載與三項唯讀能力（庫存查詢／台股報價／大盤指數串流）是否就緒 |
| POST | `/internal/portfolio/read` | 現股庫存與未實現損益查詢（內部呼叫 2 個 SDK 方法） |
| POST | `/internal/market-data/tw-quotes` | 台股即時成交價與最佳五檔查詢 |
| POST | `/internal/market-data/etf-holdings` | 台股 ETF 成分股持股明細查詢（Requirement 123／Task 389–390 新增，本任務第一版完成當下尚不存在） |
| POST | `/internal/trades/read` | 最長 7 天區間的已成交明細查詢 |
| GET | `/internal/market-data/taiex-index/stream` | 富邦官方 WebSocket 台股加權指數即時點數（SSE 訂閱） |
| POST | `/internal/bank-balance/read` | 銀行餘額唯讀正規化；business 依 Task393 更新既有銀行存款 |
| POST | `/internal/settlement/read` | 交割款安全解析；coverage 未核實，business 不寫在途款 |
| POST | `/internal/realized-gains/read` | 損益安全解析；成交身分／成本口徑未核實，business 不寫損益 |
| POST | `/internal/market-data/dividends/read` | 一次日期批次、只回雷達交集的 PARTIAL 現金股利證據 |
| POST | `/internal/market-data/technical-indicators/read` | 每檔分別取得 KDJ／MACD／BB 的日線來源與參數 |
| POST | `/internal/market-data/stock-push/subscriptions` | 更新獨立個股串流 desired set 與 120 秒租期，非交易操作 |
| GET | `/internal/market-data/stock-push/stream` | 獨立 aggregates 個股實際成交 SSE，與 indices 串流分離 |

上表為 Task393–398 接手後的 adapter 路由範圍；不代表兩項財務 writer 已完成。**使用者看過 Task386 第一版畫面後，要求擴大範圍**：不只列出當時的 adapter HTTP endpoint，還要把「富邦官方 SDK 具備但本系統尚未串接」的唯讀查詢能力也列出來，新增一欄「已串接」標示每一筆的串接狀態，並讓每一列可以展開看到請求參數與回應內容的說明（比照既有「今日交易雷達」`TradingRadarView.vue` 的 `el-table-column type="expand"` 展開列模式）。

### 盤點方法（本任務檔的資料如何得出，供之後重新核對時依循同一步驟）

在運行中的 `fubon-broker-service` 容器內直接以 Python 內省已安裝的 SDK 物件（`docker exec asset-fubon-broker-service python3 -c "..."`），取得真實方法清單並逐一讀取 `__doc__`：

1. `from fubon_neo.sdk import FubonSDK; sdk = FubonSDK()`（不需登入即可取得物件與其方法簽章／docstring）。
2. `dir(sdk.accounting)` → 7 個方法，全部為查詢用途。
3. `dir(sdk.stock)` → 43 個方法。**下列 22 個為下單／改單／刪單／批次／條件單／預約圈存／匯撥申請類方法，一律排除，不進入本頁清單，不論是否標示未串接**：
   `place_order`、`cancel_order`、`modify_price`、`modify_quantity`、`batch_place_order`、`batch_cancel_order`、
   `batch_modify_price`、`batch_modify_quantity`、`cancel_condition_orders`、`single_condition`、
   `single_condition_day_trade`、`single_condition_stop`、`multi_condition`、`multi_condition_day_trade`、
   `multi_condition_stop`、`make_modify_price_obj`、`make_modify_quantity_obj`、`reserve_cash`、`reserve_stock`、
   `transfer_stock`、`time_slice_order`、`trail_profit`。
   （`time_slice_order`／`trail_profit` 的 docstring 皆為空，但因與確認為查詢的 `get_time_slice_order`／`get_trail_order`／`get_trail_history` 成對出現、且不帶 `get_` 前綴，依 SDK 既有「查詢用 `get_`／`query_` 前綴，動作用無前綴或 `place_`/`modify_`」的命名慣例保守判定為寫入方法，排除不收錄。）
   其餘 21 個方法（含 `filled_history`）為查詢用途，全數收錄。
4. `fugle_marketdata` 套件（獨立 PyPI 套件 `fugle-marketdata==2.5.0rc5`，`fubon_neo.sdk` 內 `from fugle_marketdata import FugleAPIError` 引用；`sdk.marketdata.rest_client.stock` 實際回傳的 `RestStockClient` 定義在此套件，非編譯二進位，可直接 `cat` 原始碼）：`rest/stock/{intraday,historical,snapshot,technical,corporate_actions,ownership}.py` 六個子模組，共 20 個方法。全部透過 `BaseRest.request()`（純 HTTP GET 轉發，`requests.get(url, headers=...)`）取得行情資料，無任何寫入方法。
5. `sdk.marketdata.websocket_client.stock`：既有已串接的大盤指數即時串流（`taiex_index_stream.py`）訂閱同一個 `WebSocketStockClient` 物件的 `channel: "indices"`；同一物件理論上也可訂閱個股頻道，故額外收錄 1 筆「未串接」的個股即時推播能力。`fubon_neo/adapter.py` 的 `WebSocketStockClientWrapper.subscribe()` 對 Speed 模式限制 channel 不得為 `aggregates`／`candles`，已驗證這兩個頻道名稱確實存在；其餘頻道（如逐筆成交／五檔／報價）確切名稱未在本專案程式碼中驗證，如實標註。 本段為原盤點歷史；Task397 已依官方契約採用 Normal 模式的獨立 `aggregates` client，並標記個股推播為已串接，不與原 `indices` 連線共用訂閱狀態。
6. `sdk.futopt`／`sdk.futopt_accounting`（期貨選擇權）**整體排除**，不進入本頁範圍——本系統資產模型不含期貨/選擇權。

側欄目前有「系統資訊」選單分組（`frontend/src/App.vue` 的 `mainMenuItems`，`index: 'system-info'`），底下已有「排程列表」（`/schedule-list`）、「開放 API」（`/open-api`）、「富邦證 API」（`/fubon-api`，第一版已建立）三個項目，三者相對順序、path、存取控制本次不得改動，只調整「富邦證 API」頁本身的內容與 BFF 資料模型。

## 要做什麼

- [ ] **386.1 重寫 BFF DTO。** 修改 `bff/src/main/java/com/steven/assets/bff/fubonapi/FubonApiInfoDto.java`，欄位改為：
  ```java
  package com.steven.assets.bff.fubonapi;

  /**
   * FubonApiView 專屬 BFF（「系統資訊」分組，Requirement 121）單筆富邦 SDK 唯讀查詢能力。
   *
   * <p>唯讀資訊展示用的不可變 record。清單為 {@link FubonApiInfoBffController} 內建靜態資料，
   * 涵蓋富邦官方 SDK（{@code fubon_neo} 2.2.9）{@code accounting}／{@code stock}／
   * {@code marketdata} 命名空間中已驗證存在、且確認為唯讀查詢的方法，一律排除任何下單／
   * 改單／撤單等寫入方法。
   *
   * @param connected       本頁所述能力是否有已驗證的正常整合路徑；唯讀預檢不等於財務同步完成
   * @param category        分類（連線狀態查詢／帳戶／庫存查詢／委託與交易資訊查詢／個股報價查詢／歷史成交查詢／行情查詢／即時推播）
   * @param name             中文名稱
   * @param sdkReference     SDK 方法或頻道的完整可查證路徑（如 {@code sdk.accounting.bank_remain}），已串接與未串接皆必填
   * @param httpEndpoint     本系統實際 adapter 的 {@code method + path}；僅預檢仍可列入口，無入口才為空，不得虛構
   * @param description      白話唯讀用途說明
   * @param consumer         實際 consumer 與能力限制；尚無呼叫端時為「－（尚未串接）」
   * @param requestSummary   請求參數摘要
   * @param responseSummary  回應內容摘要
   */
  public record FubonApiInfoDto(
          boolean connected,
          String category,
          String name,
          String sdkReference,
          String httpEndpoint,
          String description,
          String consumer,
          String requestSummary,
          String responseSummary
  ) {
  }
  ```

- [ ] **386.2 重寫 BFF Controller 與人工維護靜態清單（52 筆）。** 修改 `bff/src/main/java/com/steven/assets/bff/fubonapi/FubonApiInfoBffController.java`：
  ```java
  package com.steven.assets.bff.fubonapi;

  import org.springframework.web.bind.annotation.GetMapping;
  import org.springframework.web.bind.annotation.RequestMapping;
  import org.springframework.web.bind.annotation.RestController;

  import java.util.List;

  /**
   * FubonApiView 專屬 BFF（「系統資訊」分組，Requirement 121）。
   *
   * <p>回傳富邦官方 SDK（{@code fubon_neo} 2.2.9）{@code accounting}／{@code stock}／
   * {@code marketdata} 命名空間中已驗證存在、且確認為唯讀查詢的全部方法的**人工維護靜態清單**
   * （52 筆：15 已串接、37 未串接），並標示每一筆是否已被本系統實際串接。清單來源是在
   * {@code fubon-broker-service} 容器內以 Python 內省 SDK 物件取得的真實方法與 docstring，
   * 詳細盤點步驟見 {@code spec/tasks/t386_fubon_api_documentation_view.md} 的「盤點方法」段落。
   *
   * <p><b>本頁一律排除任何下單／改單／刪單／批次委託／條件單／預約圈存／匯撥申請類方法
   * （如 {@code place_order}、{@code cancel_order}、{@code modify_price}、
   * {@code batch_place_order}、{@code single_condition}、{@code reserve_cash}、
   * {@code transfer_stock} 等），不論是否標示未串接——本頁的唯一目的是唯讀查詢能力盤點，
   * 絕不得成為交易功能的暗示或索引。</b>{@code sdk.futopt}／{@code sdk.futopt_accounting}
   * （期貨選擇權）命名空間整體不在本頁範圍。
   *
   * <p><b>維護提醒：{@code fubon-broker-service} 新增、刪除或修改對外 endpoint，或富邦 SDK
   * 版本升級改變 {@code accounting}／{@code stock}／{@code marketdata} 命名空間方法時，
   * 務必依上述盤點步驟重新核對並更新下方 {@link #APIS} 清單，否則本頁會與實際 SDK 能力漂移。</b>
   *
   * <p>本頁純資訊展示、不在 runtime import 或呼叫富邦 SDK，controller 不注入任何 Repository、
   * Service、WebClient 或對 {@code fubon-broker-service} 的呼叫。
   */
  @RestController
  @RequestMapping("/api/bff/fubon-api")
  public class FubonApiInfoBffController {

      private static final String RO = "；純查詢，不影響券商端任何狀態。";
      private static final String NOT_CONNECTED = "－（尚未串接）";
      private static final String NO_HTTP = "";
      private static final String NO_SDK_DOC =
              "SDK 未提供可查證的參數／回傳說明（docstring 為空），僅能確認此方法存在於 sdk.stock 命名空間。";

      /** 富邦 SDK 唯讀查詢能力全量盤點（52 筆：15 已串接、37 未串接）。 */
      private static final List<FubonApiInfoDto> APIS = List.of(

              // ===== 連線狀態查詢（2，全數已串接）=====
              new FubonApiInfoDto(true, "連線狀態查詢", "服務健康檢查",
                      "（本服務自建 meta 端點，非 SDK 方法）", "GET /internal/health",
                      "查詢 fubon-broker-service 進程存活狀態與目前設定狀態（尚未設定／已就緒／設定有誤），"
                              + "供 Docker 健康檢查判斷容器是否正常運作" + RO,
                      "Docker Compose 健康檢查",
                      "無請求參數（GET 無 body）",
                      "{status, configState, sdkVersion, platform}"),
              new FubonApiInfoDto(true, "連線狀態查詢", "憑證設定狀態查詢",
                      "（本服務自建 meta 端點，非 SDK 方法）", "GET /internal/config",
                      "回報富邦 SDK 憑證、憑證密碼與內部服務金鑰是否已正確掛載，以及帳戶庫存查詢／台股報價／"
                              + "大盤指數串流三項唯讀能力目前是否可用；只回報是否就緒，不回傳任何憑證或密碼本身" + RO,
                      "目前尚無服務呼叫，屬診斷保留端點",
                      "無請求參數（GET 無 body，需 X-Internal-Service-Token header）",
                      "{configState, presence:{...}(bool 各項), capabilities:{...}(bool 各項)}"),

              // ===== 帳戶／庫存查詢（7：sdk.accounting.*，3 已串接）=====
              new FubonApiInfoDto(true, "帳戶／庫存查詢", "現股庫存查詢",
                      "sdk.accounting.inventories", "POST /internal/portfolio/read",
                      "查詢富邦證券帳戶目前的現股庫存張數、可賣張數與零股明細" + RO,
                      "business-services（富邦庫存同步排程，需另啟用設定才會執行）",
                      "account（登入後 selected account，由服務端自動帶入，呼叫端無需傳遞）",
                      "Inventory 陣列，每筆含 date／account／branch_no／stock_no／tradable_qty／sell_qty／"
                              + "sell_filled_qty／sell_value／odd:{lastday_qty,buy_qty,buy_filled_qty,buy_value,...}"),
              new FubonApiInfoDto(true, "帳戶／庫存查詢", "未實現損益查詢",
                      "sdk.accounting.unrealized_gains_and_loses", "POST /internal/portfolio/read",
                      "查詢富邦證券帳戶目前的未實現損益明細" + RO,
                      "business-services（富邦庫存同步排程，需另啟用設定才會執行）",
                      "account（同上，由服務端自動帶入）",
                      "UnrealizedData 陣列，每筆含 date／account／branch_no／stock_no／buy_sell／order_type／"
                              + "cost_price／tradable_qty／today_qty／unrealized_profit／unrealized_loss"),
              new FubonApiInfoDto(true, "帳戶／庫存查詢", "交割銀行餘額查詢",
                      "sdk.accounting.bank_remain", "POST /internal/bank-balance/read",
                      "唯讀查詢交割銀行餘額，將通過身分、日期與金額驗證的台幣餘額更新到最新快照的台北富邦銀行證券戶，"
                              + "與快照總額同一交易提交；零餘額照寫" + RO,
                      "business-services（每日 08:00／09:30／14:00／22:00，需另啟用設定；"
                              + "手動 POST /internal/brokers/fubon/bank-balance-sync，dryRun 預設 true）",
                      "無 body／帳戶 selector；selected account 由 adapter 決定，需 internal token",
                      "queryDate／observedAt／accountFingerprint（HMAC 前 24 hex）／currency=TWD／balance／availableBalance；"
                              + "金額為非負 decimal 字串，不回傳原帳號或分行"),

              new FubonApiInfoDto(false, "帳戶／庫存查詢", "信用維持率查詢",
                      "sdk.accounting.maintenance", NO_HTTP,
                      "查詢帳戶整戶維持率與各筆融資融券部位的維持率明細" + RO, NOT_CONNECTED,
                      "account",
                      "MaintenanceData{date, branch_no, account, maintenance_summary:{margin_value,"
                              + "shortsell_value,shortsell_margin,collateral,margin_loan_amt,maintenance_ratio}, "
                              + "maintenance_detail:[{stock_no,order_no,order_type,quantity,price,cost_price,"
                              + "market_value,shortsell_margin,collateral,margin_loan_amt,maintenance_ratio,...}]}"),
              new FubonApiInfoDto(false, "帳戶／庫存查詢", "應收付交割金額查詢",
                      "sdk.accounting.query_settlement", "POST /internal/settlement/read",
                      "唯讀解析 3d 交割款觀察；來源完整範圍待核實，目前只預檢、不寫買股待付款／賣股待收款，財務同步尚未完成" + RO,
                      "business-services（每日 08:00／13:45／19:30／22:00；"
                              + "POST /internal/brokers/fubon/settlement-sync，dryRun 預設 true；"
                              + "SETTLEMENT_SCOPE_UNVERIFIED，無 writer／寫鎖）",
                      "無 body／帳戶 selector；adapter 固定 query_settlement(selected, 3d)，需 internal token",
                      "queryDate／observedAt／accountFingerprint／coverageStatus=UNVERIFIED／"
                              + "reason=MISSING_SETTLEMENT_RANGE_CONTRACT／details；含 sourceQueryDate、settlementDate、TWD、"
                              + "12 項 signed 整數字串與 AVAILABLE／NO_DATA_OBSERVED，無原帳號"),

              new FubonApiInfoDto(false, "帳戶／庫存查詢", "已實現損益明細查詢",
                      "sdk.accounting.realized_gains_and_loses", "POST /internal/realized-gains/read",
                      "唯讀解析已實現損益，逐筆身分、淨收款及取得成本來源待核實；目前不新增或覆寫損益，財務同步尚未完成" + RO,
                      "business-services（每日 08:00／13:45／19:30／22:00；"
                              + "POST /internal/brokers/fubon/realized-gain-sync，dryRun 預設 true；"
                              + "IDENTITY_UNVERIFIED，可變 FUBON_SYNC ledger 不能解除限制）",
                      "無 body／帳戶 selector；selected account 由 adapter 決定，需 internal token",
                      "queryDate／observedAt／accountFingerprint／rows；每列 stockNo、buySell=Sell、orderType=Stock、"
                              + "filledQty、filledPrice、realizedProfit、realizedLoss、sourceDate；零損益合法，無原帳號"),

              new FubonApiInfoDto(false, "帳戶／庫存查詢", "已實現損益彙總查詢",
                      "sdk.accounting.realized_gains_and_loses_summary", NO_HTTP,
                      "查詢已實現損益依標的彙總結果" + RO, NOT_CONNECTED,
                      "account",
                      "RealizedSummary 陣列，每筆含 start_date／end_date／branch_no／account／stock_no／"
                              + "buy_sell／order_type／filled_qty／filled_avg_price／realized_profit_and_loss"),

              // ===== 委託與交易資訊查詢（18：sdk.stock.*，皆未串接）=====
              new FubonApiInfoDto(false, "委託與交易資訊查詢", "當日委託回報查詢",
                      "sdk.stock.get_order_results", NO_HTTP,
                      "查詢當日所有委託（在途、已刪、已成交）" + RO, NOT_CONNECTED,
                      "account",
                      "OrderResult 陣列，每筆含 function_type／date／seq_no／branch_no 等委託回報欄位（docstring 未列完整欄位）"),
              new FubonApiInfoDto(false, "委託與交易資訊查詢", "當日委託回報明細查詢",
                      "sdk.stock.get_order_results_detail", NO_HTTP,
                      "查詢當日所有委託並含委託處理過程明細" + RO, NOT_CONNECTED,
                      "account",
                      "OrderResult 陣列（含處理過程），欄位同「當日委託回報查詢」並附加處理過程資訊"),
              new FubonApiInfoDto(false, "委託與交易資訊查詢", "委託歷史查詢",
                      "sdk.stock.order_history", NO_HTTP,
                      "查詢指定區間內的委託歷史紀錄" + RO, NOT_CONNECTED,
                      "account、start_date、end_date（選填，留空預設為今日）",
                      "OrderResult 陣列，欄位同「當日委託回報查詢」"),
              new FubonApiInfoDto(false, "委託與交易資訊查詢", "批次委託清單查詢",
                      "sdk.stock.batch_order_lists", NO_HTTP,
                      "查詢帳戶的批次委託紀錄清單" + RO, NOT_CONNECTED,
                      "account",
                      "BatchResult 陣列，每筆含 function_type／date／branch_no／account／batch_seq_no"),
              new FubonApiInfoDto(false, "委託與交易資訊查詢", "批次委託執行狀態查詢",
                      "sdk.stock.batch_order_detail", NO_HTTP,
                      "查詢指定批次委託清單的執行狀態" + RO, NOT_CONNECTED,
                      "account、批次委託清單（BatchResult，作為查詢鍵）",
                      "BatchResult 陣列，每筆含 is_success／message 與逐筆執行結果"),
              new FubonApiInfoDto(false, "委託與交易資訊查詢", "條件單查詢",
                      "sdk.stock.get_condition_order", NO_HTTP,
                      "查詢目前有效的條件單" + RO, NOT_CONNECTED, NO_SDK_DOC, NO_SDK_DOC),
              new FubonApiInfoDto(false, "委託與交易資訊查詢", "條件單明細查詢",
                      "sdk.stock.get_condition_order_by_id", NO_HTTP,
                      "依 id 查詢單一條件單明細" + RO, NOT_CONNECTED, NO_SDK_DOC, NO_SDK_DOC),
              new FubonApiInfoDto(false, "委託與交易資訊查詢", "條件單歷史查詢",
                      "sdk.stock.get_condition_history", NO_HTTP,
                      "查詢條件單歷史紀錄" + RO, NOT_CONNECTED, NO_SDK_DOC, NO_SDK_DOC),
              new FubonApiInfoDto(false, "委託與交易資訊查詢", "當沖條件單明細查詢",
                      "sdk.stock.get_condition_daytrade_by_id", NO_HTTP,
                      "依 id 查詢當沖條件單明細" + RO, NOT_CONNECTED, NO_SDK_DOC, NO_SDK_DOC),
              new FubonApiInfoDto(false, "委託與交易資訊查詢", "停損停利單查詢",
                      "sdk.stock.get_trail_order", NO_HTTP,
                      "查詢目前有效的停損停利單" + RO, NOT_CONNECTED, NO_SDK_DOC, NO_SDK_DOC),
              new FubonApiInfoDto(false, "委託與交易資訊查詢", "停損停利單歷史查詢",
                      "sdk.stock.get_trail_history", NO_HTTP,
                      "查詢停損停利單歷史紀錄" + RO, NOT_CONNECTED, NO_SDK_DOC, NO_SDK_DOC),
              new FubonApiInfoDto(false, "委託與交易資訊查詢", "分時分量單查詢",
                      "sdk.stock.get_time_slice_order", NO_HTTP,
                      "查詢目前有效的分時分量單" + RO, NOT_CONNECTED, NO_SDK_DOC, NO_SDK_DOC),
              new FubonApiInfoDto(false, "委託與交易資訊查詢", "融資融券額度查詢",
                      "sdk.stock.margin_quota", NO_HTTP,
                      "查詢指定股票的融資融券與借券賣出可用額度" + RO, NOT_CONNECTED,
                      "account、stock_no",
                      "MarginShortQuota{stock_no,date,shortsell_orig_quota,shortsell_tradable_quota,"
                              + "margin_orig_quota,margin_tradable_quota,margin_ratio,short_ratio}"),
              new FubonApiInfoDto(false, "委託與交易資訊查詢", "當沖額度與預收款資訊查詢",
                      "sdk.stock.daytrade_and_stock_info", NO_HTTP,
                      "查詢指定股票的當沖額度與預收款資訊" + RO, NOT_CONNECTED,
                      "account、stock_no",
                      "DayTradeStockInfo{stock_no,date,daytrade_orig_quota,daytrade_tradable_quota,"
                              + "precollect_single,precollect_accumulate,status}"),
              new FubonApiInfoDto(false, "委託與交易資訊查詢", "圈券查詢",
                      "sdk.stock.query_reserved_stocks", NO_HTTP,
                      "查詢圈券（股票）紀錄" + RO, NOT_CONNECTED,
                      "SDK docstring 僅有中文簡述「圈券查詢（raw 模式僅回大洲原欄位）」，未列出明確參數簽章",
                      "SDK docstring 說明合併版（含 available_share／name）將於官方 follow-up 提供，"
                              + "目前僅能確認回傳大洲原始欄位，無完整欄位清單"),
              new FubonApiInfoDto(false, "委託與交易資訊查詢", "圈款（預繳）紀錄查詢",
                      "sdk.stock.query_reserves", NO_HTTP,
                      "查詢圈款（預繳）紀錄" + RO, NOT_CONNECTED,
                      "SDK docstring 僅一句中文簡述「圈款（預繳）紀錄查詢」，未列出明確參數簽章",
                      "SDK 未提供可查證的回傳欄位說明"),
              new FubonApiInfoDto(false, "委託與交易資訊查詢", "匯撥可用庫存查詢",
                      "sdk.stock.query_transfer_inventory", NO_HTTP,
                      "查詢可用於匯撥的庫存" + RO, NOT_CONNECTED,
                      "SDK docstring 僅一句中文簡述「匯撥可用庫存查詢」，未列出明確參數簽章",
                      "SDK 未提供可查證的回傳欄位說明"),
              new FubonApiInfoDto(false, "委託與交易資訊查詢", "匯撥紀錄查詢",
                      "sdk.stock.query_transfers", NO_HTTP,
                      "查詢匯撥申請紀錄" + RO, NOT_CONNECTED,
                      "start_date／end_date／stock_no（依 docstring 說明，皆為選填過濾條件）",
                      "SDK 未提供可查證的回傳欄位說明"),

              // ===== 個股報價查詢（2：sdk.stock.query_symbol_*，交易命名空間版本，皆未串接）=====
              new FubonApiInfoDto(false, "個股報價查詢", "個股即時報價查詢（交易帳號版）",
                      "sdk.stock.query_symbol_quote", NO_HTTP,
                      "查詢單一個股即時報價（透過交易帳號命名空間，與已串接的行情 REST 查詢為不同物件）" + RO,
                      NOT_CONNECTED,
                      "account、symbol、market_type（選填，預設 Common）",
                      "SymbolQuote{market,symbol,istib_or_psb,market_type,status,reference_price,unit,"
                              + "update_time,limitup_price,limitdown_price,open_price,high_price,low_price,"
                              + "last_price,total_volume,total_transaction,total_value,last_size,last_transaction,"
                              + "last_value,bid_price,bid_volume,ask_price,ask_volume}"),
              new FubonApiInfoDto(false, "個股報價查詢", "多檔個股快照查詢（交易帳號版）",
                      "sdk.stock.query_symbol_snapshot", NO_HTTP,
                      "查詢多檔個股快照資訊（透過交易帳號命名空間）" + RO, NOT_CONNECTED,
                      "account、market_type（選填）、stock_types（選填，股票類型清單，預設 [Stock]）",
                      "SymbolQuote 陣列，欄位同「個股即時報價查詢（交易帳號版）」"),

              // ===== 歷史成交查詢（1，已串接）=====
              new FubonApiInfoDto(true, "歷史成交查詢", "已成交紀錄查詢",
                      "sdk.stock.filled_history", "POST /internal/trades/read",
                      "查詢最長 7 天區間內帳戶已成交明細，供成交紀錄同步排程比對並自動新增系統尚未記錄的交易" + RO,
                      "business-services（富邦成交同步排程，需另啟用設定才會執行）",
                      "account、start_date、end_date（本系統限制區間 ≤ 7 天）",
                      "FilledData 陣列，含 date／filled_no／filled_avg_price／filled_qty／filled_price／"
                              + "order_type／filled_time 等成交欄位"),

              // ===== 行情查詢（20：marketdata.rest_client.stock.*，7 已串接）=====
              new FubonApiInfoDto(true, "行情查詢", "個股即時報價與最佳五檔",
                      "marketdata.rest_client.stock.intraday.quote", "POST /internal/market-data/tw-quotes",
                      "逐檔查詢台股即時成交價、漲跌、成交量與委買委賣最佳五檔，供交易雷達即時報價與庫存估值使用" + RO,
                      "business-services（庫存估值）／external-materials-service（台股即時報價）",
                      "symbol（路徑參數）",
                      "本系統已定義完整 typed DTO（見既有 quotes.py 正規化邏輯），含成交價／漲跌／五檔委買委賣等欄位"),
              new FubonApiInfoDto(true, "行情查詢", "商品代碼清單查詢",
                      "marketdata.rest_client.stock.intraday.tickers",
                      "GET /internal/market-data/taiex-index/stream",
                      "查詢可用商品（股票）代碼清單" + RO,
                      "external-materials-service（間接：大盤指數即時串流啟動時，"
                              + "由 sdk_gateway.verify_taiex_index_symbol() 呼叫此方法核對設定的指數代碼"
                              + "是否為官方有效代碼，非獨立對外 endpoint）",
                      "type=\"INDEX\"、exchange=\"TWSE\"（本系統呼叫時固定帶入此組參數，"
                              + "wrapper 本身為 thin proxy 未在程式碼中限定欄位名稱）",
                      "商品代碼清單，本系統只用來核對設定的指數代碼是否存在於回傳清單中，"
                              + "不解析其餘欄位；完整欄位以官方回應為準，此 wrapper 未在程式碼中定義 schema"),
              new FubonApiInfoDto(false, "行情查詢", "單一商品基本資料查詢",
                      "marketdata.rest_client.stock.intraday.ticker", NO_HTTP,
                      "查詢單一商品（股票）基本資料" + RO, NOT_CONNECTED,
                      "symbol（路徑參數）＋選填 query 參數",
                      "商品基本資料，通常含名稱／產業別／市場別等，實際完整欄位以官方回應為準，"
                              + "此 wrapper 未在程式碼中定義 schema"),
              new FubonApiInfoDto(false, "行情查詢", "個股當日分鐘K線查詢",
                      "marketdata.rest_client.stock.intraday.candles", NO_HTTP,
                      "查詢個股當日盤中分鐘K線" + RO, NOT_CONNECTED,
                      "symbol（路徑參數）＋選填 query 參數",
                      "分鐘K線陣列，通常含開高低收與成交量，實際完整欄位以官方回應為準，此 wrapper 未在程式碼中定義 schema"),
              new FubonApiInfoDto(false, "行情查詢", "個股當日逐筆成交明細查詢",
                      "marketdata.rest_client.stock.intraday.trades", NO_HTTP,
                      "查詢個股當日逐筆成交明細" + RO, NOT_CONNECTED,
                      "symbol（路徑參數）＋選填 query 參數",
                      "逐筆成交明細陣列，通常含成交時間／價格／量，實際完整欄位以官方回應為準，"
                              + "此 wrapper 未在程式碼中定義 schema"),
              new FubonApiInfoDto(false, "行情查詢", "個股當日分價量查詢",
                      "marketdata.rest_client.stock.intraday.volumes", NO_HTTP,
                      "查詢個股當日各價位累計成交量" + RO, NOT_CONNECTED,
                      "symbol（路徑參數）＋選填 query 參數",
                      "分價量陣列，通常含價位與對應成交量，實際完整欄位以官方回應為準，此 wrapper 未在程式碼中定義 schema"),
              new FubonApiInfoDto(false, "行情查詢", "個股歷史日K線查詢",
                      "marketdata.rest_client.stock.historical.candles", NO_HTTP,
                      "查詢個股歷史日K線" + RO, NOT_CONNECTED,
                      "symbol（路徑參數）＋選填 query 參數（如日期區間）",
                      "歷史日K線陣列，通常含開高低收與成交量，實際完整欄位以官方回應為準，此 wrapper 未在程式碼中定義 schema"),
              new FubonApiInfoDto(false, "行情查詢", "個股歷史統計資料查詢",
                      "marketdata.rest_client.stock.historical.stats", NO_HTTP,
                      "查詢個股歷史統計資料" + RO, NOT_CONNECTED,
                      "symbol（路徑參數）＋選填 query 參數",
                      "歷史統計資料，實際完整欄位以官方回應為準，此 wrapper 未在程式碼中定義 schema"),
              new FubonApiInfoDto(false, "行情查詢", "市場即時報價總覽查詢",
                      "marketdata.rest_client.stock.snapshot.quotes", NO_HTTP,
                      "查詢指定市場的即時報價總覽" + RO, NOT_CONNECTED,
                      "market（路徑參數）＋選填 query 參數",
                      "市場報價總覽陣列，實際完整欄位以官方回應為準，此 wrapper 未在程式碼中定義 schema"),
              new FubonApiInfoDto(false, "行情查詢", "市場漲跌幅排行查詢",
                      "marketdata.rest_client.stock.snapshot.movers", NO_HTTP,
                      "查詢指定市場的漲跌幅排行" + RO, NOT_CONNECTED,
                      "market（路徑參數）＋選填 query 參數",
                      "漲跌幅排行陣列，實際完整欄位以官方回應為準，此 wrapper 未在程式碼中定義 schema"),
              new FubonApiInfoDto(false, "行情查詢", "市場成交量值排行查詢",
                      "marketdata.rest_client.stock.snapshot.actives", NO_HTTP,
                      "查詢指定市場的成交量值排行" + RO, NOT_CONNECTED,
                      "market（路徑參數）＋選填 query 參數",
                      "成交量值排行陣列，實際完整欄位以官方回應為準，此 wrapper 未在程式碼中定義 schema"),
              new FubonApiInfoDto(false, "行情查詢", "個股簡單移動平均線查詢",
                      "marketdata.rest_client.stock.technical.sma", NO_HTTP,
                      "查詢個股簡單移動平均線（SMA）指標" + RO, NOT_CONNECTED,
                      "symbol（路徑參數）＋選填 query 參數（如週期）",
                      "SMA 指標數列，實際完整欄位以官方回應為準，此 wrapper 未在程式碼中定義 schema"),
              new FubonApiInfoDto(false, "行情查詢", "個股相對強弱指標查詢",
                      "marketdata.rest_client.stock.technical.rsi", NO_HTTP,
                      "查詢個股相對強弱指標（RSI）" + RO, NOT_CONNECTED,
                      "symbol（路徑參數）＋選填 query 參數",
                      "RSI 指標數列，實際完整欄位以官方回應為準，此 wrapper 未在程式碼中定義 schema"),
              new FubonApiInfoDto(true, "行情查詢", "個股 KD 隨機指標查詢",
                      "marketdata.rest_client.stock.technical.kdj", "POST /internal/market-data/technical-indicators/read",
                      "查詢今日交易雷達台股的 KDJ(9,3,3) 日指標，保存在獨立 Redis cache，最長 7 天；不改本地技術計算或雷達評分" + RO,
                      "external-materials-service（交易日 13:40，需另啟用設定；"
                              + "POST /internal/technical-indicators/fubon-sync；純讀 GET /internal/technical-indicators/fubon-cache?symbol=...）",
                      "symbol／from／to；timeframe=D，查詢區間為 queryDate 前 120 天至 queryDate，KDJ(9,3,3)",
                      "symbol／market／provider／queryFrom／queryTo／observedAt；kdj／macd／bb 各含 status／reason／parameters／sourceDate／sourceTimestamp／payload；"
                              + "kdj payload 為 k／d／j，有界 decimal 字串；sourceTimestamp 未核實為 null"),

              new FubonApiInfoDto(true, "行情查詢", "個股 MACD 指標查詢",
                      "marketdata.rest_client.stock.technical.macd", "POST /internal/market-data/technical-indicators/read",
                      "查詢今日交易雷達台股的 MACD(12,26,9) 日指標，保存在獨立 Redis cache，最長 7 天；不改本地技術計算或雷達評分" + RO,
                      "external-materials-service（交易日 13:40，需另啟用設定；"
                              + "POST /internal/technical-indicators/fubon-sync；純讀 GET /internal/technical-indicators/fubon-cache?symbol=...）",
                      "symbol／from／to；timeframe=D，查詢區間為 queryDate 前 120 天至 queryDate，MACD(12,26,9)",
                      "symbol／market／provider／queryFrom／queryTo／observedAt；kdj／macd／bb 各含 status／reason／parameters／sourceDate／sourceTimestamp／payload；"
                              + "macd payload 為 macdLine／signalLine，有界 decimal 字串；不捏造 histogram，sourceTimestamp 未核實為 null"),

              new FubonApiInfoDto(true, "行情查詢", "個股布林通道查詢",
                      "marketdata.rest_client.stock.technical.bb", "POST /internal/market-data/technical-indicators/read",
                      "查詢今日交易雷達台股的 BB(period=20) 日指標，保存在獨立 Redis cache，最長 7 天；不改本地技術計算或雷達評分" + RO,
                      "external-materials-service（交易日 13:40，需另啟用設定；"
                              + "POST /internal/technical-indicators/fubon-sync；純讀 GET /internal/technical-indicators/fubon-cache?symbol=...）",
                      "symbol／from／to；timeframe=D，查詢區間為 queryDate 前 120 天至 queryDate，BB(period=20)",
                      "symbol／market／provider／queryFrom／queryTo／observedAt；kdj／macd／bb 各含 status／reason／parameters／sourceDate／sourceTimestamp／payload；"
                              + "bb payload 為 upper／middle／lower，有界 decimal 字串；sourceTimestamp 未核實為 null"),

              new FubonApiInfoDto(false, "行情查詢", "減資／除權息等資本變動查詢",
                      "marketdata.rest_client.stock.corporate_actions.capital_changes", NO_HTTP,
                      "查詢個股減資、除權息等資本變動事件" + RO, NOT_CONNECTED,
                      "選填 query 參數（wrapper 未在程式碼中限定欄位名稱）",
                      "資本變動事件陣列，實際完整欄位以官方回應為準，此 wrapper 未在程式碼中定義 schema"),
              new FubonApiInfoDto(true, "行情查詢", "股利政策查詢",
                      "marketdata.rest_client.stock.corporate_actions.dividends", "POST /internal/market-data/dividends/read",
                      "日期批次取得並只處理雷達交集的除權息現金股利證據；PARTIAL，未包含減資／未核實配股金額，"
                              + "併入既有股利證據，不以不完整結果取消既有事件" + RO,
                      "external-materials-service（交易日 09:00／13:30，需另啟用設定；"
                              + "POST /internal/dividend/fubon-sync，dryRun 預設 true；capital_changes 仍未串接）",
                      "symbols（今日交易雷達台股）、from／to；SDK 只傳 start_date／end_date 做一次日期批次查詢",
                      "queryDate／observedAt／scopeFrom／scopeTo／provider=FUBON_SDK／rows；逐檔 PARTIAL／FAILED、usable 與現金股利 events，"
                              + "sourceAvailableAt 未知為 null，不把未知配股當零"),

              new FubonApiInfoDto(false, "行情查詢", "新股上市櫃申請中名單查詢",
                      "marketdata.rest_client.stock.corporate_actions.listing_applicants", NO_HTTP,
                      "查詢新股上市櫃申請中名單" + RO, NOT_CONNECTED,
                      "選填 query 參數",
                      "申請中名單陣列，實際完整欄位以官方回應為準，此 wrapper 未在程式碼中定義 schema"),
              new FubonApiInfoDto(true, "行情查詢", "ETF 成分股持股明細查詢",
                      "marketdata.rest_client.stock.ownership.etf_holdings",
                      "POST /internal/market-data/etf-holdings",
                      "逐檔查詢台股 ETF 成分股持股明細，正規化市場資料落地保存，股票分析直接讀取相同資料" + RO,
                      "business-services（富邦 ETF 成分股持股同步排程，交易日 08:50／15:30，"
                              + "範圍為今日交易雷達台股 ETF，需另啟用設定才會執行）",
                      "codes（1–50 個不重複的台股 ETF 代碼，僅查今日交易雷達範圍）",
                      "batchId＋holdings 陣列，每筆含 stockCode／status（SUCCESS｜FAILURE）／reason／"
                              + "rawResponseJson（歷史欄名；版本 1 正規化 JSON 字串，含 schemaVersion／stockCode／"
                              + "sourceDate 來源日／holdings。成分欄位 stockCode／stockName／weight／shares；"
                              + "數值為 decimal 字串、shares 可 null，合法無股票成分可有 null sourceDate；"
                              + "不包含任意 SDK raw 或帳戶資料）"),

              // ===== 即時推播（2：marketdata.websocket_client.stock，全數已串接）=====
              new FubonApiInfoDto(true, "即時推播", "大盤指數即時串流",
                      "marketdata.websocket_client.stock（channel=\"indices\"）",
                      "GET /internal/market-data/taiex-index/stream",
                      "以伺服器推送（SSE）訂閱富邦官方 WebSocket 的台股加權指數即時點數，"
                              + "供系統持久化大盤走勢，需另啟用設定才會運作" + RO,
                      "external-materials-service",
                      "無請求參數（SSE GET，內部已固定訂閱 channel=\"indices\"）",
                      "SSE event，含 symbol／exchange／type／index／time"),
              new FubonApiInfoDto(true, "即時推播", "個股即時推播",
                      "marketdata.websocket_client.stock（channel=\"aggregates\"）",
                      "GET /internal/market-data/stock-push/stream",
                      "Normal mode aggregates 僅取 lastTrade 實際成交價與微秒時間，驗證當日盤中、範圍與嚴格較新後更新即時報價；"
                              + "不使用試撮、不產生官方收盤價" + RO,
                      "external-materials-service（POST /internal/market-data/stock-push/subscriptions 每 30 秒更新雷達清單，"
                              + "最長 120 秒 lease；內網 SSE consumer，需另啟用設定）",
                      "subscriptions 傳 symbols（最多 300，空陣列撤銷）；SSE GET 無 body，台北 09:00 ≤ time < 13:30 且交易日已知",
                      "SSE event=stock-price，id=symbol:tradeTimeMicros；symbol／market／exchange／type／sourceDate／tradeTimeMicros／tradeSize／"
                              + "price／previousClose／openPrice／highPrice／lowPrice／name／source=FUBON_WS_AGGREGATES；buyPrice、sellPrice、volume 為 null，無原 SDK 包或帳戶資料")

      );

      /** GET /api/bff/fubon-api —— 回傳富邦 SDK 唯讀查詢能力全量盤點（52 筆靜態資料）。 */
      @GetMapping
      public List<FubonApiInfoDto> list() {
          return APIS;
      }
  }
  ```
  Controller 不得新增 `permitAll`、不得依賴或觸及 DB、Redis、WebClient、business service、`fubon-broker-service`、排程、broker、交易、user、帳戶或 tenant，也不得在 runtime import 或呼叫富邦 SDK；此端點沿用既有預設 `anyExchange().authenticated()`（見 `bff/src/main/java/com/steven/assets/bff/config/SecurityConfig.java`），不得新增獨立安全設定。不得新增、修改或移除 `docs/openapi/docker-external-api.yaml` 的 `paths`、9090 API gateway allowlist、Tailscale Serve 或 frontend nginx 9090 allowlist——本端點不對外，僅供登入後前端呼叫。**逐一核對 `APIS` 常數不得出現「盤點方法」段落列出的 22 個下單／改單／刪單類方法名稱，也不得出現 `futopt`／`futopt_accounting` 字樣。**

- [ ] **386.3 重寫 BFF 測試。** 修改 `bff/src/test/java/com/steven/assets/bff/fubonapi/` 底下 `FubonApiInfoBffController` 的單元測試，斷言：
  - `list()` 回傳恰好 52 筆；
  - `connected == true` 的筆數恰為 15（歷史 Task386 為8，已完成 ETF 基線為9），且其 `(sdkReference, httpEndpoint)` 集合恰為：
    `("（本服務自建 meta 端點，非 SDK 方法）","GET /internal/health")`、
    `("（本服務自建 meta 端點，非 SDK 方法）","GET /internal/config")`、
    `("sdk.accounting.inventories","POST /internal/portfolio/read")`、
    `("sdk.accounting.unrealized_gains_and_loses","POST /internal/portfolio/read")`、
    `("sdk.stock.filled_history","POST /internal/trades/read")`、
    `("marketdata.rest_client.stock.intraday.quote","POST /internal/market-data/tw-quotes")`、
    `("marketdata.rest_client.stock.intraday.tickers","GET /internal/market-data/taiex-index/stream")`、
    `("marketdata.rest_client.stock.ownership.etf_holdings","POST /internal/market-data/etf-holdings")`、
    `("marketdata.websocket_client.stock（channel=\"indices\"）","GET /internal/market-data/taiex-index/stream")`、
    `("sdk.accounting.bank_remain","POST /internal/bank-balance/read")`、
    `("marketdata.rest_client.stock.corporate_actions.dividends","POST /internal/market-data/dividends/read")`、
    `("marketdata.rest_client.stock.technical.kdj","POST /internal/market-data/technical-indicators/read")`、
    `("marketdata.rest_client.stock.technical.macd","POST /internal/market-data/technical-indicators/read")`、
    `("marketdata.rest_client.stock.technical.bb","POST /internal/market-data/technical-indicators/read")`、
    `("marketdata.websocket_client.stock（channel=\"aggregates\"）","GET /internal/market-data/stock-push/stream")`；
  - `connected == false` 的筆數恰為 37；其中只有 `query_settlement` 與 `realized_gains_and_loses` 可列出已存在的安全解析 adapter 路由，consumer 必須明示無 writer／寫鎖及來源未核實，不能宣稱財務同步可用。其餘35筆 `httpEndpoint` 仍為空字串；`capital_changes` 保持未串接。原全部財務目標完成後才是17已串接／35未串接，不得預填；
  - 每筆 `category` 屬於七類之一：`連線狀態查詢`、`帳戶／庫存查詢`、`委託與交易資訊查詢`、`個股報價查詢`、`歷史成交查詢`、`行情查詢`、`即時推播`；依類別統計筆數恰為 `{連線狀態查詢:2, 帳戶／庫存查詢:7, 委託與交易資訊查詢:18, 個股報價查詢:2, 歷史成交查詢:1, 行情查詢:20, 即時推播:2}`；
  - 每筆 `sdkReference`／`name`／`description`／`consumer`／`requestSummary`／`responseSummary` 皆非空白字串；
  - 每筆 `description` 皆包含「純查詢」或「不影響券商端」字樣；
  - 機械核對「盤點方法」段落排除的 22 個下單／改單／刪單類方法完全沒有進入清單。**必須用完整方法名的精確相等比對，不得用子字串 `contains`／`doesNotContain`**——`sdk.stock` 命名空間裡合法收錄的查詢方法 `get_time_slice_order` 本身就以子字串包含 `time_slice_order`，若對 52 筆 `sdkReference` 逐一做 `doesNotContain("time_slice_order")` 會誤判這筆合法查詢方法為違規、測試必定紅燈。正確寫法：先建立禁止清單 `Set<String> FORBIDDEN = Set.of("sdk.stock.place_order", "sdk.stock.cancel_order", "sdk.stock.modify_price", "sdk.stock.modify_quantity", "sdk.stock.batch_place_order", "sdk.stock.batch_cancel_order", "sdk.stock.batch_modify_price", "sdk.stock.batch_modify_quantity", "sdk.stock.cancel_condition_orders", "sdk.stock.single_condition", "sdk.stock.single_condition_day_trade", "sdk.stock.single_condition_stop", "sdk.stock.multi_condition", "sdk.stock.multi_condition_day_trade", "sdk.stock.multi_condition_stop", "sdk.stock.make_modify_price_obj", "sdk.stock.make_modify_quantity_obj", "sdk.stock.reserve_cash", "sdk.stock.reserve_stock", "sdk.stock.transfer_stock", "sdk.stock.time_slice_order", "sdk.stock.trail_profit")`（共 22 個完整字串），再逐一斷言 52 筆的 `sdkReference` 沒有任何一筆與 `FORBIDDEN` 集合裡的任一字串**完全相等**（`assertThat(FORBIDDEN).doesNotContain(row.sdkReference())` 或等價寫法）；
  - 逐一斷言所有 52 筆 `sdkReference` 皆不包含 `futopt` 字串；
  - 若走 `@WebMvcTest`／`WebTestClient` 型態的既有 BFF controller 測試慣例，另驗證 `GET /api/bff/fubon-api` 的 HTTP 200 與 JSON 陣列長度 52。
  測試檔頂部加註解：`fubon-broker-service` 新增／修改 endpoint，或富邦 SDK 版本升級改變命名空間方法時，本測試與 `FubonApiInfoBffController.APIS` 需同步依「盤點方法」段落的步驟重新核對更新。

- [ ] **386.4 前端 API client 維持不變。** `frontend/src/api/index.js` 的 `fubonApi.get()`（第一版已新增）不需修改。

- [ ] **386.5 重寫前端頁面。** 修改 `frontend/src/views/FubonApiView.vue`：
  - 頂部 `el-alert`（`type="warning"`、`show-icon`、`:closable="false"`），文字改為：「本頁列出富邦官方 SDK 已驗證存在的唯讀查詢能力，並標示是否已被本系統串接；系統不提供、也不會透過此頁面或任何其他路徑代為下單、改單、撤單或執行任何交易。『未串接』代表 SDK 具備此查詢能力但本系統尚未整合呼叫，僅供資訊盤點，不代表可透過本系統呼叫。如需下單，請自行至富邦官方平台或 App 操作。」
  - 篩選列：
    - 「已串接」篩選：`el-radio-group`（全部／已串接／未串接）。
    - 分類篩選：`el-select`（選項為上述七類 ＋「全部分類」，7 個選項改用下拉選單而非 `el-radio-group`，避免單列按鈕換行擁擠）。
    - `el-input` 關鍵字搜尋（比對 `name`／`description`／`category`／`sdkReference`）。
  - `el-table`（`stripe`、`size="small"`）欄位（不含展開欄之外）：
    - 已串接：`el-tag`，`connected=true` 顯示 `type="success"` 文字「已串接」，`false` 顯示 `type="info"` 文字「未串接」；
    - 分類：`el-tag type="info" effect="plain"`；
    - 名稱；
    - HTTP 端點：`connected=true` 時等寬字型顯示 `httpEndpoint`（比照 `ScheduleListView.vue` 的 `.cron` class 樣式），`false` 時顯示「－」；
    - 唯讀用途說明：`show-overflow-tooltip`；
    - 呼叫端／使用情境。
  - 新增 `el-table-column type="expand"`（比照既有 `TradingRadarView.vue` 展開列模式，見 `frontend/src/views/TradingRadarView.vue` 第 222 行起 `<el-table-column type="expand">` 的寫法），展開內容依序顯示：
    - SDK 方法／頻道（`row.sdkReference`，等寬字型）；
    - 請求參數（`row.requestSummary`，`<pre>` 或條列呈現，允許換行、不得截斷）；
    - 回應內容（`row.responseSummary`，同上）。
  - `onMounted` 呼叫 `bffApi.fubonApi.get()` 取得清單，`loading` 狀態、無額外快取。
  - 不得新增任何「試打 API」「複製 curl」「輸入參數呼叫」等互動元件；不得顯示或暗示任何 API（含未串接項目）可用於下單、改單、撤單、圈存或轉帳。

- [ ] **386.6 router 與選單維持不變。** `frontend/src/router/index.js` 的 `/fubon-api` route 與 `frontend/src/App.vue` 的 `mainMenuItems`「富邦證 API」選單項（第一版已建立）不需修改，本次只調整頁面內容與資料模型。

- [ ] **386.7 不在本次範圍。** 不修改 `fubon-broker-service` 任一既有 endpoint 的行為、輸入輸出格式或 token 驗證；不新增、封裝或間接觸發任何下單類 SDK API（含清單中排除的 22 個方法）；不變更既有富邦庫存同步（`FubonInventorySyncScheduler`）、成交同步（`FubonTradeSyncScheduler`）、台股 LIVE 報價或大盤指數串流的邏輯、排程節拍或 feature flag；不將本頁 BFF 端點掛上 9090 API gateway、Tailscale Serve 或 frontend nginx 9090 allowlist；不修改 `docs/openapi/docker-external-api.yaml`；不新增即時探測 `fubon-broker-service` 或富邦 SDK 連線狀態的呼叫（清單內容為靜態文字說明，非即時查詢結果）；不實作「未串接」項目的實際串接（本任務只做盤點呈現，不做功能擴充）。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -Dnet.bytebuddy.experimental=true -f bff/pom.xml test
npm --prefix frontend run build
docker compose -p asset-management build --no-cache bff frontend
docker compose -p asset-management up -d --no-deps --force-recreate bff frontend
docker compose -p asset-management ps bff frontend
curl -s -o /dev/null -w '%{http_code}\n' http://localhost/api/bff/fubon-api
```

以實際 Compose stack 驗證：未登入直接請求 `/api/bff/fubon-api` 仍被登入流程保護（非 200）；登入後側欄「系統資訊」分組「富邦證 API」項目仍在原位（與「排程列表」「開放 API」相對順序不變）；進入 `/fubon-api` 可看到全部 52 筆資料，「已串接」欄位依實際部署階段呈現：ETF main 基線9／43，本輪已核實功能驗收後15／37；394／395仍未串接，完整財務目標才是17／35；展開任一列可看到 SDK 方法／頻道、請求參數、回應內容明細；依「已串接」與分類篩選、關鍵字搜尋皆可用；頁面明確呈現「未串接」定位說明文字。驗收過程不得對 `fubon-broker-service` 或任何券商端點送出寫入性請求。

本任務未觸及 `backend/src/main/resources/db/changelog/**`，故不需重產 `db/schema.sql`。

## 接手排程盤點更新（2026-08-30）

本階段 BFF 編譯後 metadata 與 242 項完整測試確認52筆、15已串接／37未串接；其中交割款與已實現損益仍是安全 parser/no-write，不能以 adapter 有回應視為財務同步完成。API路由14條、SDK盤點52項、global outcome counter13鍵是三種不同計數；JOBS為64＝business27＋external37，成交排程為09:00至14:00共11輪，不含14:30。本檔兩段 Java 範例已逐字同步現有 DTO／controller；前端頁面、登入權限、選單與路由未改動。

本輪獨立架構查證已完成：找到的Redis具體實作依賴問題與三個新backend token filter編碼別名繞過皆已修正並複核；未修finding為critical0／major0／minor0。完整回歸為backend1,854＋BFF242＋external700＋Python676＝3,472，皆零失敗／錯誤／跳過。Python另以Linux amd64 test image、network=none且無secret mount重跑676項全通過。

2026-08-30 13:47–13:52（Asia/Taipei）從 `/Users/steven/Project/asset-management-takeover-fubon-schedules` rebuild/recreate adapter、business、external、BFF；四服務healthy，BFF於兩上游healthy後才recreate。實際jar的11個關鍵class與已測試target產物逐位元一致，adapter的21個Python來源檔與worktree雜湊一致，實際14routes／13global counter keys吻合。image SHA前綴：adapter `55efd6a7ca0d`、business `c619abd7d24b`、external `203297269634`、BFF `b2e45b151cb2`。

只以明列GET清單驗收：根頁200、未登入BFF盤點401、9090 commodity/calendar200、adapter health200且SDK2.2.9／x86_64、停用的config／stock stream／technical cache皆503 DISABLED。沒有generic method matrix、沒有rescan或任何live POST。登入現有管理者後DOM確認52／15／37，兩financial項及capital_changes均未串接、15／37篩選正確、KD展開可讀SDK／請求／回應摘要；排程64＝27＋37且成交共11輪。BFF重啟後Connection refused／500 Server Error為0；92張表的schema與當前DB逐位元一致。

全域FUBON_ENABLED及新features在驗收中保持false，main環境檔雜湊與secret mounts不變；這些disabled checks不替代前述隔離DB／Redis正常寫入證據，也不宣稱真人SDK權限／行情已驗。Task394／395尚缺來源完整性與逐筆身分／財務口徑，不標整體session完成、不刪原Claude branches。正式環境曾於13:57恢復已push的main `07963d01`，四服務healthy；三個實際jar共1,673個class與已測試ETF產物逐位元一致，12個Python檔案與main來源一致。頁面確認52／9／43、排程58＝24＋34；主環境檔雜湊、secret mounts及其餘containers不變，GET清單通過且92張表無schema差異。

使用者隨後指出正式頁面看不到新增串接項目。本輪將已通過實作、完整測試、獨立查證及feature Docker驗收的六項API能力先獨立交付：銀行餘額、股利、個股推播、KD、MACD及布林通道；部署後正式頁面應為52／15／37。這次交付仍保留Task394／395的strict parser／preflight／零寫入限制及未串接狀態，不因合併而宣稱第二個session全部完成。各feature設定與全域富邦設定維持停用，未驗真人SDK權限。完成這次push與部署後另做所有富邦相關程式的完整檢查與必要重構，再獨立commit-merge-push；本輪diff audit不替代該項工作。以下歷史230項測試不作本輪驗收依據。

## 歷史完成報告（Task386 原版，非本輪驗收）

**實際改動的檔案**（皆在 worktree `/Users/steven/Project/asset-management/.claude/worktrees/fubon-api-feature-d5e6c5`；第一版已建立、本次第二版重寫或維持不變一併列出）：

- 重寫：`bff/src/main/java/com/steven/assets/bff/fubonapi/FubonApiInfoDto.java`（386.1；欄位由 6 個改為 9 個：`connected, category, name, sdkReference, httpEndpoint, description, consumer, requestSummary, responseSummary`）
- 重寫：`bff/src/main/java/com/steven/assets/bff/fubonapi/FubonApiInfoBffController.java`（386.2；`APIS` 常數逐字複製任務檔第 124–426 行的 52 筆完整程式碼區塊，未手動謄寫改字）
- 重寫：`bff/src/test/java/com/steven/assets/bff/fubonapi/FubonApiInfoBffControllerTest.java`（386.3；8 個測試方法，排除清單改用 `Set<String> FORBIDDEN` 完整字串精確相等比對，不用子字串 `contains`）
- 維持不變（第一版已建立，本次未修改）：`frontend/src/api/index.js`（`fubonApi.get()`，386.4）、`frontend/src/router/index.js`（`/fubon-api` route，386.6）、`frontend/src/App.vue`（`mainMenuItems` 「富邦證 API」選單項，386.6）
- 重寫：`frontend/src/views/FubonApiView.vue`（386.5；新增「已串接」`el-tag`（success／info）欄位與 `el-radio-group` 已串接篩選、分類篩選改用 `el-select`（7 類選項）、新增 `el-table-column type="expand"` 展開列顯示 `sdkReference`／`requestSummary`／`responseSummary`、頂部 `el-alert` 換成新版含「未串接」定位說明文字；未新增任何「試打 API」/「複製 curl」類互動元件）
- 回填：`spec/tasks/t386_fubon_api_documentation_view.md`（本段完成報告）

**驗證指令與實際輸出**（僅執行任務檔要求的「本機可執行」部分；Docker Compose 重建與瀏覽器實機驗收留待後續步驟）：

```bash
cd /Users/steven/Project/asset-management/.claude/worktrees/fubon-api-feature-d5e6c5
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -Dnet.bytebuddy.experimental=true -f bff/pom.xml test
# exit code 0；FubonApiInfoBffControllerTest: Tests run: 8, Failures: 0, Errors: 0
# 全模組 surefire 彙總：230 個測試，Failures: 0, Errors: 0（各 *.txt 逐檔核對，無任一檔 Failures/Errors 非 0）

npm --prefix frontend run build
# exit code 0；輸出含 dist/assets/FubonApiView-CIM22ccM.js，✓ built in 4.66s，無編譯錯誤
```

程式化核對 `FubonApiInfoBffController.java` 的 `APIS` 常數（`grep -c` 精確計數）：

```
new FubonApiInfoDto(true,  ) 出現次數 = 8
new FubonApiInfoDto(false, ) 出現次數 = 44
new FubonApiInfoDto(       ) 總出現次數 = 52
禁止的 22 個下單/改單/刪單類方法（完整字串 sdk.stock.xxx）逐一 grep：0 筆命中
"futopt" 字串：僅出現在 class Javadoc 說明排除範圍的註解文字（第 22 行），APIS 常數本體 0 筆命中
```

三個數字（8／44／52）與任務檔 386.3 要求的精確數字完全吻合。

**與原計畫的偏差**：無實質偏差。386.3 的「若走 `@WebMvcTest`／`WebTestClient` 型態的既有 BFF controller 測試慣例」為條件句——查證同類「系統資訊」分組的既有 sibling controller（`SchedulePublicBffController` 及其測試 `SchedulePublicBffControllerTest`）與本頁第一版測試，慣例皆為透過 `new XxxController().list()` 直接呼叫的純單元測試，並無 `@WebMvcTest`／`WebTestClient` 慣例可循，故未新增該類 HTTP 層測試，與任務檔條件句的前提一致。

本次未執行 `docker compose` 相關指令、未跑 `scripts/spec-check.sh`、未執行 `git commit`——依主 agent 指示，這些留待後續步驟處理。
