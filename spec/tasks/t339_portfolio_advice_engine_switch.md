# [t339] 資產配置建議三態引擎切換：完全本機／部分打 API／現行全 LLM

**對應 Requirements:** Requirement 80（資產配置建議的三態引擎切換——完全本機、部分打 API、或維持現行全 LLM）
**前置任務:** 無（與 t336、t337 可並行。t336 改的是「今日股市分析」，本任務改的是「資產配置建議」，兩支 service 完全獨立）
**Liquibase changeset:** `v1.106.0-portfolio-advice-engine.sql`（`v1.105.0` 已由 t336 佔用）

---

## 背景

### 現在的行為

`backend/src/main/java/com/steven/assets/service/PortfolioAdviceService.java`（1053 行）的 `generate(InvestmentProfileInput in)` 走同步 Messages API（**非 Batch，無 50% 折扣**）：

- `model` 預設 `claude-opus-4-8`、`MAX_TOKENS = 16000`、`ThinkingConfigAdaptive`、`OutputConfig.effort` 預設 `medium`
- 掛 `WebSearchTool20260209`，`maxUses` 預設 4（`DEFAULT_WEB_SEARCH = 4`），**每次搜尋另計費**
- 形狀為非同步：先落 `PROCESSING` 列 → 背景執行緒跑 `runGeneration(...)` → 前端輪詢

**觸發方式是純手動**：`generate(...)` 的唯一呼叫點是 `PortfolioAdviceController:87` 的 POST，全樹無 `@Scheduled` 觸發。故總成本取決於使用者按幾次，與「今日股市分析」那種每日無人值守的固定成本性質不同。

### 為什麼可以做三檔位

**本機化的基礎已經存在一半**：

| 已是本機決定性計算 | 位置 |
|---|---|
| 現有配置三類的金額與占比 | `PortfolioAdviceService.getCurrentAllocation():242-256`，讀最新 `asset_snapshot` 的 `total_deposit`／`total_fund_value`／`total_stock_value` |
| 退休現金流逐年試算 | `getProjection():265-280` → `RetirementProjectionService.project(profile, startAssets, expenses)` |
| `targetAmount` 與 `deltaAmount` | 既有後端決定性回填 |

`backend/src/main/java/com/steven/assets/dto/PortfolioAdviceResult.java` 的 class javadoc 已逐字寫明：

> 其中 `targetAmount`／`deltaAmount` 由後端以「資產總額 × 目標%」決定性回填（LLM 只給比例與分類），**金額算術不交給 LLM**。

所以 LLM 目前實際貢獻的只有：目標比例的挑選、各段敘事文字、以及 `web_search` 帶進來的總經環境與 `references`。

### ⚠ 本機配置模板是常見經驗法則，不是個人化投資建議，也未經回測

`local` 與 `hybrid` 兩檔的目標比例由「風險承受度 × 距退休年數」套一組明示對照表產生，屬廣為流傳的資產配置經驗法則（如股債比隨年齡遞減），**未經任何回測或個人情境驗證**。

此定性須：
1. 出現在本機引擎類別的 class javadoc
2. 出現在前端頁面的常駐說明
3. **輸出 `warnings` 的首條固定為該聲明**

不得讓使用者誤以為本機檔位的建議具有與完整 LLM 版相同的個人化程度。

---

## 要做什麼

### 資料庫現況（已於 2026-08-15 對運行中 DB 實查，直接照用）

`portfolio_advice_setting`（單列）：

| Column | Type | Nullable |
|---|---|---|
| `id` | integer | not null |
| `model` | varchar(64) | not null |
| `effort` | varchar(16) | not null |
| `web_search_max_uses` | integer | not null |
| `updated_at` | timestamptz | not null |

**沒有 `engine` 欄位。**

`portfolio_advice`（建議結果表）欄位：`id`、`owner_user_id`（not null）、`status varchar(20)`（not null）、`model varchar(64)`、`created_at timestamptz`（not null）、`completed_at timestamptz`、`error_message varchar(1000)`、`age integer`、`investment_horizon_years integer`、`monthly_investment numeric(20,2)`、`goals varchar(300)`、`risk_tolerance varchar(20)`、`expected_annual_return varchar(20)`、`based_on_snapshot_id bigint`、`based_on_snapshot_date date`、`based_on_total_assets numeric(20,2)`、`raw_response text`、`result_json text`。

`investment_profile` 本任務會用到的欄位：`owner_user_id bigint`（not null）、`risk_tolerance varchar(20)`、`retirement_date date`、`birth_date date`（其餘退休試算相關欄位由既有 `RetirementProjectionService` 使用，本任務不直接碰）。

**Liquibase**：運行中 `databasechangelog` 最新為 `v1.104.0-realized-gain-export-schedule-multi-time`；`v1.105.0` 由 t336 佔用，故本任務用 **`v1.106.0`**。建檔前重查一次，若已被其他 worktree 佔用，須連同檔名、changeset id 與本檔一次調整。

- [x] **339.1 Liquibase changeset `v1.106.0-portfolio-advice-engine.sql`**

    ```sql
    ALTER TABLE portfolio_advice_setting ADD COLUMN IF NOT EXISTS engine VARCHAR(16) NOT NULL DEFAULT 'local';
    UPDATE portfolio_advice_setting SET engine = 'local' WHERE engine IS NULL OR engine = '';
    ```

    在 `backend/src/main/resources/db/changelog/db.changelog-master.yaml` **最尾端**註冊（格式：`- include: { file: db/changelog/changes/<檔名>, relativeToChangelogFile: false }`）。**changeset 落地後不得再修改其內容（含註解）**——Liquibase checksum 含註解，改了會 `ValidationFailed` 導致 business crash loop。

- [x] **339.2 `PortfolioAdviceSetting` entity 與白名單常數**

    `backend/src/main/java/com/steven/assets/model/PortfolioAdviceSetting.java` 新增 `@Column(name = "engine", length = 16, nullable = false) private String engine;`。

    `PortfolioAdviceService` 新增（比照既有 `AVAILABLE_MODELS:87`／`AVAILABLE_EFFORTS`／`AVAILABLE_WEB_SEARCHES:101` 的技術白名單慣例）：

    ```java
    private static final List<PortfolioAdviceSettingsDto.EngineOption> AVAILABLE_ENGINES = List.of(
            new PortfolioAdviceSettingsDto.EngineOption("local",  "完全本機（免費）"),
            new PortfolioAdviceSettingsDto.EngineOption("hybrid", "本機計算 ＋ AI 撰寫敘述（省錢）"),
            new PortfolioAdviceSettingsDto.EngineOption("llm",    "完整 AI 分析（含網路搜尋，最貴）")
    );
    private static final String DEFAULT_ENGINE = "local";
    ```

    新增 `resolveEngine()`，比照既有 `resolveEffort():319` 的三段式（讀單列設定 → 白名單過濾 → 後備常數）。

- [x] **339.3 `PortfolioAdviceSettingsDto` 契約擴充**

    現有 record 為 `PortfolioAdviceSettingsDto(String model, String effort, Integer webSearchMaxUses, List<ModelOption> availableModels, List<EffortOption> availableEfforts, List<WebSearchOption> availableWebSearches)`（由 `getSettings():333-350` 的建構呼叫確認）。新增 `String engine` 與 `List<EngineOption> availableEngines`，並新增巢狀 `public record EngineOption(String id, String label) {}`。所有建構呼叫端一併更新。

    **`PortfolioAdviceController.updateSettings`（`backend/src/main/java/com/steven/assets/controller/PortfolioAdviceController.java:99-107`）須一併解析 `engine`**。現況為：

    ```java
    return adviceService.updateSettings(
            str(body, "model"),
            str(body, "effort"),
            intOrNull(body, "webSearchMaxUses"));
    ```

    新增第四個引數 `str(body, "engine")`。**漏掉這一步，前端切不了引擎**（BFF `PortfolioAdviceBffController:112-117` 是原封 Map passthrough，`engine` 進得到 business，卡點只在 controller）。

    `getSettings()` 沿用既有「現值若不在白名單則補入清單開頭」防呆（既有三處已有此寫法，照抄）。`updateSettings(String model, String effort, Integer webSearchMaxUses, String engine)` 新增第四個參數，維持既有「null 表示該欄不變、至少須提供一項」語意（既有訊息「未提供任何可更新的設定（model / effort / webSearchMaxUses）」須同步補上 engine）；不支援的 engine 值拋 `IllegalArgumentException("不支援的分析引擎：" + engine)`。

- [x] **339.4 新增 `LocalPortfolioAllocationEngine`（`backend/src/main/java/com/steven/assets/service/LocalPortfolioAllocationEngine.java`）**

    **配置模板核心必須是純函式**：接受 `riskTolerance`（String）與 `yearsToRetirement`（Integer，可為 null），回傳三類的目標比例。

    **`riskTolerance` 的合法值只有三個**（`PortfolioAdviceService.java:125-129` 的既有 `InvestmentProfileDto.Option` 清單，欄位為 `investment_profile.risk_tolerance varchar(20)`，**可為 null**）：

    | 代碼 | 標籤 |
    |---|---|
    | `CONSERVATIVE` | 保守（不能忍受本金明顯虧損） |
    | `BALANCED` | 穩健（可承受中度波動） |
    | `AGGRESSIVE` | 積極（可承受較大波動追求高報酬） |

    **不是 `LOW`／`MEDIUM`／`HIGH`**——猜錯會讓對照表永遠 miss、靜默落到最保守檔，而純函式測試仍然會過。`null` 時採**最保守檔**。**不注入 Repository、不做 IO、不讀時鐘**（距退休年數由呼叫端算好傳入）。

    對照表須為**具名常數**、可單元測試，每一格都能對應到輸出 `targetAllocation[].rationale` 的一句中文理由。

    **三類名稱必須與 `getCurrentAllocation()` 既有的三類逐字一致**：`"存款（現金）"`、`"信託基金"`、`"股票"`（見 `PortfolioAdviceService:252-254` 的 `new CurrentAllocationDto.Item(...)` 三行）。**不得自創第四類或改名**——否則 `currentValue` 對不上、`deltaAmount` 失去意義。

    距退休年數的推算：`investment_profile.retirement_date` 減今日；`retirement_date` 為 null 時退回以 `birth_date` 推算（假設退休年齡為具名常數）；兩者皆 null 則採**最保守檔**。

- [x] **339.5 三檔位的語意，逐檔明確且互不重疊**

    - **`local`（完全本機、零 API）**
      - 全部欄位由本機規則產生，**不建立 `AnthropicClient`、不發任何外部請求**
      - `references` 固定回空陣列
      - **不得檢查 `ANTHROPIC_API_KEY`**——無金鑰時仍須正常產出。
        **注意分岔點的位置**：既有金鑰檢查在 `generate(...)` 本體、建 prompt 之前：

        ```java
        // PortfolioAdviceService.java:441-447
        if (apiKey == null || apiKey.isBlank()) {
            …
            row.setStatus(PortfolioAdvice.STATUS_NOT_CONFIGURED);
            …
            return save(row);
        }
        ```

        這與 t336 的情形**不同**——`MarketAnalysisService` 的金鑰檢查在 `submitBatch`（LLM 分支）內部，天然不影響 local。本頁若把 engine 分岔放在 `:441` **之後**，`local` 檔位在無金鑰時會落 `NOT_CONFIGURED`，339.14(d) 必定不過。**engine 分岔須置於該金鑰檢查之前；檢查只保留在 `hybrid`／`llm` 分支。**
      - **同步完成**（純計算、毫秒級）：直接落終態，**不經 `PROCESSING`**

    - **`hybrid`（部分打 API）**
      - **所有數字仍由本機決定**：目標比例、`currentValue`、`targetAmount`、`deltaAmount`、`rebalancePlan` 的金額
      - LLM **只**負責把本機算出的結果改寫成 `summary` 與 `riskAssessment` 兩個文字欄位
      - **強制停用 `web_search`**：不加 `ToolUnion.ofWebSearchTool20260209(...)`（既有 `runGeneration` 在 `webSearchOn` 為真時才加，見 `:491-492`）。`references` 固定回空陣列
      - `maxTokens` 須顯著低於既有 `MAX_TOKENS = 16000`（`:78`）——本檔位只產兩段文字。實際值為具名常數
      - **LLM 不得回傳任何數字欄位；若模型回了，一律以本機值覆蓋，不採信**
      - 維持既有非同步形狀（`PROCESSING` → 背景執行緒 → 前端輪詢）
      - **請求／回應契約須自成一套，不得沿用既有 `parseResult`**：既有 `runGeneration` 的解析走 `parseResult(rawText)`（`PortfolioAdviceService.java:498-500`），期待的是**完整** `PortfolioAdviceResult` schema。hybrid 只要兩段文字，故：
        - system prompt 說明「以下數字已由系統算好，請勿更動、勿新增數字，只把它改寫成通順的中文敘述」
        - user prompt 帶入本機算好的 `targetAllocation`／`rebalancePlan`／退休試算摘要
        - 要求模型只回 `{"summary": "...", "riskAssessment": "..."}` 兩個字串欄位
        - 以**專屬的解析路徑**取這兩欄（沿用既有「取首個 `{` 至末個 `}` 再 Jackson 解析」的容錯手法即可），解析失敗時**退回本機模板文字並落成功狀態**，不因文字潤飾失敗而讓整筆建議失敗
        - **LLM 回應解析後不得再跑第二次 `enrich()`**——金額已在本機計算階段由**同一支既有的** `enrich(result, totalAssets)`（`PortfolioAdviceService.java:900-918`）填好（見 338.6／本檔 338.6 對應項），再跑一次會對已正確的值重複覆寫。**這不是「hybrid 不用 enrich」**：三檔位的金額算術一律走那一支，只是 hybrid 在 LLM 潤飾之後不重複呼叫；`references` 直接設空陣列，不走既有 `references` 淨化

    - **`llm`（現行完整版）**
      - 既有 `runGeneration(...)` 路徑，**程式碼一行不改**
      - 含 `ThinkingConfigAdaptive`、`outputConfig.effort`、`web_search`（次數沿用 `resolveWebSearchMaxUses()`）與既有 `references` 淨化

- [x] **339.6 金額算術一律沿用既有的後端決定性回填**

    `targetAmount = 資產總額 × targetPct`、`deltaAmount = targetAmount − currentValue` 這兩條既有算術在**三檔位下走同一段程式碼**，不得為本機檔位另寫一份。

    `currentValue` 一律取自既有 `getCurrentAllocation()`，**不得**由本機引擎重新查 `asset_snapshot`。

- [x] **339.7 `rebalancePlan` 在本機檔位只到「類別」層級**

    `PortfolioAdviceResult.Rebalance` 的欄位為 `(String assetClass, String holding, String action, BigDecimal estimatedAmount, String rationale)`。

    LLM 版的 `holding` 可以是個股代號或基金名稱；`local`／`hybrid` 版**沒有能力**判斷該賣哪一檔，故：
    - `holding` 一律填 `"整體"`
    - `action` 由 `deltaAmount` 正負決定：`> 0` → `"BUY"`、`< 0` → `"SELL"`、`== 0` → `"HOLD"`
    - `estimatedAmount` 為 `|deltaAmount|`

    **不得在本機檔位產生個股層級的買賣建議**——那會是沒有依據的臆測。此限制須在 `warnings` 中明示。

- [x] **339.8 `riskAssessment` 得援引既有退休試算，但不得重算**

    本機版的 `riskAssessment` 可引用 `getProjection()`（既有 `RetirementProjectionService.project(...)`，完全決定性）的結果組出敘述（例如資產耗盡年齡、缺口）。

    **必須呼叫既有方法，不得在本引擎複製一份試算邏輯**——那是 Requirement 32／Task 165 既有的唯一事實來源。

- [x] **339.9 `model` 欄位記錄檔位與版本**

    - `local` → `local-allocation:v1`
    - `hybrid` → `hybrid-allocation:v1+<實際 Claude model id>`（例如 `hybrid-allocation:v1+claude-haiku-4-5`）
    - `llm` → 既有行為（實際 model id）

    欄位長度為 `varchar(64)`，`hybrid` 的組合字串須確認不超長（沿用既有 truncate 慣例）。**配置模板調整時須提升版本號**（同 t336 對 `local-rule-engine:v1` 的理由）。

- [x] **339.10 狀態機三檔共用，不得新增第二套**

    既有 `latest():284-299` 有 `PROCESSING` 卡超過 `PROCESSING_STALE` 即自癒判 `FAILED` 的邏輯。三檔位共用同一組狀態常數（`PortfolioAdvice.STATUS_*`）與同一個 `latest()` 自癒邏輯。`local` 因為同步完成而不經 `PROCESSING`，但**不得**為它新增第二套狀態常數或第二個自癒路徑。

- [x] **339.11 前端：`AssetAllocationAdviceView.vue` 新增引擎下拉**

    檔案：`frontend/src/views/AssetAllocationAdviceView.vue`

    新增「分析引擎」下拉（三檔，選項由後端 `availableEngines` 提供，不在前端寫死）：
    - `local` 時「模型」「思考深度」「搜尋次數」三個既有下拉全部**停用（`disabled`）而非隱藏**
    - `hybrid` 時「模型」「思考深度」可用、「搜尋次數」**停用並顯示為 0**（該檔位強制不搜尋）
    - `llm` 時三者皆可用（既有行為）

    頁面須有**常駐說明**載明背景段的 ⚠ 定性（本機模板為經驗法則、未經回測、非個人化投資建議）。

    切換沿用既有 `PUT /api/bff/portfolio-advice/settings` 與既有權限，**不新增端點**。

    > **前端變更依 CLAUDE.md 規定由固定模型的 subagent 執行**（Claude Code：`sonnet 5` / `high`）。

- [x] **339.12 與 Requirement 79／Task 338 的第八條相容**

    `GET /api/public/portfolio-advice/latest`（Task 338 新增的 9090 第八條路由）只讀最新一筆，**不因引擎檔位而改變行為**；三檔位產出的列都經同一個 `PortfolioAdviceDto` 序列化。本任務**不得**讓該公開端點觸發任何產生動作。

- [x] **339.13 不新增排程**

    本頁維持純手動觸發，不新增 `@Scheduled`。`SchedulePublicBffController.JOBS` 筆數不變，`SchedulePublicBffControllerTest` 的 `hasSize` 斷言不需更動。

- [x] **339.14 測試**

    至少涵蓋：

    - (a) 配置模板為純函式，在固定 `riskTolerance` ＋ `yearsToRetirement` 下產出確定比例——**不啟 Spring context、不連 DB**
    - (b) 三類名稱與 `getCurrentAllocation()` 既有三類**逐字相同**
    - (c) `targetAmount`／`deltaAmount` 由既有後端算術產生，三檔位走同一段程式碼
    - (d) `local` 檔位全程**零 Anthropic client 互動**（以替身斷言），且金鑰為空字串時仍成功
    - (e) `hybrid` 檔位**不掛 `web_search` tool**（斷言送出的 params 不含該 tool）、`references` 為空陣列、且**模型回傳的數字欄位被本機值覆蓋而非採信**
    - (f) `llm` 檔位既有行為不回歸（含 `web_search` 與既有 `references` 淨化）
    - (g) 本機檔位的 `rebalancePlan.holding` 一律為 `"整體"`、無個股層級建議
    - (h) `riskAssessment` 走既有 `getProjection()`，未複製第二份試算
    - (i) `engine` 白名單驗證與「null 不變」語意
    - (j) `local` 同步落終態、不經 `PROCESSING`；`hybrid`／`llm` 維持既有非同步形狀且 `latest()` 自癒邏輯三檔共用

    > **Mockito on Java 21/25 注意**：跑測試用 `-DextraArgLine=-Dnet.bytebuddy.experimental=true`，**不要用 `-DargLine`**（會覆蓋專案時區設定，導致大量測試 error，錯誤訊息偽裝成 byte-buddy 問題）。

---

## 驗證

```bash
# 1. 後端測試
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -f backend/pom.xml test \
  -DextraArgLine=-Dnet.bytebuddy.experimental=true
```

```bash
# 2. 前端 build
cd frontend && /Users/steven/.nvm/versions/node/v22.21.0/bin/node \
  /Users/steven/.nvm/versions/node/v22.21.0/bin/npm run build
```

```bash
# 3. 重建 + recreate（JVM service 一律 --no-cache；business 換 IP 後 BFF 需 restart）
cp /Users/steven/Project/asset-management/.env . 2>/dev/null; \
docker compose -p asset-management build --no-cache business-services && \
docker compose -p asset-management up -d --no-deps --force-recreate business-services && \
sleep 20 && docker compose -p asset-management restart bff
```

```bash
# 4. 確認 changeset 已套用、欄位存在
docker exec asset-postgres psql -U assets -d assets \
  -c "SELECT id FROM databasechangelog ORDER BY orderexecuted DESC LIMIT 2" \
  -c "\d portfolio_advice_setting"
```

```bash
# 5. local 檔位：切換設定並觸發，應立即回終態、model 為 local-allocation:v1
docker exec asset-business-services curl -s -X PUT \
  -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" \
  -H "Content-Type: application/json" -d '{"engine":"local"}' \
  http://localhost:8080/api/portfolio-advice/settings
docker exec asset-business-services curl -s -X POST \
  -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" \
  -H "Content-Type: application/json" -d '{}' \
  http://localhost:8080/api/portfolio-advice/generate
```

```bash
# 6. 斷言 local 檔位沒有任何 Anthropic 呼叫
docker logs --tail 200 asset-business-services 2>&1 | grep -i "anthropic\|web_search" || echo "PASS: 無 Anthropic 呼叫"
```

驗收斷言：`local` 立即回終態且 `model` 為 `local-allocation:v1`、日誌零 Anthropic 呼叫；`hybrid` 落 `PROCESSING` 後完成、`model` 前綴為 `hybrid-allocation:v1+`、`references` 為空陣列；`llm` 行為與改動前一致。

---

## 完成報告

### 後端（339.1–339.10、339.12–339.14）與前端（339.11）皆已完成

實作由兩支 subagent 分別執行（後端一支、前端一支依 CLAUDE.md 前端例外用固定模型），完成報告由主 agent 統一回填——刻意不讓實作 subagent 寫 `spec/`，避免並行 agent 互相讓 SDD 內容雜湊失效（Task 337 實作期間已發生過一次，導致該 agent 被迫繞過閘門）。

**新增檔案**
- `backend/src/main/resources/db/changelog/changes/v1.106.0-portfolio-advice-engine.sql`（冪等，註冊於 master 尾端、v1.105.0 之後）
- `backend/src/main/java/com/steven/assets/service/LocalPortfolioAllocationEngine.java`（純函式配置模板）
- `backend/src/test/java/com/steven/assets/service/LocalPortfolioAllocationEngineTest.java`（16 tests）
- `backend/src/test/java/com/steven/assets/service/PortfolioAdviceServiceEngineTest.java`（19 tests）

**修改檔案**
- `db.changelog-master.yaml`、`PortfolioAdviceSetting.java`、`PortfolioAdviceSettingsDto.java`、`PortfolioAdviceController.java`（`updateSettings` 補第四個引數 `str(body, "engine")`）、`PortfolioAdviceService.java`（三檔分岔 ＋ `resolveEngine()` ＋ `buildLocalResult`／`completeLocal`／`startHybrid`／`runHybridGeneration`／`buildHybridParams`／`mergeRefinement`）
- `frontend/src/views/AssetAllocationAdviceView.vue`（三態下拉、`local` 停用三個既有下拉、`hybrid` 停用搜尋次數並顯示 0、常駐免責說明）

**驗證輸出（主 agent 獨立複跑，非採信 subagent 回報）**
- `mvn -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true` → exit 0；surefire 報告統計 **Tests=1084 Failures=0 Errors=0 Skipped=0**（動工前 baseline 1049，本任務 +35）
- `LocalPortfolioAllocationEngineTest` 16／`PortfolioAdviceServiceEngineTest` 19，皆 0 failures
- 分岔點查證：`resolveEngine()` 於 `PortfolioAdviceService:500`、`ENGINE_LOCAL` 判斷於 `:530`、既有金鑰檢查於 `:535` —— 分岔確實在金鑰檢查**之前**（339.5 硬約束）
- `buildHybridParams` 內零 `WebSearchTool`／`addTool`；`HYBRID_MAX_TOKENS = 4000`（既有 `MAX_TOKENS = 16000` 的 1/4）
- 三類名稱查證：引擎內僅出現 `"存款（現金）"`／`"信託基金"`／`"股票"` 三個字串，與 `getCurrentAllocation()` 逐字一致
- `npm run build` exit 0

**與原計畫的偏差（4 項）**

1. **hybrid 無金鑰 → `NOT_CONFIGURED`，不退回本機文字。** 依 339.5「檢查只保留在 hybrid／llm 分支」的字面語意；spec 所述「退回本機模板」只針對*解析失敗*。錯誤訊息提示改用「完全本機」檔位。
2. **hybrid 保留 `ThinkingConfigAdaptive` ＋ `outputConfig.effort`**，只拿掉 `web_search` 並把 maxTokens 降至 4000。理由：339.11 要求 hybrid 的「思考深度」下拉可用，effort 必須真的生效。
3. **hybrid 的 LLM 呼叫失敗（不只解析失敗）亦退回本機文字並落 `OK`**，並於 `warnings` 追加一條退化說明（⚠ 首條聲明維持在最前）。理由同 spec「不因文字潤飾失敗而讓整筆建議失敗」，且不靜默。
4. `buildHybridParams` 與 `mergeRefinement` 設為 package-private 供同套件測試直接斷言，未對外曝露。

**mergeRefinement 比規格更嚴**：規格要求「模型若回數字欄位一律以本機值覆蓋」，實作上這些欄位**根本不讀**，測試以含 `targetPct:99`／`estimatedAmount`／`references` 的假回覆驗證 `targetAllocation`／`rebalancePlan` 逐項等於本機值、`references` 為空。

