# [t358] 今日股市分析頁面改為分類分點呈現（技術面／量能面／美股連動／籌碼面）

**對應 Requirements:** Requirement 95（今日股市分析頁面改為分類分點呈現——技術面／量能面／美股連動／籌碼面各自獨立、台股與美股分開區塊）
**前置任務:** t337（今日股市分析本機規則引擎，已完成——本任務直接改動其產出的 fragment list）
**Liquibase changeset:** `v1.108.0-market-analysis-factor-groups.sql`（建檔前須先查運行中 `databasechangelog`：`SELECT id FROM databasechangelog ORDER BY orderexecuted DESC LIMIT 5`，若 `v1.108.0` 已被其他 worktree 佔用，須連同本檔檔名、changeset id、`spec/requirements.md` Requirement 95、`spec/design.md` 對應段落一次調整版號）

## 背景

使用者反應「今日股市分析」頁面（`/market-analysis`，元件 `frontend/src/views/TodayMarketAnalysisView.vue`）目前把整段研判文字塞進一個 `summary` 大坨字串一次顯示，難以閱讀，要求改成「分點條列，且台股與美股要分開在不同的點」。

**現況資料流：**

- `backend/src/main/java/com/steven/assets/service/LocalMarketAnalysisEngine.java` 的 `evaluate()`（`:218-236`）計分過程中，把每個訊號各自的中文敘述句 `add` 進三個區域變數：`twFragments`（`:219`，目前混雜 MA／KD／MACD／RSI**與量能**共五項）、`usFragments`（`:220`，SOX／IXIC／SPX 三項）、`chipFragments`（`:221`，外資最新／外資近三日／投信／自營四項）。
- `volumeSignal(List<double[]> closes, List<double[]> volumes, List<String> twFragments)`（`:444-488`）的第三個參數名為 `twFragments`，方法內 `:486` 呼叫 `twFragments.add(desc)`——量能面的敘述句因此被錯誤地併入技術面 fragment，兩者在既有欄位裡無法區分。
- `evaluate()` 尾端（`:261-264`）：`twContext = String.join("；", twFragments) + "。"`（技術面＋量能面混合）、`usContext = String.join("；", usFragments) + "。"`。**籌碼面沒有對應的獨立 context 欄位**，`chipFragments` 只在 `buildSummary()`（`:624-648`）內被拼進 `summary` 字串的「籌碼面：」段落，`MarketAnalysisResult`／`MarketAnalysisDto` 完全沒有欄位可單獨取用。
- `MarketAnalysisResult`（`backend/src/main/java/com/steven/assets/dto/MarketAnalysisResult.java`，12-20 行）欄位：`bias`、`confidence`、`summary`（大坨字串）、`keyFactors`（`List<String>`，已是陣列但技術／量能／美股／籌碼混在同一 flat list，來自每個 `Signal.factor()`，與本任務要處理的 fragment 分類是不同的東西——`keyFactors` 只收「非零方向分」的訊號、`factorGroups` 收「所有已算出文字」的訊號，兩者不得混用同一份 list）、`newsHighlights`、`twContext`、`usContext`。
- `MarketAnalysisDto`（`backend/src/main/java/com/steven/assets/dto/MarketAnalysisDto.java`）對應到前端 API 回傳，欄位大致相同，多 `analysisDate`／`model`／`status`／`errorMessage`／`generatedAt`。
- 資料流：`LocalMarketAnalysisEngine.evaluate()` → `MarketAnalysisResult` → `MarketAnalysisService.applyLocalResult(row, result)`（本機路徑）或 `applyResult(row, r)`（LLM 路徑）存 DB → `MarketAnalysisController.today()`（`GET /api/market-analysis/today`，回 `MarketAnalysisDto`）→ `TodayMarketAnalysisBffController`（`GET /api/bff/today-market-analysis`，純轉發 `Mono.zip` 出的 `Map<String,Object>`，未做欄位轉換）→ 前端 `frontend/src/api/index.js` 的 `bffApi.todayMarketAnalysis.get()` → `TodayMarketAnalysisView.vue` 顯示。
- 前端顯示位置：`TodayMarketAnalysisView.vue:123` `<div class="summary" v-if="today.summary">{{ today.summary }}</div>`（整段文字直接塞進去）；`:126-132` `today.keyFactors` 已經是 `<ul><li>` 條列（但無分類）；`:146-155` `today.twContext`／`today.usContext` 已經分成兩個 `<el-col>` 區塊（「台股近期走勢」「美股近期走勢」），但各自仍是整段文字未再拆點；`:176-178` 歷史表格中 `summary` 也整段顯示。
- `MarketAnalysisEmailDispatcher.buildHtml()`（`:107-110`，由 `dispatchDaily()`〔`:45-75`，於 `:61` 呼叫 `buildHtml(dto)`〕組出 HTML 信件內文）直接使用 `dto.summary()`。本任務**不改動** email 版式——本 Requirement 刻意不擴充 email 版式，非因格式限制（`buildHtml()` 本就是 HTML 信件、且已用 `<ul><li>` 呈現 `keyFactors`），`summary` 欄位維持現行格式作後備。

**現在的錯誤行為：** 使用者在頁面上看到一整段沒有分行、沒有項目符號、動輒二三百字的連續中文長句，必須自己斷句才能找出某一類重點（例如「KD 是否死叉」「SOX 昨夜漲跌」），且台股與美股的敘述交織在同一段落裡（`summary`）或至少沒有進一步拆點（`twContext`／`usContext`）。

**正確行為：** 引擎已經算出的每一條訊號敘述句，改以四個分類（台股技術面／台股量能面／美股連動／籌碼面）各自條列呈現，台股與美股視覺上分屬不同區塊，不必逐段文字閱讀即可掃過重點。

**這是責任結構性拆解，不是重新分析。** 本任務**不改動任何訊號的計分邏輯、權重、門檻**，只把既有已經分類完成（但被黏合成文字）的 fragment list 重新暴露成結構化欄位。

## 要做什麼

### 358.1 後端：拆分 `volumeSignal()` 使用獨立的 `volumeFragments`

- [ ] **358.1a** `LocalMarketAnalysisEngine.volumeSignal(List<double[]> closes, List<double[]> volumes, List<String> twFragments)`（`:444`）第三個參數改名為 `List<String> volumeFragments`，方法內 `:486` `twFragments.add(desc)` 改為 `volumeFragments.add(desc)`。方法簽名與內部計算邏輯（`VOLUME_MA_DAYS`、`VOLUME_SURGE_RATIO`、`VOLUME_SHRINK_RATIO`、`W_TW_VOLUME`、`SIG_TW_VOLUME`）**一個字不改**。
- [ ] **358.1b** `evaluate()`（`:218-236`）新增區域變數 `List<String> volumeFragments = new ArrayList<>();`，`:227` 呼叫 `volumeSignal(closes, volumes, volumeFragments)` 改傳入這個新 list（不再傳 `twFragments`）。
- [ ] **358.1c** `twContext` 的組成（`:261-262`）改為合併 `twFragments` 與 `volumeFragments` 兩者後再 join——例如新增 helper `private static List<String> concat(List<String> a, List<String> b)` 回傳 `a` 接 `b` 的新 `ArrayList`，`twContext = twAndVolume.isEmpty() ? "（台股日線資料不足，本次未產生技術面敘述）" : String.join("；", twAndVolume) + "。"`（`twAndVolume = concat(twFragments, volumeFragments)`）。**驗收點**：拆分前後 `twContext` 的最終字串內容必須逐字相同（量能面那句仍出現在同一位置）。
- [ ] **358.1d** `buildSummary(...)`（`:624-648`）的呼叫處（`:265-266`）與方法簽名同步：「技術面：」段落須合併 `twFragments` 與 `volumeFragments`。**驗收點**：拆分前後 `summary` 的最終字串內容必須逐字相同。

### 358.2 後端：新增 `MarketAnalysisResult.FactorGroups` 與 `factorGroups` 欄位

- [ ] **358.2a** 在 `backend/src/main/java/com/steven/assets/dto/MarketAnalysisResult.java` 新增巢狀 record：
  ```java
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record FactorGroups(
          List<String> twTechnical,
          List<String> twVolume,
          List<String> us,
          List<String> chip
  ) {}
  ```
  並在 `MarketAnalysisResult` 的欄位列表新增第 8 個欄位 `FactorGroups factorGroups`（緊接在既有 `usContext` 之後）。既有 7 個欄位（`bias`／`confidence`／`summary`／`keyFactors`／`newsHighlights`／`twContext`／`usContext`）**順序與型別不變**，`factorGroups` 純追加，不插隊。
- [ ] **358.2b** `LocalMarketAnalysisEngine.evaluate()` 回傳前（`:268-269` 附近）新增：
  ```java
  MarketAnalysisResult.FactorGroups factorGroups = new MarketAnalysisResult.FactorGroups(
          List.copyOf(twFragments), List.copyOf(volumeFragments),
          List.copyOf(usFragments), List.copyOf(chipFragments));
  ```
  並把 `factorGroups` 傳入 `MarketAnalysisResult` 建構式的第 8 個位置。四個清單**直接**是既有的 fragment list（`List.copyOf` 只為避免外洩可變參照，不重新計算任何內容）。
- [ ] **358.2c** LLM 路徑不動：`MarketAnalysisResult` 由 Jackson 從 Claude 回傳的 JSON 反序列化時，既有 system prompt schema（`bias/confidence/summary/keyFactors[]/newsHighlights[]/twContext/usContext`）**不新增** `factorGroups` key，靠既有 `@JsonIgnoreProperties(ignoreUnknown = true)` 與 record canonical constructor 對缺 key 欄位自動填 `null` 的既有語意，使 `factorGroups` 對 LLM 結果恆為 `null`。**不得**修改 `MarketAnalysisService.buildSystemPrompt()`／`buildUserPrompt()` 的既有 schema 說明字串去要求模型輸出這個欄位。

### 358.3 後端：DB 新增 `factor_groups` 欄位

- [ ] **358.3a** 新增檔案 `backend/src/main/resources/db/changelog/changes/v1.108.0-market-analysis-factor-groups.sql`：
  ```sql
  ALTER TABLE daily_market_analysis ADD COLUMN IF NOT EXISTS factor_groups TEXT;
  ```
  （冪等；不加 `NOT NULL`、不加 `DEFAULT`——本欄位對既有列與 LLM 路徑一律維持 `NULL`。）
- [ ] **358.3b** 在 `backend/src/main/resources/db/changelog/db.changelog-master.yaml` 尾端新增對應 `include`：
  ```yaml
    - include:
        file: db/changelog/changes/v1.108.0-market-analysis-factor-groups.sql
        relativeToChangelogFile: false
  ```
- [ ] **358.3c** `backend/src/main/java/com/steven/assets/model/DailyMarketAnalysis.java` 新增欄位（緊接既有 `usContext` 之後）：
  ```java
  @Column(name = "factor_groups", columnDefinition = "TEXT")
  private String factorGroups;
  ```
  （含對應 getter／setter；本專案 entity 慣例用 Lombok `@Getter`／`@Setter` 或手寫，沿用該檔既有其他欄位的寫法。）

### 358.4 後端：`MarketAnalysisService` 寫入 `factorGroups`

- [ ] **358.4a** 本機路徑 `applyLocalResult(row, result)`（Requirement 78／Task 337 新增）在既有 `row.setKeyFactors(objectMapper.writeValueAsString(result.keyFactors()))` 之後，新增：
  ```java
  row.setFactorGroups(result.factorGroups() == null
          ? null
          : objectMapper.writeValueAsString(result.factorGroups()));
  ```
- [ ] **358.4b** LLM 路徑 `applyResult(row, r)` **不修改**——`r.factorGroups()` 對 LLM 結果恆為 `null`，`row.factorGroups` 因該欄位未被此路徑設值而維持 entity 預設（`null`）。**不得**為了「填滿欄位」在 `applyResult` 里臨時拼一份假的分類。

### 358.5 後端：`MarketAnalysisDto` 新增欄位與解析

- [ ] **358.5a** `MarketAnalysisDto`（`backend/src/main/java/com/steven/assets/dto/MarketAnalysisDto.java`）record 欄位列表在 `usContext` 之後新增 `MarketAnalysisResult.FactorGroups factorGroups`。
- [ ] **358.5b** 新增私有 static 方法：
  ```java
  private static MarketAnalysisResult.FactorGroups parseFactorGroups(String json, ObjectMapper mapper) {
      if (json == null || json.isBlank()) return null;
      try {
          return mapper.readValue(json, MarketAnalysisResult.FactorGroups.class);
      } catch (Exception e) {
          return null;
      }
  }
  ```
  與既有 `parse(String, TypeReference<List<T>>, ObjectMapper)`（`:57-64`，失敗回 `List.of()`）**刻意不同**：本方法失敗或空白時回 `null`，不是空物件。理由：`null` 代表「本次分析沒有分類資料，前端走既有整段呈現」；一個所有欄位皆空陣列的 `FactorGroups` 代表「本次有算過分類、只是剛好零命中」，兩者語意不同，混淆會讓前端「無資料」與「該分類零命中」顯示成同一種空白。
- [ ] **358.5c** `from(e, mapper)`（`:34-49`）新增一行 `parseFactorGroups(e.getFactorGroups(), mapper)`，放進建構式呼叫的對應位置（`usContext` 之後）。
- [ ] **358.5d** `none()`（`:52-55`）新增的 `factorGroups` 位置固定填 `null`。

### 358.6 前端：分類分點呈現

- [ ] **358.6a** `frontend/src/views/TodayMarketAnalysisView.vue` 既有 `<div class="summary" v-if="today.summary">{{ today.summary }}</div>`（`:123`）、`today.keyFactors` 條列（`:126-132`）、`today.twContext`／`today.usContext` 兩個區塊（`:146-155`）**全部保留、不刪除**。
- [ ] **358.6b** 在既有卡片內新增一個 `v-if="today.factorGroups"` 的區塊，內含四個並列的分類清單（沿用既有「關鍵因素」的 `<div class="block-title">`＋`<ul class="factor-list"><li v-for="(f,i) in list" :key="i">{{ f }}</li></ul>`＋`<div v-else class="muted">—</div>` 樣式，不新增 CSS class）：
  - 「台股技術面」← `today.factorGroups.twTechnical`
  - 「台股量能面」← `today.factorGroups.twVolume`
  - 「美股連動」← `today.factorGroups.us`
  - 「籌碼面」← `today.factorGroups.chip`
  版面用 `<el-row><el-col :xs="24" :md="12">` 兩欄式排版，**台股技術面／台股量能面放同一列或同一群組，美股連動與籌碼面各自獨立、且與台股兩個分類視覺上不同區塊**（不得四者混排在同一個 `<ul>` 或同一欄）。任一分類陣列為空（`[]`）時該區塊仍顯示標題＋`<div class="muted">—</div>`，**不得整塊隱藏**。
- [ ] **358.6c** `today.factorGroups` 為 `falsy`（`null`／`undefined`）時，**不渲染**上述四個區塊，且不得對 `today.factorGroups.twTechnical` 等巢狀屬性解參照（用 `v-if="today.factorGroups"` 包住整塊，不要在內層才做 null 檢查）。
- [ ] **358.6d** 歷史表格（`:176-178`）**不修改**，維持 `row.summary` 整段顯示。

### 358.7 明確排除範圍

- [ ] **358.7** 本任務**不得**：變更任何訊號的權重、門檻、方向分計算或新增／移除訊號；修改 `RULE_VERSION`（維持 `local-rule-engine:v1`——本任務不改變任何計算結果，只改變呈現分類）；修改 `MarketAnalysisEmailDispatcher` 的 email 版式；修改 `/api/market-analysis/today`、`/api/bff/today-market-analysis`、`/api/public/market-analysis/today`（Requirement 79 第七條）的路徑或既有必填欄位；修改 `docs/openapi/docker-external-api.yaml`（新欄位屬既有回應 schema 的自然擴充，非契約邊界變更）；新增 `@Scheduled`；修改歷史表格呈現方式。

## 驗證

```bash
# 後端單元測試（含 LocalMarketAnalysisEngineTest／MarketAnalysisServiceLocalEngineTest 新增斷言）
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test

# 前端 production build（本任務不新增前端自動化測試，見下方理由；但仍須確認建置不壞）
cd frontend && /Users/steven/.nvm/versions/node/v22.21.0/bin/node ./node_modules/.bin/vite build

# 建置 image 前先確認 v1.108.0 未被其他 worktree 佔用
docker exec asset-postgres psql -U assets -d assets -c \
  "SELECT id FROM databasechangelog ORDER BY orderexecuted DESC LIMIT 5;"

# --no-cache 重建並 recreate（JVM 服務改動 + 前端改動，四支皆需重建）
docker compose -p asset-management build --no-cache business-services bff frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services bff frontend
curl -s http://localhost:8080/actuator/health

# 免 OAuth 端到端：容器內觸發一次本機引擎分析，斷言 factorGroups 四個分類非 null
docker exec asset-business curl -s -X POST http://localhost:8080/api/market-analysis/generate \
  -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" | \
  python3 -c "import json,sys; d=json.load(sys.stdin); assert d.get('factorGroups') is not None; print('OK', d['factorGroups'])"
```

**前端無既有自動化測試框架前例可援引**：本專案前端目前僅有 `frontend/src/utils/*.test.js` 這類工具函式層級 contract test（例如 `stockAnalysisDialog.contract.test.js`），沒有任何 view 元件層級的既有測試可援引，`TodayMarketAnalysisView.vue` 本身亦無既有測試檔。故本任務改以 `/run-stack` 實機瀏覽器驗證：登入後於「今日股市分析」頁，`engine=local` 觸發一次分析，確認卡片同時顯示既有 `summary`／`keyFactors`／`twContext`／`usContext` 與新增的四個分類區塊，且台股（技術面＋量能面）與美股連動視覺上分屬不同區塊；切換檢視 `engine=llm` 或既有舊資料歷史（`factorGroups` 為 `null`）時，畫面不顯示四個分類區塊、不報錯；歷史列表 `summary` 欄維持既有整段文字顯示。

## 完成報告

**Liquibase changeset 版號：** `v1.108.0`（與預估一致；建檔前已用
`ls backend/src/main/resources/db/changelog/changes/ | sort -V | tail -8` 與運行中
`databasechangelog`（`docker exec asset-postgres psql ...`）核實，當時最大已用版號為
`v1.107.0-deposit-type-withdrawal-order`，`v1.108.0` 未被佔用，無需調整版號或連動修改
`spec/requirements.md`／`spec/design.md`。

**實際改動檔案：**

- `backend/src/main/java/com/steven/assets/service/LocalMarketAnalysisEngine.java`——
  358.1a～358.1d：`volumeSignal()` 第三參數改名 `volumeFragments`；`evaluate()` 新增獨立
  `volumeFragments` list；新增 `concat()` helper；`twContext`／`buildSummary()` 的「技術面：」
  段落改用 `concat(twFragments, volumeFragments)`；`evaluate()` 回傳前組出並傳入
  `MarketAnalysisResult.FactorGroups`。
- `backend/src/main/java/com/steven/assets/dto/MarketAnalysisResult.java`——新增巢狀 record
  `FactorGroups(twTechnical, twVolume, us, chip)`，`MarketAnalysisResult` 新增第 8 個欄位
  `factorGroups`（緊接 `usContext` 之後，前 7 欄順序型別不變）。
- `backend/src/main/resources/db/changelog/changes/v1.108.0-market-analysis-factor-groups.sql`
  （新檔）——`ALTER TABLE daily_market_analysis ADD COLUMN IF NOT EXISTS factor_groups TEXT;`，冪等。
- `backend/src/main/resources/db/changelog/db.changelog-master.yaml`——尾端新增對應 `include`。
- `backend/src/main/java/com/steven/assets/model/DailyMarketAnalysis.java`——新增
  `factor_groups`（`TEXT`）欄位，沿用既有 Lombok `@Data` 慣例（自動 getter/setter）。
- `backend/src/main/java/com/steven/assets/service/MarketAnalysisService.java`——
  `applyLocalResult()` 新增 `row.setFactorGroups(...)`（null 或序列化 JSON）；`applyResult()`（LLM
  路徑）維持不動；另在既有 `clearContent()`（重跑既有 row 時清空內容欄位，與 `twContext`／
  `usContext` 同一批）新增 `row.setFactorGroups(null)`——這是計畫外的必要延伸：若不清空，
  重跑切到 LLM 路徑或訊號全缺時會殘留上一次本機路徑的舊 `factorGroups`，與當次
  `twContext`／`summary` 不一致。判斷屬於「同一份既有清空邏輯的自然延伸」，非額外重構，
  故未回頭修改 spec 文字（沒有新增業務規則，只是修正一個原本會產生資料不一致的疏漏）。
- `backend/src/main/java/com/steven/assets/dto/MarketAnalysisDto.java`——新增
  `factorGroups` 欄位（`usContext` 之後）與 `parseFactorGroups()`（null/空白/解析失敗一律回
  `null`，與既有 `parse()` 回 `List.of()` 刻意不同）；`from()`／`none()` 同步更新。
- `frontend/src/views/TodayMarketAnalysisView.vue`——既有 `summary`／`keyFactors`／
  `twContext`／`usContext` 呈現不動；新增 `v-if="today.factorGroups"` 包住的兩列
  `<el-row>`（第一列：台股技術面／台股量能面；第二列：美股連動／籌碼面），沿用既有
  `.block-title`／`.factor-list`／`.muted` 樣式，未新增 CSS class。
- 測試：
  - `backend/src/test/java/com/steven/assets/service/LocalMarketAnalysisEngineTest.java`——
    擴充 `taiwanTechnicalSignals_produceNumericKeyFactorsFromTheSameSeries`（斷言
    `factorGroups` 四分類逐條與 `keyFactors`／`twContext` 一致、量能敘述不再混入
    `twTechnical`、`twContext` 逐字等於 `twTechnical`＋`twVolume` 串接）；擴充
    `volumeSignal_isAbsentWhenWindowIsTooShort`（斷言 `twVolume` 為空陣列）；新增
    `factorGroups_usAndChipMatchFragmentsUsedForUsContextAndChipKeyFactors`。
  - `backend/src/test/java/com/steven/assets/service/MarketAnalysisServiceLocalEngineTest.java`——
    3 處 `new MarketAnalysisResult(...)` mock stub 補上第 8 個參數（`null`）；新增
    `localEngine_persistsFactorGroupsAsRoundTrippableJson`（斷言落庫 JSON round-trip）。
  - `backend/src/test/java/com/steven/assets/dto/MarketAnalysisDtoTest.java`（新檔）——涵蓋
    `factor_groups` 為 `NULL`／空白／損毀 JSON 三種情形回傳 `null`、有效 JSON round-trip、
    `none()` 恆 `null`，以及 Jackson 對缺 `factorGroups` key 的 LLM JSON 反序列化為 `null`
    （驗證 358.2c）。

**驗證輸出：**

- 後端全量測試：`mvn -DextraArgLine=-Dnet.bytebuddy.experimental=true test` → 全綠，
  `Tests run: 1351, Failures: 0, Errors: 0, Skipped: 0`。
- 前端 production build：`vite build` → `exit 0`，`✓ built in 4.47s`
  （worktree 缺 `node_modules`，先 `npm install` 後才能跑；與程式碼變更無關）。

**與原計畫的偏差：**

1. `clearContent()` 新增 `row.setFactorGroups(null)`——任務檔未列出，但屬同一批既有
   內容清空欄位（`bias`／`summary`／`keyFactors`／`twContext`／`usContext`）的自然延伸，
   不清空會在重跑情境產生資料不一致，判斷在 358.4 範圍內、非額外重構。
2. `docs/openapi/docker-external-api.yaml`、`MarketAnalysisEmailDispatcher`、
   `RULE_VERSION`、任何訊號權重／門檻、`/api/market-analysis/today` 等既有必填欄位、
   BFF／`/api/public/market-analysis/today` 路徑均**未變動**，符合 358.7 排除範圍。
3. 未執行 `/run-stack` 實機瀏覽器驗證（docker image rebuild + container recreate +
   瀏覽器操作）——受限於本次任務時間與工具邊界，僅完成後端全量測試與前端 production
   build 的靜態驗證；`/run-stack` 留待收尾階段的 subagent 執行時一併驗證。
