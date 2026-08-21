# 開發規範 — 資產管理系統

## 所有回覆，盡量用台灣繁體中文，非不得已才用英文，不得使用其它文字。

## 核心原則：嚴格遵守 AWS KIRO SDD 方法

本專案採用 **AWS KIRO SDD（Spec-Driven Development）** 方法開發。
**每一次功能新增或變更，必須依以下順序執行，不得跳過：**

```
1. spec/requirements.md     → 先確認或新增 User Story + Acceptance Criteria
2. spec/design.md           → 確認架構、資料模型、API 設計已反映變更
3. spec/tasks/tNNN_*.md     → 建立自足任務檔（規範見 spec/tasks/README.md）
4. spec 對抗式審查          → /spec-review（找出問題並修，不打分數、不設通過門檻）
5. 實作程式碼
6. 收尾提交                 → /commit-merge-push（驗收通過後自動執行，不必等使用者開口）
```

> **第 3→4→5 步之間有機器閘門把關，不靠自律。** 寫完 `spec/` 會**立刻**收到跑
> `/spec-review` 的提示；在通過審查並記錄之前，對 `backend/**`／`bff/**`／
> `external-materials-service/**`／`frontend/src/views/*.vue`／`frontend/src/router/**`／
> `db/changelog/**` 的任何寫入都會被擋下。詳見下方
> [SDD 自動閘門](#sdd-自動閘門posttooluse-提示--pretooluse-強制)。

> **第 3 步的任務檔是新制。** Task 201 之後的新任務一律建立獨立的自足任務檔
> `spec/tasks/tNNN_<slug>.md`，不再追加進 `spec/tasks.md`；後者已降為索引，
> 歷史 Task 1–200 凍結在 `spec/tasks/archive/`。詳見 [spec/tasks/README.md](spec/tasks/README.md)。

> **第 4 步的審查必須由另一支 subagent 執行**，寫 spec 的人自己審等於沒審。
> 審查前會先跑 `scripts/spec-check.sh` 取機械證據（編號撞號／重號、changeset 版號碰撞與
> 冪等性、宣稱的測試類是否存在、文件計數漂移）——這些是本專案實際反覆犯的錯，
> 一年內光編號避讓就有 12 次 commit。

> **凡涉及商業邏輯變更（新增、刪除、修改、bug fix），都必須同步更新 spec/**。
> 包含：新 Entity、新 API endpoint、新頁面、新業務邏輯，以及修正既有商業邏輯的 bug fix。
>
> 唯一例外（可不更新 spec）：純樣式 / CSS 微調、純 typo、純 import 整理等不影響功能契約的變更。

### 第 6 步：變更完成後自動 `/commit-merge-push`

**每個 session 只要動過檔案、且變更已驗收通過，就自動執行 `/commit-merge-push` 收尾，
不必等使用者再下一次「commit」「推上去」的指令。** 使用者要的是把事情做完；
留在 worktree 沒進 main 的變更不算做完。

> **這條規則的成因是實際事故，不是潔癖。** 本專案同時有 30+ 個 worktree，寫完沒 commit
> 的實作會躺在某個 worktree 的 `git status` 裡；下次在 main 或別的 worktree 找不到該功能，
> 極容易被誤判成「還沒做」而整套重寫。自動收尾就是讓「寫完」與「進 main」不脫鉤。

**執行方式沿用既有規範**：派 subagent 跑 `/commit-merge-push`（見下方〈Subagent 一律與主
Agent 使用相同模型〉一節，該 skill 沒宣告模型 → 繼承主 agent），主 agent 只負責彙整並向
使用者回報 merge commit SHA。

**先驗收、再收尾。** 順序固定為：實作 → 驗收（涉及可執行的變更就先跑 `/run-stack`，確認
image rebuild + container recreate 後真的 serve）→ `/commit-merge-push`。**驗收沒過不准
merge 進 main**：main 是所有 worktree 共用的分支，壞掉的變更會擴散出去。

**不自動執行的例外（僅此四種）：**

1. 使用者明說「先不要 commit」「暫時不推」——以當下指令為準。
2. 本 session 沒有任何檔案變更（純問答、純查證、純唯讀分析）。
3. 驗收未通過，或 SDD 閘門未過（spec 未審、arch 未查、測試紅燈）——先修好再收尾，不得繞過。
4. 變更明顯只做到一半，且下一步仍在同一 session 內接續——做完整個工作單元再一次收尾，
   不要把半成品推上 main。

> **第 6 步沒有機器閘門，只有這條規範。** 下面的 commit-msg hook 只在「你已經決定要 commit」
> 之後才有話語權，它擋不了「乾脆不 commit」。要硬擋只能攔 `git commit` 或掛 Stop hook，
> 前者會干擾其他 worktree 的提交流程，後者會在多輪對話中每一輪都重複提示——皆不採用。

> **這一步會連帶推上 origin，這是刻意的。** `/commit-merge-push` 的第三段就是 `git push`；
> 且多個 worktree 共用同一份 local `main` ref，變更一旦 merge 進 main，別的 session 推 main
> 時也會一併帶上去。因此「只想留在本機」的變更不要走這條流程，停在 feature 分支 commit 即可。

### Commit-msg Hook（強制 SDD 同步）

本專案內建 commit-msg hook（`scripts/git-hooks/commit-msg`），staged 變更若觸及
controller / model / dto / views / router / db changelog / bff 等「會改變功能或契約」
的檔案，**強制要求同 commit 也包含 `spec/` 變更**，否則阻擋提交。

**首次安裝（每個 clone / worktree 各執行一次）：**
```bash
git config core.hooksPath scripts/git-hooks
```

**例外：**
- 純樣式 / 無商業邏輯影響的調整：commit 訊息加 `[skip-spec]`
- 緊急情況：`git commit --no-verify`（請審慎）

> **hook 只是最低限度的閘門，不要把它當成 spec 品質保證。** 它只檢查「`spec/` 有沒有被碰」——
> 改一個錯字就能過關；而且觸發清單漏了 `service/`、`repository/`、`external-materials-service/**`，
> Task 195 那兩處排程漂移（改的是 service 層的 `@Scheduled`）根本不會觸發它。
> 內容正確性由第 4 步的 `/spec-review` 負責。

### SDD 自動閘門（PostToolUse 提示 ＋ PreToolUse 強制）

commit-msg hook 有兩個先天限制：**時機在 commit 當下**（程式早就寫完了），且**只驗
「`spec/` 有沒有被碰」、不驗有沒有審過**。`.claude/hooks/` 下的三支補的正是這一段。

| 閘門 | 規則 | 時機 |
|---|---|---|
| `notify-spec-changed` | 寫完 spec → **立刻**提示跑 `/spec-review` | 寫入 `spec/` 後 |
| `require-spec-review` | 改了 spec → 必須審過才能寫 code | 寫入實作檔前 |
| `notify-code-changed` | 寫完 code → 提示派 `arch-auditor` 查架構符規 | 寫入實作檔後 |
| `commit-msg` | 改了 code → 必須有 spec | commit 時 |

> **`notify-code-changed` 刻意只提示、不擋 commit。** 要硬擋只能攔 `git commit`，
> 那會干擾到其他 worktree 的提交流程（本專案同時有 30+ 個 worktree）。
> 查證完由 `bash .claude/hooks/arch-review-pass.sh` 記錄；紀錄綁定**實作程式碼的
> 內容雜湊**，code 再變動即失效並重新提示。逃生門 `SKIP_ARCH_GATE=1`。
>
> `arch-auditor` 與 `spec-auditor` 互補：前者在實作**後**審程式碼是否違反架構鐵則，
> 後者在實作**前**審規格的可查證性。兩者都唯讀，都由主 agent 在報告產出後負責修。
> **`arch-auditor` 是 diff-scoped**，呼叫時必須餵本次變更的完整 diff——不餵它會退化成
> 全樹掃描，把既有技術債整包倒出來，淹掉真正的新增違規。

**spec 那一組為什麼要兩支。** 只有硬擋的話，是「撞到牆才回頭審」——中間可能已經想好
一整套實作方向。在 spec 落地當下就提示，順序才對（審完再想怎麼寫）。提示每個 spec
變更週期只出現一次，連改五個 spec 檔不會被唸五次。

四者刻意不重疊。**完全沒動 `spec/` 的純 bug fix / 樣式調整不會被任何閘門擋下**
（那種情境由 commit-msg 負責），閘門只在真正的 SDD 週期裡收緊，避免變成人人繞道。

> **唯一的例外是 `notify-code-changed`。** 它以「有沒有寫實作檔」為觸發條件，
> 不看 `spec/` 動了沒——因為純 bug fix 一樣能違反架構鐵則（往 controller 塞一個
> Repository 就是）。代價是純 bug fix 也會收到一次提示。之所以可接受：它**不阻斷**、
> 且每個變更週期只出現一次。真的不想被提示就 `SKIP_ARCH_GATE=1`。

> **hook 叫不到 subagent。** hook 是 shell 指令，無法直接執行 `spec-auditor`／
> `arch-auditor`——它只能提示模型去跑。唯一的替代是在 hook 裡跑 headless `claude -p`，
> 但單次審查約 6～7 分鐘，每次存檔都同步卡住不可接受，故不採用。

審查通過後由 `/spec-review` 的 Step 4 記錄：

```bash
bash .claude/hooks/spec-review-pass.sh          # 記錄通過
bash .claude/hooks/spec-review-pass.sh --status # 查目前狀態
```

紀錄綁定**當下 `spec/` 的內容雜湊**；spec 之後再被改動即自動失效、需重審——
否則「審過一次就永久放行」等於沒擋。

**例外：** `SKIP_SPEC_GATE=1`（例如只是回填任務完成報告）。

> 無須手動安裝，`.claude/settings.json` 已掛載。但**修改該檔後需重啟 session 才生效**。
> 另注意 `core.hooksPath` 設的是**絕對路徑**、指向主 clone；改 `scripts/git-hooks/`
> 底下的檔案要 merge 進 main 後才會實際生效。

### Subagent 一律與主 Agent 使用相同模型（例外：skill 可自行釘住模型；前端 Vue 變更固定模型）

**所有 subagent**（包含實作、`spec-auditor`、`arch-auditor`，以及任何臨時派出的
subagent）一律使用與當前主 agent **完全相同的模型與 reasoning effort**。
禁止因任務較簡單、成本、速度或 fallback 而改用較低階模型。執行環境支援繼承時，
省略 subagent 的 model／effort override；若工具要求明確指定，則兩者必須與主 agent
一致。相同模型無法使用時應停止並回報，不得靜默降級。

**例外一、例外二共用同一組固定模型，全專案的「模型例外」只有一種標準，不分開
各自維護一組值：**

| Harness | 固定模型 / effort |
|---|---|
| Claude Code | `sonnet 5` ／ `high` |
| Codex | `gpt-5.6-terra` ／ `high` |

**這組固定值本質上是刻意的降規格，目的就是省錢，不是妥協。** 若跟著主 agent 當下
用的模型連動調整，例外就失去意義——兩項例外的重點正是「不論主 agent 開多高規格，
這類工作一律固定用這組較低規格」。與最上方「禁止因成本考量而降階」不衝突：那條
規則管的是任意、未宣告的降級，這裡是全專案唯一、白紙黑字寫明的降規格標準，套用
時不得再各自加碼或減碼。

**例外一：skill 可在自己的定義檔裡指定特定模型。** skill 自行宣告的模型／effort
**優先於**上述繼承規則——那是 skill 作者針對該工作負載的刻意選擇，不算降級。
例外只涵蓋「skill 定義檔裡的宣告」：skill **執行過程中**再派出去的 subagent，
仍須沿用該 skill 當下的模型，不得再往下降；未宣告模型的 skill 一律繼承主 agent。

目前只有 `/run-stack` 用到這個例外，宣告位置：
- Claude Code：`.claude/skills/run-stack/SKILL.md` frontmatter `model:` ／ `effort:`
- Codex：`.agents/skills/run-stack/SKILL.md` 內文（frontmatter 不支援）

> **兩邊的強制力不同，別當成同一回事。** Claude Code 的 skill frontmatter 由 harness
> 直接套用（parser 會驗 `effort`，合法值 `low｜medium｜high｜xhigh｜max`）；Codex 的
> skill loader 只認得 `name`／`description`／`metadata`／`interface`／`dependencies`／
> `policy`／`agents`／`assets`，**沒有 `model` 欄位**，寫了也會被忽略，因此只能在內文
> 要求用 `spawn_agent` 帶 `model` 參數——那是指令引導，不是硬性保證。

**例外二：新增、修改、刪除前端程式（Vue）時，開的 subagent 固定使用上表的模型，
不繼承主 agent、不隨主 agent 當下用什麼模型而變動。**

**第 5 步「實作程式碼」不得由主 agent 直接動手，一律開 subagent 執行**，完成後
回到主 agent 彙整結果（驗收、跑閘門、commit）：

**唯一的例外是驗收失敗的收尾。** 例外一／例外二派出的 subagent，模型可能低於主
agent（取決於主 agent 當下實際跑哪個模型）。這類較低模型 subagent 做完後，若主
agent 驗收沒過，不得重新派工給同一顆（較低模型）subagent 反覆重試——問題很可能
出在模型能力，重試不會有不同結果——應由主 agent 直接接手重做。同模型繼承的一般
subagent 驗收不過，仍應改進 prompt 後重新派工，不得由主 agent 代勞。

主 agent 的職責限於：拆解任務、撰寫 subagent prompt、彙整回報、查證 subagent
宣稱的變更（注意：subagent 回報的絕對路徑常指向主 repo 而非 worktree，
落地前先用 `git -C <worktree路徑> status/diff` 確認變更真的落在 worktree）。

**`/run-stack` 與 `/commit-merge-push` 也一律派 subagent 執行。** 主 agent 在 prompt 裡
帶入該 skill 的完整流程與本次變更脈絡（改了哪個 service、預期驗證點），subagent 執行完
回報結果（stack 是否 serve、merge commit SHA），由主 agent 向使用者彙整。模型則看該
skill 有沒有自己宣告：`/commit-merge-push` 沒宣告 → 繼承主 agent；`/run-stack` 有宣告
→ 用它宣告的那組。

> **派工工具設不了 effort 時，直接叫 skill、不要硬派。** 部分執行環境的 Agent 工具只有
> `model` 參數、沒有 effort，這時派出去的 subagent 拿不到 skill 宣告的 effort。與其派一個
> effort 錯的 subagent，不如由主 agent 直接觸發該 skill——讓 harness 自己套用 frontmatter。

---

## 技術棧

### Backend
- Java 21 + Spring Boot 3.4.4（CLAUDE.md 原標「Java 25」為未來目標；pom.xml 與 Dockerfile 目前實際為 21）
- Spring Data JPA（H2 開發 / PostgreSQL 生產）
- Apache POI（Excel 匯入）
- Lombok
- `ddl-auto: none` + Liquibase（資料庫 Schema 版本管理）

### Frontend
- Vue 3 + Vite 5
- Element Plus（UI 元件）
- ECharts（圖表）
- Pinia（狀態管理）
- Axios（API 呼叫）
- unplugin-auto-import（自動匯入 ref/computed/watch 等）

---

## 架構規範

### Clean Architecture 原則
程式架構必須遵守 Clean Architecture 原則，核心是**依賴方向一律由外向內**（框架 / IO → 介面轉接 → 業務邏輯 → 領域模型），內層不得知道外層的存在：

- **分層職責**：Controller 只做 HTTP 收發與 DTO 轉換；業務邏輯一律放 Service 層；資料存取一律經 Repository。禁止 Controller 直接注入 Repository、禁止在 Controller 寫業務規則。
- **依賴反轉**：業務邏輯依賴抽象（interface），不依賴具體實作。外部系統（行情 API、Redis、Excel 解析）以介面隔離，實作放在最外層，方便替換與測試。
- **領域模型獨立**：Entity / 領域物件不得依賴 Web 層（HttpServletRequest、DTO 等）；DTO 與 Entity 分離，不得把 JPA Entity 直接當 API 回傳格式。
- **跨層禁令**：前端不得直接呼叫 business service（一律走 BFF，見下方 BFF 規範）；business service 不得直連外部行情 API（即時價走 Redis、收盤價走 `stock_price_history`）。
- **可測試性**：業務邏輯必須能在不啟動 Spring context、不連資料庫的情況下單元測試。

### 資料庫完整正規化
相同的資料只能存一份。禁止：
- 同一欄位同時以 FK 和字串冗餘儲存（如 `bank_id` + `bank_name`）
- 存入可從其他欄位計算得出的衍生值（如 `profit = currentValue - investmentCost`）
- 跨資料表重複儲存同一事實

例外（刻意的 denormalization，需加註說明）：
- 歷史快照的匯總欄位（`asset_snapshot` 的 `total_*`，供歷史回溯）
- 歷史交易記錄中的名稱字串（如 `realized_gain.broker`，記錄成交當下的券商名稱）

### 禁止 Enum 寫死
所有業務分類（銀行、券商、存款類型、市場類型）**必須存入資料庫**，由 `DataInitializer` 提供 Seed Data，並提供 `/api/settings/*` 管理端點與前端設定頁面。

### 零遷移策略
欄位從 Enum 改為 String 時，因 `@Enumerated(EnumType.STRING)` 原本就以 VARCHAR 儲存，無需 DB Migration。`DepositTypeEntity.code` 與 `MarketType.code` 即為寫入欄位的字串值。

### API 命名規則
- 資源操作：`GET/POST/PUT/DELETE /api/{resource}`
- 軟停用：`PATCH /api/{resource}/{id}/active`
- 設定管理：`/api/settings/{resource}`
- 市場資料：`/api/market-data/{action}`

### BFF 與資料來源規範

**1. 一個前端頁面一個 BFF**

每個前端頁面對應一支獨立的 BFF controller（或 route 設定），路徑前綴 `/api/bff/{page-name}/...`。
- 前端 view 一律走自己頁面對應的 BFF endpoint，不直接呼叫 business service `/api/{resource}`
- BFF 負責跨服務 aggregation、預先計算 / 排序 / 過濾，前端只負責 render
- 範例：`DashboardBffController`、`SnapshotFormBffController`、`AssetHistoryBffController`、`BankSettingsBffRoutes`（純 passthrough 也要有自己的 route）
- **具名、限縮例外（Requirements 66–68／70／71／77／78／79／86；Tasks 317、325、327、328、329、336、337、338、347）**：Docker 外部 HTTP 只能從 non-root Nginx `api-gateway` 的 loopback `127.0.0.1:9090` 九條路由（八條唯讀 GET ＋ 一條寫入 POST `/api/public/crawler-data/rescan`，見 Requirement 71）進入；BFF 與 external-materials-service 都不映射 host port。BFF 只對 `GET /api/public/market-index`、`GET /api/assets/latest`、`GET /api/public/exchange-rate/usd-twd`、`GET /api/public/market-analysis/today`、`GET /api/public/portfolio-advice/latest`、`GET /api/public/trading-radar/today` 與 `POST /api/public/crawler-data/rescan` 匿名放行，quotes 由 Nginx 直接送 external-materials；禁止 `/api/**`／descendant wildcard 與同路徑其他 method。`/api/public/crawler-data/rescan` 是唯一有外部抓取副作用的例外，經 business 端 30 秒全域 Redis 冷卻節流，語意等同 `fetchAndExportNow()` 的匿名版本。交易雷達公開端點固定以 configured-admin 為 owner，必須清除 Reactor 呼叫者身分並顯式傳遞 tenant headers，只能走不寫入 snapshot／Redis／DB 的 current-read 路徑。Frontend 對九路 exact／matrix 變體回 404，view 不得援引此例外。Controller 仍只委派 service，BFF 不直查 DB／外部行情。USD/TWD 的台銀／兆豐／Yahoo 外部抓取、交易時段判定與每 2 秒 Redis producer 只能位於 `external-materials-service`；business/BFF 只做唯讀 cache/DB 與聚合。

**2. 同義欄位、同一 business service API**

不同頁面顯示「同樣意義的值」時，BFF 必須呼叫**同一支 business service API**取得，避免值在不同頁面不一致。
- 例：股票即時 K/D/季線 → 兩個頁面都透過 `TechnicalIndicatorService.computeAll()`
- 例：snapshot 預估配息 → 各頁面都讀 `asset_snapshot.estimated_annual_dividend`（不要前端各自重算）
- 例：股價收盤值 → 一律從 `stock_price_history` 抓
- 共用邏輯抽到 `bff/common/`（如 `SnapshotEnricher`），各 BFF controller 注入使用

**3. Docker 外部 API 一律經 Nginx 9090 gateway（八條唯讀 GET ＋ 一條寫入 POST）**

- Host 只綁 `127.0.0.1:9090`，精確放行 `GET /api/quotes`、`/api/quotes/one`、
  `/api/public/market-index`、`/api/assets/latest`、`/api/public/exchange-rate/usd-twd`，
  第六條 `POST /api/public/crawler-data/rescan`（Requirement 71 / Task 329；唯一有外部
  抓取副作用的例外，免登入觸發 NewsPoller 重新搜尋，經 business 端 30 秒全域 Redis 冷卻節流），
  以及第七條 `GET /api/public/market-analysis/today` 與第八條
  `GET /api/public/portfolio-advice/latest`（Requirement 79 / Task 338；純唯讀零副作用，
  owner 走 configured-admin bootstrap），以及第九條 `GET /api/public/trading-radar/today`
  （Requirement 86 / Task 347；owner 同樣走 configured-admin，且不得寫入匯出 snapshot）。
- `bff` 與 `external-materials-service` 不發布 host port；`frontend:80` 對上述九條回 `404`，
  瀏覽器登入 API 與 SPA 仍經 frontend → BFF。
- Tailscale Serve 只以 path-scoped HTTPS `:9090` 掛相同九條 exact path，包含 USD/TWD 公開匯率
  與第六條寫入路由。禁止 root／`/api/` proxy、Funnel、自簽憑證與另一層 OAuth proxy。
- 每一條掛載到 9090 的 API 都必須在 `docs/openapi/docker-external-api.yaml` 提供完整、可驗證的
  OpenAPI 3 契約；路徑、HTTP method、參數、成功與錯誤回應、所有可達巢狀 schema、必填／nullable
  語意與安全邊界都不得省略。Gateway allowlist 與 OpenAPI paths 必須由自動化 contract test 雙向比對，
  禁止先上線再留下缺漏或過期 Swagger。

### 服務啟動
```bash
# 後端（port 8080）
cd backend
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn spring-boot:run

# 前端（port 5173）
cd frontend
/Users/steven/.nvm/versions/node/v22.21.0/bin/node \
  /Users/steven/.nvm/versions/node/v22.21.0/bin/npm run dev
# 或直接執行 vite：
/Users/steven/.nvm/versions/node/v22.21.0/bin/node ./node_modules/.bin/vite
```

---

## Spec 文件位置

| 文件 | 說明 |
|------|------|
| `spec/requirements.md` | User Stories + Acceptance Criteria（89 個 Requirements；Requirement 87／89 由在途 worktree 保留，最新為 91） |
| `spec/design.md` | 架構圖、ERD、API 端點、關鍵業務邏輯 |
| `spec/tasks.md` | 任務索引（Task 1–228、264–267、269–292、297–309、311–342、344–347、349）＋尚未歸檔的 Task 201 起區段；Task 229–263、268、293–296 以各自 `spec/tasks/tNNN_*.md` 為準；Task 348／350 由在途 worktree 保留，main 的 Task 351（交易日曆）與 Fubon Task 352–353 以各自任務檔為準 |
| `spec/tasks/README.md` | 自足任務檔規範（新任務寫這裡，不再追加 `tasks.md`） |
| `spec/tasks/tNNN_*.md` | 自足任務檔（Task 201 之後的新任務） |
| `spec/tasks/archive/` | Task 1–200 歷史，已凍結不再修改 |
| `spec/steering/` | 長期 context：`product.md` / `tech.md` / `structure.md` |

| 工具 | 用途 |
|------|------|
| `scripts/spec-check.sh` | spec 變更的機械前置檢查（撞號／重號／changeset／計數漂移） |
| `/spec-review` | 實作前的獨立對抗式審查（產出 findings，不打分數；critical／major 修完即可開工） |
