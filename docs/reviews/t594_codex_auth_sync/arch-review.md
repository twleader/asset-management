VERDICT: PASS

## 判定

critical: 0   major: 0   minor: 0

審查範圍：以 `origin/main`（`d7a8d2a8`，目前 HEAD 同此提交）對照工作樹的未提交變更。實際檢閱下列實作、測試與操作文件：

- `backend/.../SrppSettingsLocalAi.java` 與其測試。
- `frontend/src/utils/srppSettingsCenterAi.js` 與其測試。
- `scripts/srpp-local-ai-bridge.py`、installer 與 bridge 測試。
- `docs/operations/srpp-local-ai.md`。

另核對 requirements／design 的變更範圍，並參考本輪提供的 Task594、測試收據與規格審查結果；未重做 spec 審查。已完整讀取 `CLAUDE.md` 與 `spec/steering/structure.md`。

目前 `git diff origin/main --stat` 為 10 個受追蹤檔案變更，另有 5 個未追蹤文件；材料中的「變更檔數：3」未作為本輪範圍依據。

## Findings

無。本輪未查得 diff 新增或擴大、且違反兩份判準來源明文規定的架構問題。

## 已查無發現

| 檢查項目 | 實際查證與結果 |
|---|---|
| 模組界線、跨層資料存取 | 對變更涉及的 Java production 檔與 bridge 執行 `rg -n -i`，搜尋指定外部行情網域、`businessServicesUrl`、`JdbcTemplate`、`EntityManager`、`Repository`，零命中。diff 未修改 external-materials、券商 adapter 或資料庫存取流程；未新增反向呼叫、跨層讀表或券商交易路徑。符合 `structure.md` §8。 |
| Controller／Service 分層 | `git diff origin/main -- SrppSettingsLocalAi.java` 顯示唯一 production 變更是第 23 行加入兩個安全碼。搜尋 `ResponseEntity`／`HttpServletResponse` 零命中；無 Controller、Repository 或 Entity 變更。未新增 HTTP response 組裝、Controller 領域判斷或直接讀 Repository。符合 §2.2。 |
| 抽象介面與外部整合 | `rg` 回讀 `SrppSettingsLocalAi.java:21` 仍為 `implements SrppSettingsAiPort`。本輪沒有將 CLI 憑證解析、程序控制或供應者登入邏輯搬進 Java 業務端；新增行為位於既有 host bridge。未擴大既有相依方向。 |
| BFF 與前端呼叫 | 對修改的前端 utility 搜尋 `fetch\(`、`new EventSource`、`/api/`、`axios`、`Promise\.`，零命中。本輪只修改第 17、18 行安全文案，沒有新增頁面、跨頁 BFF、裸 HTTP／SSE 或 panel 載入流程；一頁一 BFF 與並行載入要求沒有新增適用點。符合 `CLAUDE.md`「BFF 與資料來源規範」及 §3.2。 |
| 前端聚合與同義值來源 | 前端 diff 沒有新增聚合、排序、過濾、金融衍生值或數值來源；安全碼對應顯示文字未構成新增業務計算。 |
| DTO、金額、日期與正規化 | `git diff origin/main --name-only` 無 Java DTO、Entity、schema 或 migration 變更。未新增持久化／傳輸金額欄位、冗餘金融事實或衍生值儲存。bridge 的 `CodexAuthSnapshot` 為 `@dataclass(frozen=True, repr=False)`（第 90 行），是 host 內部登入快照，非 Java API DTO。 |
| 業務分類與命名 | 新增的 `AI_AUTH_REQUIRED`／`AI_AUTH_EXPIRED` 是既有錯誤協定白名單的擴充，並非銀行、券商、市場等可管理業務分類；不適用「必須入 DB＋seed＋設定頁」要求。無新增 endpoint、資料表或 Liquibase changeset，因此無新增 URL 命名、版號碰撞、冪等或 master 註冊問題。 |
| 行情資料來源 | diff 未修改 Redis 即時價、`stock_price_history`、TTL 或 tick 寫入流程，沒有新增預設值、`o`／`y` 回填價格路徑。 |
| 登入與執行邊界 | 實際 `rg` 命中 bridge 第 150、156 行以 `O_RDONLY` 讀正常登入；第 484 行投影的 `refresh_token` 為空字串；第 1043、1055 行 proof 與 exec 都使用 `projected_env`，第 1044 行先執行費用檢查。程序群組清理與暫存目錄清理仍留在 bridge，未擴散至前端或 Java 業務邏輯。 |

## 無法查證

本輪依唯讀限制，未執行測試、installer、`spec-check.sh`、容器重建、POST API 或真實模型解讀。因此 Python 91／backend 65／frontend 192 與 build PASS 均屬提供的測試證據，不能視為本審查者重新執行的結果。

正式 host 登入同步、部署後跨層錯誤碼與訂閱解讀仍待作者後續實機驗收。本輪沒有 DB 變更，未查詢運行中資料庫，也未以 changelog 推測 schema 現況。

## 自我挑戰

- 已用實際 `git diff origin/main` 限定新增行為；未把既有 Java HTTP adapter 的放置方式、前端既有 helper 或既有 Controller／SSE 技術債列成本輪 finding。
- 已排除三個誤報陷阱：沒有以外層 DTO class 判違規、沒有把註解中的 `@Data` 當標註，也沒有把 bridge 的期限／大小等非金額變數套用金融金額型別規則。
- 已重新以 `rg -n` 核對 `CLAUDE.md:311–314、350–351` 與 `structure.md` §2.2、§3.2、§8 的明文；沒有另增安全碼必須入 DB、Python 內部快照必須是 Java record 等規定。
- 未形成後撤下的 finding；未保留缺乏新增行證據或規範原文支持的疑點。此判定僅涵蓋本輪架構 diff，不代替正式部署驗收。