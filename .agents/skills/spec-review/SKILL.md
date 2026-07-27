---
name: spec-review
description: 對 asset-management 的 spec/ 變更做獨立對抗式審查，找出實作前該修的問題。先跑 scripts/spec-check.sh 取機械證據，再派一支唯讀 subagent 逐項查證並列出 findings（不打分數、不設通過門檻）。當使用者說「審 spec」「spec review」「spec 寫好了」「可以開始實作了嗎」「檢查規格」之類指令、或剛改完 spec/requirements.md／design.md／tasks 任務檔而尚未動 code 時使用。審查者一律不得修改任何檔案。
---

# Spec 對抗式審查（實作前閘門）

SDD 流程的第 4 步（實作）之前的閘門。目的**不是**檢查「需求寫得完不完整」——本專案稽核 64 處 spec 不一致時，**沒有一處是「規格寫了但沒實作」**（Task 197）。真正的缺陷全部是**可查證性**問題：規格宣稱了程式碼裡不存在的東西、文件自相矛盾、編號撞號、計數漂移。審查火力照這個事實分配。

> 觸發詞：審 spec、spec review、spec 寫好了、可以開始實作了嗎、檢查規格、規格審查。

## 鐵則

1. **審查者不是作者。** 一律用 Agent tool 另起一支 subagent 執行審查。主 agent 剛寫完 spec 就自己審＝沒有獨立性，這一關等於沒跑。
2. **唯讀。** 審查者禁止 Edit / Write 任何檔案，包含「順手修個 typo」。輸出只有報告。修正由主 agent 在報告產出後執行。
3. **只找問題，不做摘要。** 不要複述 spec 寫了什麼。沒問題的維度就寫「已查 X，無發現」，不要用篇幅充數。
4. **機械證據壓過判斷。** `scripts/spec-check.sh` 報 BLOCK 就是已證實的缺陷，LLM 不得以主觀判斷繞過。
5. **不打分數、不設通過門檻。** 產出的是 findings 清單，由主 agent 判斷哪些該修、哪些是誤判。**critical 與 major 修完即可進入實作**，minor 可留待後續。重審最多 3 輪；第 3 輪仍在爭同一件事就停下來問使用者，附上「審查者反覆指出什麼 vs 修正者反覆做了什麼」的分歧摘要，不要無限迴圈。

---

## Step 0 — 機械前置檢查

```bash
bash scripts/spec-check.sh              # 預設比對 origin/main
```

先跑，把輸出整段交給審查者當輸入。它涵蓋**不需要判斷力**的部分：Task／Requirement 編號撞號與重號、Liquibase changeset 版號碰撞／非冪等／未註冊、spec 宣稱的測試類不存在、`@Scheduled` 變更未同步排程登錄表、文件計數漂移。

有 BLOCK 就是已證實的缺陷，審查者不必再花力氣重查，直接納入報告並套用分數上限。

> `scripts/spec-check.sh` 與 commit-msg hook 是**兩個不同的閘門**。hook 只檢查「`spec/` 有沒有被碰」，改一個錯字就過關；而且它的觸發清單漏了 `service/`、`repository/`、`external-materials-service/**`——Task 195 那兩處排程漂移根本不會觸發 hook。這支 skill 補的正是 hook 看不到的那一面。

## Step 1 — 圈出審查範圍

```bash
git diff origin/main...HEAD --stat -- spec/
git diff origin/main...HEAD -- spec/          # 未 commit 的變更另跑 git diff -- spec/
```

只審**這次變更的切片**，不是整份 10000 行的 spec。審查者要拿到完整 diff，不是摘要。

## Step 2 — 派唯讀 subagent 審查

用 Agent tool（`subagent_type: Explore` 或 `general-purpose`），把下列內容交給它：Step 0 的完整輸出、Step 1 的完整 diff、以及下面整套 rubric 與規則。要求它**逐維度**回報，每個維度都要有具體證據（檔案:行號、identifier、實際 grep 結果），不接受「大致沒問題」。

### 審查維度（依重要性排序）

| 維度 | 權重（僅代表查證力度分配，不用於計分） | 查什麼 |
|---|---|---|
| **可查證性** | 0.30 | diff 裡描述**既有事實**的 CamelCase 類名、`/api/...` 路徑、`.vue` 檔名、方法名，逐一 grep 全樹是否存在。前例：Task 61 宣稱的 `WatchListService`／`WatchListController`／`/api/watch-list` **四項全部從未存在**；`design.md` 的 `UsaMap`（實為 `UsFlag`）、Gateway `GlobalFilter`（實為 `WebClientConfig.tenantHeaderFilter()`）、`FundDividendSourceQuery`（實為 `FundNavSourceQuery`）。**新功能尚未實作的識別字合法**——要區分「對現況的錯誤斷言」與「對未來的正當描述」。 |
| **內部一致性** | 0.25 | 同一份文件的表格 vs 敘述、標題 vs 內文、ERD vs 表清單是否打架。前例：`design.md` 的 API 表仍列 Task 179 已移除的 `webSearchMaxUses`，而**同一節的敘述已寫「Task 179 移除」**；Requirement 31 標題寫「每交易日 08:45」但 AC 內文早已改為可設定。 |
| **架構鐵則** | 0.25 | 見下方檢查表。違反 `CLAUDE.md`／`spec/steering/structure.md` 的既有規範。 |
| **資料來源正確性** | 0.20 | 即時價只能從 Redis、收盤價只讀 `stock_price_history`、business-services 不直連外部行情 API；對 DB 現況的斷言必須引用 `db/schema.sql` 而非 `db/changelog/**`（照永不執行的 changelog 改 entity，反而會把原本正確的改成錯的——Task 148→197→201 花了 53 個 Task 才發現）；已改為 DB 可設定的排程時點不得在 spec 裡寫死成字面時間（Task 195：「寫死正是本次漂移成因」）。 |

### 架構鐵則檢查表（是非題，逐條答）

- Controller 是否被描述為直接注入／讀取 Repository，或在 controller 內做外部 HTTP、upsert、領域判斷？
- 新增／修改的 DTO 是否為不可變 `record`？出現 `@Data`／setter 即違規。
- 新增前端頁面時，是否**同時**新增該頁專屬的 `/api/bff/{page-name}/...`？（純 passthrough 也要建）
- 前端是否被描述為呼叫**別頁**的 BFF、或直打 `/api/{resource}`、或裸 `fetch`／`EventSource` 繞過 `api/index.js`？
- diff 若以「共用／慣例例外」為跨頁呼叫辯護，該例外是否**明文寫在** `CLAUDE.md` 或 `structure.md` 3.2？沒有就是自己發明的（前例：`RealizedGainBffRoutes` 的「共用 CRUD」正當性早已不成立，該 route 零消費者）。
- 同一語意的值，是否讓不同頁面走**同一支** business API？
- 是否 frontend 直打 business-services／business 直連外部行情 API／external-materials 反向呼叫 business？
- 是否同一欄位以 FK 與字串冗餘並存、或存入可計算的衍生值？若主張 denormalization 例外，有沒有附理由？
- 新的業務分類是否入 DB ＋ `DataInitializer` seed ＋ `/api/settings/*` ＋ 前端設定頁？（硬編 enum 即違規）
- 新 endpoint 是否符合 `GET/POST/PUT/DELETE /api/{resource}`、軟停用用 `PATCH /{id}/active`、URL kebab-case？

### 計分

每條 finding 標 severity：

- **critical** — 架構鐵則違反，或對現況的錯誤斷言（會讓實作者做錯事）
- **major** — 會讓實作者踩坑或漏做，但不至於做錯方向
- **minor** — 措辭、遺漏的補充、可讀性

**自我挑戰（必做，寫進報告）：** 「我這些 findings 裡，哪幾條其實是我查錯或誤判？」——逐條回頭驗一次再送出，寧可少報也不要報錯。

### 報告格式

```
## 判定
critical: N   major: N   minor: N
（critical 與 major 全部修完即可進入實作）

## Findings
### [Critical|Major|Minor] <一句話結論>
位置：<file:line>
證據：<實際 grep 結果／引用的原文／spec-check 輸出>
修法：<具體到可直接執行>

## 已查無發現
<逐維度列出查了什麼、用什麼方式查的>

## 自我挑戰
<為什麼不該再低 2 分>
```

## Step 3 — 處理結果

- **≥ 8**：通過，可進入實作。
- **< 8**：主 agent 依 findings 修 spec，**修完重跑 Step 0–2**（重派新的 subagent，不要叫同一支自己確認自己）。
- **第 3 輪仍未過**：停。回報使用者，附分歧摘要，不要繼續燒。

## 完成判準

- `scripts/spec-check.sh` 零 BLOCK。
- 審查由**另一支 subagent** 產出，不是主 agent 自評。
- 報告含逐維度證據與自我挑戰段落，不是一句「看起來沒問題」。
- critical 與 major 已修完（或已判定為誤判並說明理由）。
- 審查過程零檔案修改。

## 不要做

- 不要自己審自己剛寫的 spec。
- 不要在審查途中順手改檔——連 typo 都不行，那會讓「唯讀」這條失效。
- 不要重做 commit-msg hook 已做的事（「`spec/` 有沒有被碰」），直接從「碰了什麼、碰對了沒」起跑。
- 不要因為「這次改動很小」就跳過。本專案最貴的三次稽核（Task 183／197／201）修的全是當初看起來很小的改動。
- 不要把 `spec/requirements.md` 的 `- [ ]` 當成「未實作」的證據——該檔 509 個 `[ ]` 對 56 個 `[x]`，checkbox 在那份文件裡不具完成語意（見該檔開頭的文件慣例說明）。
