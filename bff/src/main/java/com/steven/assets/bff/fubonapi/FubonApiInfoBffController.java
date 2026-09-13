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
 * （52 筆：21 已串接、31 未串接），並標示每一筆是否已被本系統實際串接。清單來源是在
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

    /** 富邦 SDK 唯讀查詢能力全量盤點（52 筆：21 已串接、31 未串接）。 */
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
                    "唯讀查詢交割銀行餘額，將通過身分、日期與金額驗證的台幣餘額更新到最新快照的既有台北富邦銀行台幣活存，"
                            + "與快照總額同一交易提交；零餘額照寫" + RO,
                    "business-services（每日 08:00／09:20／14:20／22:00，需另啟用設定；"
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
            new FubonApiInfoDto(true, "帳戶／庫存查詢", "應收付交割金額查詢",
                    "sdk.accounting.query_settlement", "POST /internal/settlement/read",
                    "唯讀將 SDK 3d 回傳列中交割日位於 future、金額 nonzero 的 TWD transit 在途款窄投影為買股待付款／賣股待收款；"
                            + "這不是富邦官方定義的完整結算窗口或完整未交割帳。完全相同的 target 會略過，"
                            + "僅在 target 缺少或金額不同時建立或更新" + RO,
                    "business-services（每日 08:00／13:45／19:30／22:00；"
                            + "POST /internal/brokers/fubon/settlement-sync，dryRun 預設 true；"
                            + "僅接受真正 boolean accountBindingExplicit=true；"
                            + "coverageStatus=SDK_RANGE_3D_RETURNED_ROWS 且 reason=null）",
                    "無 body／帳戶 selector；adapter 固定 query_settlement(selected, 3d)，需 internal token",
                    "queryDate／observedAt／accountFingerprint／accountBindingExplicit=true（JSON boolean）／"
                            + "coverageStatus=SDK_RANGE_3D_RETURNED_ROWS／reason=null／details；含 sourceQueryDate、"
                            + "settlementDate、TWD、12 項 signed 整數字串與 AVAILABLE／NO_DATA_OBSERVED，無原帳號"),

            new FubonApiInfoDto(true, "帳戶／庫存查詢", "已實現損益明細查詢",
                    "sdk.accounting.realized_gains_and_loses", "POST /internal/realized-gains/read",
                    "唯讀將富邦回報的淨損益映射為調節成本基礎；這不是原始取得成本或完整 ledger（完整成交帳）。"
                            + "已由手動等價（manual-equivalence）資料或 source-idempotency 保護表示的相同資料會略過，"
                            + "僅在缺少對應 occurrence 時新增，絕不覆寫既有紀錄" + RO,
                    "business-services（每日 08:00／13:45／19:30／22:00；"
                            + "POST /internal/brokers/fubon/realized-gain-sync，dryRun 預設 true；"
                            + "僅接受真正 boolean accountBindingExplicit=true；手動等價（manual-equivalence）與 source-idempotency 保護）",
                    "無 body／帳戶 selector；selected account 由 adapter 決定，需 internal token",
                    "queryDate／observedAt／accountFingerprint／accountBindingExplicit=true（JSON boolean）／rows；"
                            + "每列 stockNo、buySell=Sell、orderType=Stock、filledQty、filledPrice、realizedProfit、"
                            + "realizedLoss、sourceDate；零損益合法，無原帳號"),

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

            // ===== 行情查詢（20：marketdata.rest_client.stock.*，11 已串接）=====
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
            new FubonApiInfoDto(true, "行情查詢", "單一商品基本資料查詢",
                    "marketdata.rest_client.stock.intraday.ticker", "POST /internal/market-data/stock-basic/read",
                    "以固定 strict wire 查詢台股單一商品基本資料；正規化資料寫入 PostgreSQL 的富邦基本資料表，"
                            + "僅補既有空白 stock 名稱，不建立標的、不改資產分類" + RO,
                    "external-materials-service（交易日 13:40 唯一 lifecycle；需另啟用設定）",
                    "body 僅允許 {symbol}；ordinary-lot 呼叫省略 SDK optional type",
                    "schemaVersion=1／symbol／market／provider／sourceDate／observedAt 與經嚴格驗證的交易所、名稱、分類、漲跌停、交易資格欄位；"
                            + "PostgreSQL 同日 hash fence，無 Redis／價格 history／latest quote 寫入"),
            new FubonApiInfoDto(true, "行情查詢", "個股當日分鐘K線查詢",
                    "marketdata.rest_client.stock.intraday.candles", "POST /internal/market-data/intraday-candles/read",
                    "以固定 strict wire 查詢台股 ordinary-lot 當日一分鐘 K；每批通過驗證後寫入 PostgreSQL 分鐘 K 歷史" + RO,
                    "external-materials-service（交易日 13:40 唯一 lifecycle；需另啟用設定）",
                    "body 僅允許 {symbol}；固定 timeframe=1，ordinary-lot 呼叫省略 SDK optional type",
                    "AVAILABLE 或 NO_DATA 的 schemaVersion=1 分鐘 K bundle；每根含 UTC 整分 OHLC／量／均價與 hash。"
                            + "分鐘資料絕不寫 Redis、daily price history 或 latest quote"),
            new FubonApiInfoDto(false, "行情查詢", "個股當日逐筆成交明細查詢",
                    "marketdata.rest_client.stock.intraday.trades", NO_HTTP,
                    "查詢個股當日逐筆成交明細" + RO, NOT_CONNECTED,
                    "symbol（路徑參數）＋選填 query 參數",
                    "逐筆成交明細陣列，通常含成交時間／價格／量，實際完整欄位以官方回應為準，"
                            + "此 wrapper 未在程式碼中定義 schema"),
            new FubonApiInfoDto(true, "行情查詢", "個股當日分價量查詢",
                    "marketdata.rest_client.stock.intraday.volumes", "POST /internal/market-data/intraday-volumes/read",
                    "以 strict wire 查詢當日各價位累計成交量；只保存專用 Redis 18 小時快照，交易雷達只讀當日新鮮快取" + RO,
                    "external-materials-service（交易日 09:00–13:30 每分鐘；需另啟用設定）",
                    "body 僅允許 {symbol}；ordinary-lot，固定今日交易雷達台股、最多 30 檔",
                    "schemaVersion=1 的 OK／NO_DATA levels；每列含 canonical price／volume／bidVolume／askVolume，不寫 PostgreSQL 或 generic price cache"),
            new FubonApiInfoDto(true, "行情查詢", "個股歷史日K線查詢",
                    "marketdata.rest_client.stock.historical.candles", "POST /internal/market-data/historical-daily-candles/read",
                    "以 strict wire 查詢完成日 K；immutable PostgreSQL fact 先提交，再 guarded 投影本地收盤，可信官方收盤永不覆寫" + RO,
                    "external-materials-service（台股交易日 15:35；需另啟用設定）",
                    "body 僅允許 {symbol,from,to}；固定 queryTo=今日、queryFrom=今日−365 天、inclusive 366 日、最多 30 檔",
                    "schemaVersion=1 的 OK／NO_DATA candles；OHLC／量／成交額／漲跌與 canonical SHA-256 fact，不寫 Redis"),
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
            new FubonApiInfoDto(true, "行情查詢", "個股簡單移動平均線查詢",
                    "marketdata.rest_client.stock.technical.sma", "POST /internal/market-data/technical-indicators/read",
                    "以 immutable 17-profile bundle 查詢 SMA：日線 5／10／20／60／240、週線 5／10／20；"
                            + "全歷史 fact/member 由 PostgreSQL 保存，交易雷達再以 100 秒 BOUND Redis overlay 優先讀取" + RO,
                    "external-materials-service（交易日 13:40 唯一 lifecycle；需另啟用設定；"
                            + "POST /internal/technical-indicators/fubon-sync）",
                    "body 僅允許 {symbol}；from 固定 queryDate−420 天、to=queryDate，無 caller 選擇週期／timeframe",
                    "schemaVersion=2 exact17 profiles；每筆 strict payload {sma}、candidate hash 與 observedAt。"
                            + "雷達只在 immutable compatibility proof 成立時套用指定欄位；其餘留 detail"),
            new FubonApiInfoDto(true, "行情查詢", "個股相對強弱指標查詢",
                    "marketdata.rest_client.stock.technical.rsi", "POST /internal/market-data/technical-indicators/read",
                    "以 immutable 17-profile bundle 查詢 RSI：日線與週線各 5／10；全歷史寫 PostgreSQL，"
                            + "交易雷達以 100 秒 BOUND Redis overlay 優先，local fallback 只覆寫 Redis、不覆寫富邦歷史" + RO,
                    "external-materials-service（交易日 13:40 唯一 lifecycle；需另啟用設定；"
                            + "POST /internal/technical-indicators/fubon-sync）",
                    "body 僅允許 {symbol}；from 固定 queryDate−420 天、to=queryDate，無 caller 選擇週期／timeframe",
                    "schemaVersion=2 exact17 profiles；每筆 strict payload {rsi}、candidate hash 與 observedAt；"
                            + "欄位 provenance 明示 FUBON／LOCAL／DETAIL_ONLY／未採用原因"),
            new FubonApiInfoDto(true, "行情查詢", "個股 KD 隨機指標查詢",
                    "marketdata.rest_client.stock.technical.kdj", "POST /internal/market-data/technical-indicators/read",
                    "以 immutable 17-profile bundle 查詢日／週 KDJ(9,3,3)；富邦全歷史在 PostgreSQL 為權威，"
                            + "雷達 Redis overlay 僅 100 秒且 K／D 的 direct overlay 必須有同一 response previous-row 證明" + RO,
                    "external-materials-service（交易日 13:40，需另啟用設定；"
                            + "POST /internal/technical-indicators/fubon-sync；純讀 GET /internal/technical-indicators/fubon-cache?symbol=...）",
                    "body 僅允許 {symbol}；固定日／週 KDJ(9,3,3) 與 queryDate−420 天至 queryDate",
                    "schemaVersion=2 exact17 profiles；kdj payload 為 k／d／j、same-response previous pointer 與 candidate hash。"
                            + "vendor J 僅 detail 對照，絕不取代 local J9"),

            new FubonApiInfoDto(true, "行情查詢", "個股 MACD 指標查詢",
                    "marketdata.rest_client.stock.technical.macd", "POST /internal/market-data/technical-indicators/read",
                    "以 immutable 17-profile bundle 查詢日／週 MACD(12,26,9)；全歷史寫 PostgreSQL，"
                            + "100 秒 Redis overlay 僅供雷達即時優先讀取，MACD 保持 detail-only、不改 V18 score" + RO,
                    "external-materials-service（交易日 13:40，需另啟用設定；"
                            + "POST /internal/technical-indicators/fubon-sync；純讀 GET /internal/technical-indicators/fubon-cache?symbol=...）",
                    "body 僅允許 {symbol}；固定日／週 MACD(12,26,9) 與 queryDate−420 天至 queryDate",
                    "schemaVersion=2 exact17 profiles；macd payload 為 macdLine／signalLine、candidate hash 與 observedAt；"
                            + "不捏造 EMA／DIF／OSC，也不將 vendor MACD 填入 V18 score"),

            new FubonApiInfoDto(true, "行情查詢", "個股布林通道查詢",
                    "marketdata.rest_client.stock.technical.bb", "POST /internal/market-data/technical-indicators/read",
                    "以 immutable 17-profile bundle 查詢日線 BB(20)；全歷史寫 PostgreSQL，100 秒 Redis overlay"
                            + "只供雷達即時優先讀取，BB 保持 detail-only、不改 V18 score" + RO,
                    "external-materials-service（交易日 13:40，需另啟用設定；"
                            + "POST /internal/technical-indicators/fubon-sync；純讀 GET /internal/technical-indicators/fubon-cache?symbol=...）",
                    "body 僅允許 {symbol}；固定日線 BB(20) 與 queryDate−420 天至 queryDate",
                    "schemaVersion=2 exact17 profiles；bb payload 為 upper／middle／lower、candidate hash 與 observedAt；"
                            + "不由富邦 BB 改寫本地 BIAS 或任一 V18 rule 欄位"),

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
                    "business-services（富邦 ETF 成分股交易日 08:50／15:30 全量同步；"
                            + "ApplicationReady missing-only 補齊尚無成功資料及 failure retry，"
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
