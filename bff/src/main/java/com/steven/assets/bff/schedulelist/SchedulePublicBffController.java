package com.steven.assets.bff.schedulelist;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * ScheduleListView 專屬 BFF（「公開資訊」分組，Requirement 36）。
 *
 * <p>回傳系統所有自動排程的**人工維護靜態清單**。排程分屬兩個服務：
 * {@code business-services}（21 個）與 {@code external-materials-service}（34 個）。
 * 此頁為唯讀資訊展示，故不做跨服務反射探索、不入 DB、不設管理端點。
 *
 * <p><b>計數慣例：以 {@code @Scheduled} 方法計，一法一筆。</b>external 34 筆對應 36 個標註
 * （{@code TwClosurePoller} 與台股官方收盤對帳各為一法兩標、各併為一筆；Task 228 直接以 {@code grep '@Scheduled'} 逐檔核對重新校正此數，
 * 修正了 Task 228 之前既已存在、與此清單無關的計數漂移；Task 337 新增 {@code CommodityPricePoller} 的
 * 每分鐘即時報價與收盤後校正兩筆，各一法一筆，不套用一法兩標的合併規則）。
 *
 * <p><b>維護提醒：新增／調整任何 {@code @Scheduled} 時，務必同步更新下方 {@link #JOBS} 清單，避免與實際 cron 漂移。</b>
 * 已非「cron 皆為編譯期常數」——部分排程改為「每分鐘 tick ＋ 比對 DB 可設定時點」，
 * 此類**一律標「動態：依『X』頁設定」，不得寫死單一時間**（寫死即漂移：Task 191 把分析改為每分鐘 tick 後，
 * 此清單仍顯示 08:45 直到 Task 195 修正）：
 * <ul>
 *   <li>{@code MarketAnalysisScheduler} → {@code market_analysis_send_time}（「今日股市分析」頁可增減）</li>
 *   <li>{@code NewsPoller} → {@code crawler_schedule}（「爬蟲資訊查詢」頁可增減）</li>
 *   <li>{@code StockFundamentalPoller} → {@code crawler_schedule[crawler_key=fundamental]}</li>
 * </ul>
 * 每分鐘 tick 但時點屬 per-user 私人設定者（{@code ExportScheduleService}、
 * {@code TradingCalendarExportScheduleService}）則照列其實際 cron {@code 0 * * * * *}。
 *
 * <p>對照來源：
 * <ul>
 *   <li>business-services：IndexDailyRefreshScheduler、TreasuryYieldRefreshScheduler、HistoricalDataService、ExportScheduleService、
 *       TradingCalendarExportScheduleService、RealizedGainExportScheduleService、SnapshotDateRollScheduler、
 *       StockAlertService、MarketAnalysisScheduler、BackupService</li>

 *   <li>external-materials-service：TwseIndexPoller、PricePoller、TaiexIndexPoller、TwClosurePoller、
 *       FundDividendPoller、NewsPoller、StockFundamentalPoller、KrStockPoller、FundNavPoller、DividendPersister、
 *       IntradayTickRefresher、HistoricalBackfillService、ExchangeRatePoller、ClosePersister、
 *       UsValuationDerivationScheduler、CommodityPricePoller、EtfNavPoller</li>
 * </ul>
 *
 * <p>{@code CommodityPricePoller} 與 {@code EtfNavPoller} 為 Task 337 補入——前者是本次新增的
 * 兩筆（盤中即時報價／收盤後校正），後者是既有清單本就漏列的標的（與本任務無關的既有漂移，
 * 既然要動這段就順手清掉，不必另開任務）。
 */
@RestController
@RequestMapping("/api/bff/schedule-list")
public class SchedulePublicBffController {

    private static final String BUSINESS = "業務服務";
    private static final String EXTERNAL = "外部行情服務";
    private static final String TPE = "Asia/Taipei";
    private static final String NYC = "America/New_York";
    private static final String LON = "Europe/London";

    /** 全系統排程清單（55 筆）。順序刻意先業務服務、再外部行情服務，前端再依 category 分組。 */
    private static final List<ScheduledJobDto> JOBS = List.of(
            // ===== business-services（21）=====
            new ScheduledJobDto(BUSINESS, "資產快照", "最新快照釘定當日",
                    "將每位使用者的最新快照日期釘為當日並重算資產，讓即時股價覆蓋生效",
                    "每日 00:05", "0 5 0 * * *", TPE),
            new ScheduledJobDto(BUSINESS, "資料清理", "警示觸發紀錄清理",
                    "清理 30 天前的股票警示觸發紀錄",
                    "每日 04:00", "0 0 4 * * *", TPE),
            new ScheduledJobDto(BUSINESS, "今日股市分析", "今日股市分析產生",
                    "每分鐘比對啟用中的寄送時間，命中即由 AI 判斷當日台股走向並產生分析；每個時段各重跑一次並各寄一封（限台股交易日，颱風假／假日不寄）；執行時間可於「今日股市分析」頁增減（Requirement 31）",
                    "動態：依「今日股市分析」頁設定（預設 08:45）", "動態（market_analysis_send_time）", TPE),
            new ScheduledJobDto(BUSINESS, "今日股市分析", "分析批次收尾輪詢",
                    "定期撈在製的 Batch API 批次，批次完成後把結果落庫",
                    "每 90 秒（啟動後延遲 60 秒）", "fixedDelay=90s, initialDelay=60s", ""),
            new ScheduledJobDto(BUSINESS, "股票警示", "警示通知派送",
                    "每 60 秒派送已觸發但尚未通知的股票警示",
                    "每 60 秒", "fixedDelay=60s", ""),
            new ScheduledJobDto(BUSINESS, "交易雷達", "雷達通知派送",
                    "每 2 秒檢查交易雷達通知佇列並派送待處理通知",
                    "每 2 秒（啟動後延遲 2 秒）", "fixedDelay=2s, initialDelay=2s", ""),
            new ScheduledJobDto(BUSINESS, "大盤指數", "櫃買／海外 code-keyed 指數日線回補",
                    "各國大盤指數收盤後全量回補日線（TPEX＋8 檔海外指數＋SP500TR＋TWSE 報酬指數增量）；回補完成後驗收美股指數是否已追上最近一個已完成美股交易日，未追上者逐檔留下 WARN",
                    "每日 07:00（週二~六）", "0 0 7 * * TUE-SAT", TPE),
            new ScheduledJobDto(BUSINESS, "大盤指數", "美股指數日線落後補救檢查",
                    "檢查美股指數（道瓊／標普500／那斯達克／費半／SP500TR）日線是否落後最近一個已完成美股交易日（與交易雷達同一把尺），涵蓋 07:00 全量回補時來源尚未發布當日 bar 的情形；只回補確實落後的那幾檔，已追上則不對外部來源發任何請求",
                    "每日 09:00、12:00（週二~六）", "0 0 9,12 * * TUE-SAT", TPE),
            new ScheduledJobDto(BUSINESS, "美債殖利率", "官方殖利率曲線刷新",
                    "美股收盤後抓美國財政部官方 3M／5Y／10Y／30Y 完整 curve；官方失敗或 partial 時才以 Yahoo 四 ticker 整批 fallback",
                    "每日 07:00（週二~六）", "0 0 7 * * TUE-SAT", TPE),
            new ScheduledJobDto(BUSINESS, "資產匯出", "每日匯出排程檢查",
                    "每分鐘檢查歷年資產頁設定的多個每日時間，命中每個啟用時間即同時產出 JSON 與 Excel 兩份（主檔名相同）；較晚時段覆寫同日最新檔，輸出含 Google Drive 同步（若已啟用）",
                    "每分鐘", "0 * * * * *", TPE),
            new ScheduledJobDto(BUSINESS, "交易日曆", "交易日曆每日匯出排程檢查",
                    "每分鐘檢查各使用者的交易日曆自動匯出設定，命中執行時間即同時產出 JSON 與 Excel 兩份（主檔名相同）的當前年度交易日曆（Requirement 37）；輸出含 Google Drive 同步（若已啟用）",
                    "每分鐘", "0 * * * * *", TPE),
            new ScheduledJobDto(BUSINESS, "已實現損益匯出", "每日匯出排程檢查",
                    "每分鐘檢查已實現損益頁設定的多個每日時間，命中每個啟用時間即同時產出 JSON 與 Excel 兩份（主檔名相同）到指定目錄（Requirement 73）；較晚時段覆寫同日最新檔，輸出含 Google Drive 同步（若已啟用）",
                    "每分鐘", "0 * * * * *", TPE),
            new ScheduledJobDto(BUSINESS, "油價金價匯出", "每日匯出排程檢查",
                    "每分鐘檢查頁面設定的多個每日時間，命中每個啟用時間即同時產出 JSON 與 Excel 兩份（主檔名相同）到指定目錄（Requirement 72）；輸出含 Google Drive 同步（若已啟用）",
                    "每分鐘", "0 * * * * *", TPE),
            new ScheduledJobDto(BUSINESS, "台幣兌美元匯出", "每日匯出排程檢查",
                    "每分鐘檢查各使用者的台幣兌美元匯率自動匯出設定，命中執行時間即同時產出 JSON 與 Excel 兩份（主檔名相同）到指定目錄（Requirement 42）；輸出含 Google Drive 同步（若已啟用）",
                    "每分鐘", "0 * * * * *", TPE),
            new ScheduledJobDto(BUSINESS, "大盤指數匯出", "每日匯出排程檢查",
                    "每分鐘檢查各使用者的大盤指數日線自動匯出設定，逐一處理每個時間點及其複選指數，命中後同時產出 JSON 與 Excel 兩份（主檔名相同，欄位為開高低收＋四條均線＋成交股數／成交金額，各複選指數各自一組）到指定目錄（Requirement 45）；輸出含 Google Drive 同步（若已啟用）",
                    "每分鐘", "0 * * * * *", TPE),
            new ScheduledJobDto(BUSINESS, "資料備份", "每日備份（台股收盤後）",
                    "台股交易日收盤後 2 小時備份資料庫至 daily/",
                    "交易日 15:30", "0 30 15 * * MON-FRI", TPE),
            new ScheduledJobDto(BUSINESS, "資料備份", "每日備份（美股收盤後）",
                    "美股收盤後 2 小時（台北 07:00）備份資料庫至 daily/",
                    "每日 07:00（週二~六）", "0 0 7 * * TUE-SAT", TPE),
            new ScheduledJobDto(BUSINESS, "資料備份", "每週備份",
                    "每週日備份資料庫至 weekly/",
                    "每週日 05:00", "0 0 5 * * SUN", TPE),
            new ScheduledJobDto(BUSINESS, "資料清理", "歷史資料清理",
                    "刪除 10 年前的股價與匯率歷史",
                    "交易日 17:30", "0 30 17 * * MON-FRI", TPE),
            new ScheduledJobDto(BUSINESS, "交易雷達匯出", "每日匯出排程檢查",
                    "每分鐘檢查各使用者設定的多個交易雷達匯出時間點，命中執行時間即先回補台股即時行情、由背景重算一次雷達並寫入快照，再把當日快照同時產出 JSON 與 Excel 兩份（主檔名相同）到指定目錄；台股休市日不產檔（Requirement 48）；輸出含 Google Drive 同步（若已啟用）",
                    "動態：依「今日交易雷達」頁設定的多個時間點", "0 * * * * *", TPE),
            new ScheduledJobDto(BUSINESS, "交易紀錄匯出", "每日匯出排程檢查",
                    "每分鐘檢查各使用者的每一筆交易紀錄自動匯出排程（Task 255 起每人可設定多筆，各有自己的時間與輸出資料夾），命中執行時間即同時產出 JSON 與 Excel 兩份（主檔名相同）到該筆指定目錄（Requirement 49）；輸出含 Google Drive 同步（若已啟用）",
                    "每分鐘", "0 * * * * *", TPE),

            // ===== external-materials-service（34）=====
            new ScheduledJobDto(EXTERNAL, "即時行情", "台股個股即時價（盤中）",
                    "盤中每 2 分鐘查 TWSE MIS，更新上市／上櫃持股與觀察清單「個股」即時價至 Redis；不含大盤 0000，該筆由「台股大盤即時點位（盤中）」負責",
                    "交易日 09:00–13:30 每 2 分鐘", "0 0/2 9-13 * * MON-FRI", TPE),
            new ScheduledJobDto(EXTERNAL, "即時行情", "台股大盤即時點位（盤中）",
                    "盤中每 2 分鐘更新台股大盤（0000）即時點位至 Redis（來源 Yahoo ^TWII 5 分 K）；與「台股個股即時價（盤中）」刻意同頻率，但標的與來源皆不同，非重複排程（Task 228）",
                    "交易日 09:00–13:00 每 2 分鐘", "0 0/2 9-13 * * MON-FRI", TPE),
            new ScheduledJobDto(EXTERNAL, "即時行情", "美股即時價（盤中）",
                    "盤中每 2 分鐘更新美股即時價至 Redis",
                    "交易日 09:00–16:00 每 2 分鐘", "0 0/2 9-16 * * MON-FRI", NYC),
            new ScheduledJobDto(EXTERNAL, "即時行情", "英股即時價（盤中）",
                    "盤中每 2 分鐘更新英股即時價至 Redis",
                    "交易日 08:00–16:00 每 2 分鐘", "0 0/2 8-16 * * MON-FRI", LON),
            new ScheduledJobDto(EXTERNAL, "即時行情", "台股分時收尾",
                    "收盤後把台股當日分時 tick 收尾補齊",
                    "交易日 14:00", "0 0 14 * * MON-FRI", TPE),
            new ScheduledJobDto(EXTERNAL, "即時行情", "美股分時收尾",
                    "收盤後把美股當日分時 tick 收尾補齊",
                    "交易日 16:05", "0 5 16 * * MON-FRI", NYC),
            new ScheduledJobDto(EXTERNAL, "即時行情", "英股分時收尾",
                    "收盤後把英股當日分時 tick 收尾補齊",
                    "交易日 17:00", "0 0 17 * * MON-FRI", LON),
            new ScheduledJobDto(EXTERNAL, "收盤落庫", "台股官方收盤對帳",
                    "以 TWSE MI_INDEX 與 TPEx 日收盤全市場資料寫入可稽核的官方收盤，缺漏時於午後重試",
                    "交易日 14:05、15:35、17:35", "0 5 14 * * MON-FRI；0 35 15,17 * * MON-FRI", TPE),
            new ScheduledJobDto(EXTERNAL, "收盤落庫", "台股收盤缺漏補抓",
                    "16:00 僅用 FinMind 補官方來源仍缺漏的台股標的，不覆寫已完成列",
                    "交易日 16:00", "0 0 16 * * MON-FRI", TPE),
            new ScheduledJobDto(EXTERNAL, "收盤落庫", "美股收盤價落庫",
                    "把 Redis 美股收盤價 dump 進 DB",
                    "交易日 16:02", "0 2 16 * * MON-FRI", NYC),
            new ScheduledJobDto(EXTERNAL, "收盤落庫", "美股收盤價校正",
                    "用 FinMind 校正並覆寫美股當日收盤價",
                    "交易日 18:00", "0 0 18 * * MON-FRI", NYC),
            new ScheduledJobDto(EXTERNAL, "收盤落庫", "英股收盤價落庫",
                    "把 Redis 英股收盤價 dump 進 DB",
                    "交易日 16:32", "0 32 16 * * MON-FRI", LON),
            new ScheduledJobDto(EXTERNAL, "收盤落庫", "英股收盤價校正",
                    "用 Yahoo Finance 校正並覆寫英股當日收盤價",
                    "交易日 17:00", "0 0 17 * * MON-FRI", LON),
            new ScheduledJobDto(EXTERNAL, "大盤指數", "台股加權指數刷新（午後）",
                    "台股收盤後刷新加權指數（開高低收 ＋ 成交股數／成交金額）",
                    "交易日 14:00", "0 0 14 * * MON-FRI", TPE),
            new ScheduledJobDto(EXTERNAL, "大盤指數", "台股加權指數刷新（傍晚）",
                    "收盤後 3.5 小時再刷新一次，給 TWSE 月報多點時間發佈（開高低收 ＋ 成交股數／成交金額）",
                    "交易日 17:00", "0 0 17 * * MON-FRI", TPE),
            new ScheduledJobDto(EXTERNAL, "大盤指數", "台股加權指數刷新（隔日補抓）",
                    "隔日早盤前最後一次 catch-up（開高低收 ＋ 成交股數／成交金額）",
                    "每日 08:30（週二~六）", "0 30 8 * * TUE-SAT", TPE),
            new ScheduledJobDto(EXTERNAL, "匯率", "即期匯率（盤中）",
                    "盤中每 5 分鐘抓即期匯率（台銀優先，失敗改兆豐銀行；兩者皆失敗且為 USD 才退回 Yahoo 中間價）",
                    "交易日 09:00–15:55 每 5 分鐘", "0 0/5 9-15 * * MON-FRI", TPE),
            new ScheduledJobDto(EXTERNAL, "匯率", "USD/TWD 即時牌告（2 秒）",
                    "全天每 2 秒 tick；只在台銀營業日 09:00–15:30／16:30–23:00，或兆豐營業日 09:00–15:30 與 16:30 至次一營業日 08:00 依序抓取，銀行皆失敗才用 Yahoo；single-flight 且只寫兩個 Redis live key，不寫歷史 DB",
                    "全天每 2 秒 tick（各銀行交易時段內外呼）", "*/2 * * * * *", TPE),
            new ScheduledJobDto(EXTERNAL, "匯率", "匯率收盤補抓",
                    "收盤後走 FinMind 增量補匯率（涵蓋盤中漏抓）",
                    "交易日 17:00", "0 0 17 * * MON-FRI", TPE),
            new ScheduledJobDto(EXTERNAL, "油價金價", "油金價每日回補",
                    "每日補 WTI／布蘭特原油／COMEX 黃金前一交易日收盤（紐約收盤落在台北前一夜）",
                    "每日 06:30（週日不跑）", "0 30 6 * * MON-SAT", TPE),
            new ScheduledJobDto(EXTERNAL, "油價金價", "盤中即時報價",
                    "交易時段內每分鐘更新 WTI／布蘭特／COMEX 黃金即時價至 Redis（非交易時段不外呼）",
                    "每分鐘", "0 * * * * *", NYC),
            new ScheduledJobDto(EXTERNAL, "油價金價", "收盤後校正",
                    "收盤後 5 分鐘取回當盤收盤價寫入日線表，並回看 5 天讓結算價修訂落地",
                    "交易日 17:05", "0 5 17 * * MON-FRI", NYC),
            new ScheduledJobDto(EXTERNAL, "ETF淨值", "台股 ETF 淨值折溢價",
                    "盤中每 2 分鐘以一個證交所全市場 ETF 彙整檔 request，取即時預估淨值與折溢價寫入 Redis（Task 214／349）",
                    "交易日 09:01–13:29 每 2 分鐘", "0 1/2 9-13 * * MON-FRI", TPE),
            new ScheduledJobDto(EXTERNAL, "ETF淨值", "台股 ETF 淨值收盤後補抓",
                    "投信約 17:00 更新當日淨值，收盤後再抓一次，使 etf_nav_history 當日值為收盤折溢價（Task 215）",
                    "交易日 17:30", "0 30 17 * * MON-FRI", TPE),
            new ScheduledJobDto(EXTERNAL, "ETF淨值", "美股 ETF 淨值折溢價",
                    "美股收盤後逐檔取 Yahoo navPrice 與同時點市價算折溢價（淨值一天僅公告一次）",
                    "交易日 18:30", "0 30 18 * * MON-FRI", NYC),
            new ScheduledJobDto(EXTERNAL, "基金", "基金淨值回補",
                    "每日抓取所有基金淨值（NAV）",
                    "每日 09:00", "0 0 9 * * *", TPE),
            new ScheduledJobDto(EXTERNAL, "基金", "基金配息回補",
                    "每日抓取所有基金配息",
                    "每日 09:05", "0 5 9 * * *", TPE),
            new ScheduledJobDto(EXTERNAL, "股利", "股票配息同步",
                    "每交易日同步股票配息資料入庫",
                    "交易日 17:00", "0 0 17 * * MON-FRI", TPE),
            new ScheduledJobDto(EXTERNAL, "ETF 透視", "ETF 透視成份股回補",
                    "每日重算 ETF 透視前 10 大成份股並增量補齊歷史收盤",
                    "每日 18:30", "0 30 18 * * *", TPE),
            new ScheduledJobDto(EXTERNAL, "財經新聞", "財經新聞抓取",
                    "抓取財經新聞＋公開資訊快照；執行時間改由 DB 驅動，可於「爬蟲資訊查詢」頁增減多個時間點（Requirement 38）。"
                            + "每輪輸出公開資訊，同時產出 JSON 與 Excel 兩份（主檔名相同）至本機設定資料夾，並於已啟用時同步上傳一份副本至 Google Drive"
                            + "（Requirement 50；本機一律照寫，Drive 為附加副本、失敗不影響本機檔與入庫）。"
                            + "亦可於「爬蟲資訊查詢」頁按「立即匯出」（只重產檔案）或「立即抓取並匯出」"
                            + "（完整跑一輪）手動觸發（Requirement 63）",
                    "動態：依「爬蟲資訊查詢」頁設定（預設 08:20 / 11:30 / 18:00）", "動態（crawler_schedule）", TPE),
            new ScheduledJobDto(EXTERNAL, "個股基本面", "個股基本面與產業營收",
                    "先使用已入庫的 public_info_* 公開資訊作為質性證據，再依交易所→Yahoo→玩股網→FinMind 固定順序補齊持股與觀察個股的財報、估值與產業營收觀測；雷達請求只讀本地資料，不會現場抓外網",
                    "動態：依 crawler_schedule 設定（預設 15:30）", "動態（crawler_schedule:fundamental）", TPE),
            new ScheduledJobDto(EXTERNAL, "個股基本面", "美股歷史估值序列推導",
                    "先無條件重抓已入庫美股標的的 SEC EDGAR 官方季報（繞過既有 coverage 短路，否則放寬後的舊季別永遠補不進來），再由已入庫季報與收盤價逐交易日推導 PE／PB／殖利率並以 SEC_DERIVED 落地（Requirement 74）；預設只補缺口，但一律重算最近 30 個交易日（收盤價會被 18:00 ET 的 FinMind 校正覆寫），每股基準變動點後移時整段刪除重寫。時點必須晚於美股收盤校正（冬令時＝台北 07:00），故不得再往前調",
                    "每日 07:30（週二~六）", "0 30 7 * * TUE-SAT", TPE),
            new ScheduledJobDto(EXTERNAL, "韓股", "韓股參考個股抓取",
                    "每日抓取韓國三星電子／SK 海力士收盤，供公開資訊韓股快照（KOSPI 另讀既有海外指數）",
                    "每日 16:00", "0 0 16 * * *", TPE),
            new ScheduledJobDto(EXTERNAL, "台股休市偵測", "台股臨時休市偵測",
                    "開盤前每 15 分鐘偵測颱風／臨時休市，於 09:00 開盤前生效",
                    "交易日 05:00–07:00 每 15 分鐘", "0 0/15 5-6 * * MON-FRI；0 0 7 * * MON-FRI", TPE)
    );

    /** GET /api/bff/schedule-list —— 回傳全系統排程清單（55 筆靜態資料）。 */
    @GetMapping
    public List<ScheduledJobDto> list() {
        return JOBS;
    }
}
