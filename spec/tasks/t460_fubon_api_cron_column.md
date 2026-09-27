# [t460] 富邦證 API 一覽新增 Cron 執行時間欄位

**對應 Requirements:** Requirement 169（為富邦證唯讀 API 清單逐列標示實際 Cron 觸發式）
**前置任務:** Task 386 已建立的 52 筆靜態唯讀 API 清單
**Liquibase changeset:** 無

## 背景

「系統資訊／富邦證 API」頁目前以靜態清單呈現 SDK 唯讀查詢能力與呼叫情境，但時間只以自然語言散落在 consumer 說明中，無法直接讀取實際 Cron。需求是在每列增加 Cron 欄位。來源必須是目前排程宣告的 Spring 六欄 Cron，不可依自然語言猜測。沒有 Cron 的手動、事件、啟動、fixedRate 或 fixedDelay API 必須明確顯示無 Cron。

此功能只增加既有 GET /api/bff/fubon-api 靜態資料欄位與前端顯示；不改任何排程及其 gates，不改 scheduler list，不觸發 SDK／券商 API，也不新增資料寫入。

## 要做什麼

- [x] **460.1 BFF DTO 與固定對照。** 在 FubonApiInfoDto 新增非 null、不可變的 List<String> cronExpressions。保留現有 9 參數建構方式或等效相容實作，使原靜態 52 筆 APIS 宣告不必逐列重寫。Controller 的 list() 只依 exact sdkReference 對照靜態 metadata，替下列 18 筆 API 附上全部實際 @Scheduled(cron=...) 字串；每個 Cron 欄位均是 Spring 六欄格式，全部時區為 Asia/Taipei：

  | SDK reference | Cron expressions |
  |---|---|
  | sdk.accounting.inventories | 0 5,35 9-13 * * MON-FRI |
  | sdk.accounting.unrealized_gains_and_loses | 0 5,35 9-13 * * MON-FRI |
  | sdk.accounting.bank_remain | 0 0 8 * * *；0 20 9 * * *；0 20 14 * * *；0 0 22 * * * |
  | sdk.accounting.query_settlement | 0 0 8 * * *；0 45 13 * * *；0 30 19 * * *；0 0 22 * * * |
  | sdk.accounting.realized_gains_and_loses | 0 0 8 * * *；0 45 13 * * *；0 30 19 * * *；0 0 22 * * * |
  | sdk.stock.filled_history | 0 0,30 9-13 * * MON-FRI；0 0 14 * * MON-FRI |
  | marketdata.rest_client.stock.intraday.quote | */10 * 9-13 * * MON-FRI；0 5,35 9-13 * * MON-FRI |
  | marketdata.rest_client.stock.intraday.ticker | 0 40 13 * * MON-FRI |
  | marketdata.rest_client.stock.intraday.candles | 0 40 13 * * MON-FRI |
  | marketdata.rest_client.stock.intraday.volumes | 0 * * * * * |
  | marketdata.rest_client.stock.historical.candles | 0 35 15 * * MON-FRI |
  | marketdata.rest_client.stock.technical.sma | 0 40 13 * * MON-FRI |
  | marketdata.rest_client.stock.technical.rsi | 0 40 13 * * MON-FRI |
  | marketdata.rest_client.stock.technical.kdj | 0 40 13 * * MON-FRI |
  | marketdata.rest_client.stock.technical.macd | 0 40 13 * * MON-FRI |
  | marketdata.rest_client.stock.technical.bb | 0 40 13 * * MON-FRI |
  | marketdata.rest_client.stock.corporate_actions.dividends | 0 0 9 * * MON-FRI；0 30 13 * * MON-FRI |
  | marketdata.rest_client.stock.ownership.etf_holdings | 0 50 8 * * MON-FRI；0 30 15 * * MON-FRI |

- [x] **460.2 其他 API 不產生假 Cron。** 其餘清單項目的 cronExpressions 為空 list。包括手動／同步 endpoint、SDK TAIEX stream verification、WebSocket stream、啟動補齊，以及以 fixedDelay／fixedRate 執行者。不得將 interval、自然語言說明或啟動事件換算成猜測 Cron，不得改排程觸發式。
- [x] **460.3 前端顯示。** 在 frontend/src/views/FubonApiView.vue 的既有表格新增「執行時間（Cron）」欄，位置緊接 HTTP 端點欄。Cron 用等寬字體顯示原字串；多筆逐行顯示；時區清楚標示 Asia/Taipei。空 list 顯示「無固定 Cron」。不更動表格既有搜尋、分類／連線篩選、列展開或其他欄位。
- [x] **460.4 BFF 回歸測試。** 擴充 FubonApiInfoBffControllerTest：精確斷言上述全部 18 筆對照與每筆順序（`marketdata.rest_client.stock.intraday.quote` 含 PricePoller 與庫存同步觸發的兩個 Cron），斷言其餘項目為空 list，所有 row 的 cron list 非 null，既有 52 筆、連線狀態、category、SDK reference、HTTP endpoint 與唯讀限制測試維持通過。
- [x] **460.5 保持排程執行安全。** 本任務只揭露靜態排程註記；不得新增 runtime schedule discovery、SDK/API 呼叫、feature flag、排程註冊或 DB／Redis writer。Cron 只代表觸發時間，原有 feature flag、交易日、時段、服務就緒及 in-flight gates 皆維持。

## 驗證

```bash
bash scripts/spec-check.sh
mvn -q -f bff/pom.xml -Dtest=FubonApiInfoBffControllerTest test
cd frontend && npm run build
```

依 .agents/skills/run-stack/SKILL.md 僅重建與重建容器 bff、frontend，確認兩者 healthy。使用已登入的本機應用打開「系統資訊／富邦證 API」頁，實際確認「執行時間（Cron）」欄、四 Cron 的銀行餘額列、多 Cron 換行與無 Cron 標示。不要呼叫 SDK、排程手動同步、券商 API 或新增任何寫入操作。

## 完成報告

完成。`FubonApiInfoDto` 加入 immutable `cronExpressions` 並保留既有 9 參數建構相容性；BFF 僅依 exact `sdkReference` 加入 18 筆靜態 schedule 對照，其他列維持空 list。`FubonApiInfoBffControllerTest` 精確驗證所有 18 組對照與順序、其餘空值、52 筆完整清單、非 null 與不可變性。`FubonApiView.vue` 在 HTTP 端點後顯示 Cron、Asia/Taipei、多筆換行及無固定 Cron。

驗證：`bash scripts/spec-check.sh` 通過；`mvn -q -f bff/pom.xml -Dtest=FubonApiInfoBffControllerTest test` 通過；Docker production build 已完成並從 feature worktree 重建 `bff`、`frontend`。`asset-bff` healthy 且 actuator 為 `UP`，前端部署 chunk 已確認含「執行時間（Cron）」欄；映像 SHA-256 前綴：bff `8596df12`、frontend `472038b4`。沒有改排程註冊或其他 SDK／券商 I/O。

驗收限制：本次沒有可用的已登入瀏覽器 session；匿名功能 API 正確回 401，因此未宣稱已在登入後頁面逐列點驗 Cron 顯示。已開啟本機應用供登入後查看。
