# Task 487 / 488 隔離 feature 的實際執行驗收

本紀錄是合併前 feature 建置驗收；兩個 main 落地後的重建與驗收須另外完成，本檔不先宣稱已由 main 部署。

## 建置與部署

- 驗收時間：2026-10-10T23:45:55+08:00。
- Feature base commit：1688b8e9432f64b2bbe60f3e8f005476c328a6cf。
- 最終服務建置來源 SHA256：`ffc68248f339294dd6dbe7f6ed9444f2a9c4f1ddd2978e14d64e616066200f35`（tracked 與新增的四服務來源及 Compose；非 .env 或 private data）。
- 正式 Compose project：asset-management；只 build / recreate business-services、external-materials-service、fubon-broker-service、BFF，未重新建立 datastore、gateway、frontend 或元大服務。
- Run-stack 使用繼承主 agent 的模型；派工工具無 Terra 可選，未據此中止。
- .env 僅由 main 同步至原本缺檔的 feature；只在 feature 設正式 Fubon secrets 絕對來源。四服務既有 effective environment 與 main 完全相同，bind mounts 均與 main 同源；未輸出秘密值。External 新旗標 FUBON_INTRADAY_CANDLE_SYNC_ENABLED=true。
- business / Fubon / BFF 健康等待分別10.3 / 6.2 / 5.1秒；external檢查時已healthy。BFF 在 upstream 更新期間保留，business safe GET 0.1秒、external quotes JSON array 0.8秒恢復。BFF 因自身程式更新才 recreate。

| Service | 最終驗收 image SHA | Health |
|---|---|---|
| business-services | `sha256:9abc5ac0c6a53fd651009eded21103c60f9da12ba58183cb3a4090f3ea3f86fe` | healthy |
| bff | `sha256:0000c6f8779c5d2534f0b2bd20c421e2cefb8a2f8f002d13d75409e3c2ca5541` | healthy |
| external-materials-service | `sha256:acb3dfad0d8f88333aa1428de11d53ae0b480f605734162aabb5ec0e67d0523e` | healthy |
| fubon-broker-service | `sha256:5c5ac01b292352adf18b57832de9b25cb30a01607d52dd3bd5f7b29fb12fa0a8` | healthy |

## 一年保留與 schema

- TaipeiToday=2026-10-10，保留起點=2025-10-10，起點當日包含在保留範圍。
- 分鐘表部署前3,846,788列，過期2,669,152列；啟動清理後1,177,636列，過期0列。實際保留資料日期2025-10-13至2026-09-30。
- Capture metadata 過期0列；SRPP FINAL與claim皆0列。148及149 changeset成功，歷時611ms及15ms。
- db/schema.sql 先輸出暫存 dump，再保留原檔頭重產；112張表，schema全文drift測試PASS。
- 帳號、持股、資產快照、已實現損益、backup_record、官方Fubon每日歷史的count均不變。stock_price_history部署期間淨新增16列；external既有HistoricalBackfillService啟動回補持續執行，這不是retention刪除。分鐘retention SQL只清理分鐘表和capture metadata，沒有刪每日事實。
- 未執行VACUUM FULL、備份API、Drive上傳或刪除；pending dumps保留。

## 本機備份量測

兩份皆custom format / compress=9 / no-owner / no-acl；基準是既有2026-10-07 pending backup的歷史大小，並非同一時點相同輸入的前後dump。新量測僅是本機唯讀pg_dump，已以pg_restore --list驗證可讀，未上傳或移動pending檔。

| 項目 | bytes | MiB |
|---|---:|---:|
| 既有歷史基準 | 217,551,460 | 207.47 |
| 一年清理後新本機dump | 85,885,825 | 81.91 |

相對上述歷史基準減少60.52%。新dump SHA256=`64fedc4ad21fe0bb4407c89f33c278889aa2ab4aea901069f8bc47a6aa1d6c9b`，產生17.91秒；private內容及本機檔不加入Git。

## 真實 API 與安全邊界

- minute-no-token: HTTP 401。
- minute-current-day: HTTP 200，MARKET_CLOSED。
- minute-duplicate-selector: HTTP 400。
- radar-list: HTTP 200。
- radar-detail: HTTP 200，OUTSIDE_TRADING_SESSION。
- decision-holiday: HTTP 409，NON_TRADING_DAY。
- decision-invalid-body: HTTP 400，INVALID_REQUEST。
- decision-media: HTTP 415，UNSUPPORTED_MEDIA_TYPE。
- decision-wrong-method: HTTP 405。
- decision-descendant: HTTP 404。
- internal-not-public: HTTP 404。

- 當日日曆authority AVAILABLE且twTrading=false，live SRPP 409證明calendar fail closed；strict400、media415及route405/404也均驗證。沒有建立FINAL或claim，也未注入未來交易日或虛構行情。
- 雷達ruleVersion=TW_RULES_V22，list與detail保留完整closed typed intradayCandleConfirmation且同一標的一致；當前OUTSIDE_TRADING_SESSION，不宣稱真實盤中CONFIRMED已驗收。
- 分鐘internal GET未帶token401，重複selector400；合法當日200且UNAVAILABLE/MARKET_CLOSED、空candles。沒有request-time vendor smoke或券商下單。
- Gateway/OpenAPI二十三個method/path pairs parity與strict schema、examples、generated docs測試PASS。Feature鏡像檢查用CLAUDE_CODE_REMOTE=true限制renderer不讀尚未落地的SRPP main，並另行逐位元比對SRPP feature第三份；這是隔離驗收，並非雲端執行。
- Tailscale現行22個exact handlers涵蓋23個method/path pairs，SRPP decision / event path指向loopback9090；無root、/api wildcard或Funnel異動。現有Tailscale腳本回歸測試PASS。

## Swagger 與政策身分

- OpenAPI 1.24.0，asset feature文件 / classpath資源 / SRPP feature文件三份逐位元一致。
- 實際business-services JAR的classpath Swagger SHA256=`15fe7fb043ea0002809bb12033b473cee0a069e5bd26f76ee05eabac3a1ae0a6`，與feature文件相同。
- 請求政策hash=`919c7b79d79b62442063dac64f2aea14809d25ff75f7328147505013db70095f`。此runtime holiday gate先於policy查核；bundle完整驗證仍依SRPP獨立policy check證據。
- main落地後仍須從main重build / recreate四服務，重新驗證實際classpath與三份main Swagger鏡像、設定及images；此步尚待兩repo landing。

## 可證明範圍

本輪是服務/資料保留/契約/保守決策邊界驗收。市場已休市，盤中CONFIRMED、缺棒、衝突、未來事實、20-session D-195 golden及並行claim/跨slot由獨立固定輸入與PostgreSQL測試覆蓋。未做收益、費用、滑價或holdout回測；不據此宣稱投資判斷準確率提高。Gmail端到端稽核不在本次驗收範圍，未建立或寄送郵件。
