# [t344] 資產配置建議的標的層級再平衡——本機檔位把加減碼金額落到具體銀行／基金／個股

**對應 Requirements:** Requirement 84（「再平衡操作明細」不再只給「股票 整體 減碼 5,739,699 元」，而是把各子類別的差額按使用者**現有持有標的**的現值比重攤到個別銀行／基金／個股；存款的減碼改依提領優先序逐筆抽取以避開定存解約損失。僅 `local`／`hybrid` 檔位，`llm` 一行不改）
**前置任務:** t339（三態引擎 local/hybrid/llm）、t341（子類別細分，本任務直接消費它產出的 `subAllocations`）
**Liquibase changeset:** `v1.107.0-deposit-type-withdrawal-order.sql`

## 背景

「資產配置建議」頁的「再平衡操作明細」目前只有三筆類別層級動作：「存款（現金）整體 減碼 2,338,033 元」「信託基金 整體 增碼 8,077,733 元」「股票 整體 減碼 5,739,699 元」。使用者（本專案唯一使用者，已退休或臨退休、資產約 2,034 萬）的原話回饋：

> 「應該要有明確標的，例如 0050 投入 50 萬 … 定存 300 萬…」

他拿這份清單是要去下單的，只給類別總額等於還要他自己再算一次。

### 本任務推翻了什麼

Requirement 80 的 AC 明文寫著：

> 「`rebalancePlan` 在本機檔位只到「類別」層級，不得偽裝成個股操作……本機版**沒有能力**判斷該賣哪一檔，故 `holding` 一律填「整體」……**不得**在本機檔位產生個股層級的買賣建議——那會是沒有依據的臆測。」

Requirement 82 亦重申「`rebalancePlan` 不擴充到子類別層級」。程式碼中對應的是 `LocalPortfolioAllocationEngine` 類別 javadoc 的「已知退化」段落（約 `:38-41`）與常數 `NO_HOLDING_LEVEL_WARNING`（約 `:86-88`，本任務會把它**改名為 `HOLDING_LEVEL_SCOPE_WARNING`**，見 344.21）。

**推翻的理由：當初那條限制把兩件本質不同的事混為一談。**

| | 需要什麼 | 本機規則有沒有依據 | 本任務 |
|---|---|---|---|
| 指名該買**你沒持有的新標的** | 選股、估值、時機判斷 | 沒有 → 硬給是臆測 | **維持禁止** |
| 把已定好的金額按**你既有部位的現值比重**攤下去 | 分母（你的持股）＋分子（上層算好的差額） | 純算術，零市場觀點 | **本次解禁** |

**⚠ 但「按比重攤」不是完全中性的，必須對使用者明示、不得美化。** 等比例減碼預設「同一子類別內每一檔一樣好、應該等比例減持」，它**不考慮**個別標的的套牢／獲利狀態、交易成本與最低手續費、稅務、基本面差異。此預設須逐字寫進 `warnings`（見 344.14），不得只留在 spec 裡。

### 存款是唯一的例外，理由是「賣出成本不對稱」而非投資判斷

定期存款中途解約按實際存期折算利息，是可量化的真實損失；活期存款沒有這個問題。若對存款也套等比例，會建議把三筆 1.715%、合計 7,998,458 元的定存同時各解約約 **770,295／738,826／679,572** 元（合計約 219 萬）。係數為 `T/V = 2,338,033 / 8,544,212 = 0.273639`。

故存款的**減碼**改採「依提領優先序逐筆抽取、抽滿一筆才動下一筆」。實測同一份資料只會動到**一筆**定存（富邦部分解約 1,988,010 元），另兩筆定存與兩筆美元定存完全不動。**存款的增碼不套用此規則**——waterfall 的正當性來自「解約有損失」，那是賣出才有的問題。

### 使用者已拍板的三項（不要重新提案別的方向）

1. **存款減碼＝活存優先的 waterfall**（非等比例）。
2. **接受同一桶底下同時出現賣出與買進**（賣 0050 約 237 萬與買 00919 約 6.8 萬並列）——因為頁面上方的子類別目標表本來就寫「收益型增碼 363,839」。
3. **最小操作金額門檻 = 10,000 元**，低於此值的合併成一列。

## 要做什麼

### DB：提領優先序入庫（禁止 Enum 寫死）

- [x] 344.1 新增 Liquibase changeset `backend/src/main/resources/db/changelog/changes/v1.107.0-deposit-type-withdrawal-order.sql`，並在 `backend/src/main/resources/db/changelog/db.changelog-master.yaml` **最尾端**以 `- include: {file: db/changelog/changes/v1.107.0-deposit-type-withdrawal-order.sql, relativeToChangelogFile: false}` 註冊（比照既有 `v1.106.0-portfolio-advice-engine` 的寫法）。內容須冪等：
  ```sql
  ALTER TABLE deposit_type ADD COLUMN IF NOT EXISTS withdrawal_order INTEGER NOT NULL DEFAULT 50;
  ```
  版號已於建檔前重查運行中 `databasechangelog`（最新為 `v1.106.0-portfolio-advice-engine`），且全機 worktree 皆無人佔用 `v1.107`。**若實作當下重查發現已被佔用，往上取下一個未使用版號並同步改本檔。**
- [x] 344.2 `DepositTypeEntity`（`backend/src/main/java/com/steven/assets/model/DepositTypeEntity.java`，現有欄位為 `id`／`code`／`displayName`／`sortOrder`／`active`）新增：
  ```java
  /** 提領優先序：越小越優先被提領（資產配置建議的存款減碼 waterfall 用）。 */
  @Column(name = "withdrawal_order", nullable = false)
  @Builder.Default
  private Integer withdrawalOrder = 50;
  ```
- [x] 344.3 提領優先序的預設值，**必須拆成兩件互不相干的事寫**——`DataInitializer.seedDepositTypes()`（`:184-205`）既有的迴圈是 `if (depositTypeRepo.findByCode(s.code()).isEmpty()) { save(...) }`，**只 INSERT、從不 UPDATE**；照「加進 seed 常數」的寫法做，五個既有類型的 `withdrawal_order` **一個都不會被回填**，全部停在 changeset 的 DEFAULT 50：
  - **(a) 產品 seed（供全新 DB 建立時用）**：`DepositTypeSeed` record 增加 `withdrawalOrder` 欄位，六個既有 seed 項目各填——`活存`=10、`美元活存`=20、`定存`=90、`美元定存`=91、`證券戶`=50、`信用卡待付款`=50。
  - **(b) 既有資料回填（供已部署的 DB 用）**：寫進 344.1 的 changeset，以一次性 `UPDATE` 處理，並在 SQL 註解註明「這是既有資料回填、不是產品 seed」：
    ```sql
    UPDATE deposit_type SET withdrawal_order = 10 WHERE code = '活存'         AND withdrawal_order = 50;
    UPDATE deposit_type SET withdrawal_order = 20 WHERE code = '美元活存'     AND withdrawal_order = 50;
    UPDATE deposit_type SET withdrawal_order = 30 WHERE code = '優利活存 1.5%' AND withdrawal_order = 50;
    UPDATE deposit_type SET withdrawal_order = 90 WHERE code = '定存'         AND withdrawal_order = 50;
    UPDATE deposit_type SET withdrawal_order = 91 WHERE code = '美元定存'     AND withdrawal_order = 50;
    ```
    `AND withdrawal_order = 50` 使本 changeset **可重複執行**（正常單次執行時所有列皆為 `DEFAULT 50`，重跑時只跳過已被改成非 50 的列）。**不要把它宣稱成「保護使用者已調整的值」**——使用者若剛好把某類型調成 50，重跑仍會被覆蓋；真正的保護是「這支 changeset 只跑一次」。
  - **⚠ `優利活存 1.5%` 只能出現在 (b)，絕對不能加進 (a) 的產品 seed。** 實查運行中 DB，`deposit_type` 有 7 列，`id=7 | 優利活存 1.5% | 台幣優利活存` **不在 `DataInitializer` 的 seed 名單裡，是使用者自己新增的私有分類**（名稱還內含 `1.5%` 這種會過期的利率字面值）。把它寫進產品 seed 等於在任何乾淨 DB 上憑空建出一個別人的私有分類。
  對照運行中 DB 的實際資料（`SELECT id,code,display_name,sort_order,active FROM deposit_type ORDER BY sort_order`）：
  ```
  1|活存|台幣活存|1|t          2|定存|台幣定存|2|t
  7|優利活存 1.5%|台幣優利活存|3|t   3|美元活存|美元活存|3|t
  4|美元定存|美元定存|4|t      5|證券戶|證券戶|5|f    6|信用卡待付款|信用卡待付款|6|f
  ```
  注意 `code` 就是存進 `bank_deposit.deposit_type` 的值（如 `活存`、`優利活存 1.5%`），比對時用 `code` 不是 `displayName`。
- [x] 344.4 `/api/settings/deposit-types` 的 DTO 與前端「存款類型」設定頁新增 `withdrawalOrder` 可編輯欄位（沿用既有 `sortOrder` 的編輯形式與 ADMIN 權限限制）。

  **判準不得改用「名稱含『定存』」或「`annualInterestRate > 0`」，兩者實測都判錯：**

  | 存款列 | `annual_interest_rate` | 用利率判 | 用名稱判 | 正確 |
  |---|---|---|---|---|
  | 美元定存（國泰 131,557／富邦 64,174） | **NULL** | 無息→優先抽 ✗ | 定存 ✓ | 定存 |
  | 優利活存 1.5%（Line Bank 82,372） | 1.5000 | 計息→最後抽 ✗ | 活存 ✓ | 活存 |
  | 定存（富邦／國泰／台新） | 1.7150 | 計息 ✓ | 定存 ✓ | 定存 |

  名稱比對在當前資料上碰巧全對，但 `deposit_type` 是使用者可自行新增的業務分類（CLAUDE.md「禁止 Enum 寫死」），新增一個叫「三個月期存款」的類型就會失效。故存進表裡。

### Backend：引擎輸入的資料結構（純函式邊界）

- [x] 344.5 `LocalPortfolioAllocationEngine` 維持「**不注入任何 Repository、不做 IO、不讀時鐘**」（類別 javadoc 明載，CLAUDE.md 可測試性鐵則：業務邏輯須能在不啟動 Spring context、不連資料庫的情況下單元測試）。持有明細由呼叫端查好後以**扁平 record 清單**傳入。在 `LocalPortfolioAllocationEngine` 內新增：
  ```java
  /** 逐筆持有明細（由 PortfolioAdviceService 查好傳入；本引擎不自行查詢）。 */
  public record HoldingBreakdown(List<Holding> holdings) {}

  public record Holding(
      String assetClass,              // CLASS_CASH／CLASS_FUND／CLASS_STOCK 三者之一
      String subClass,                // SUBCLASS_* 五者之一；存款恆為 null
      String displayName,             // 畫面顯示字串，見 344.15
      BigDecimal currentValue,        // 現值（台幣）
      Integer withdrawalOrder,        // 僅存款有值，其餘 null
      BigDecimal annualInterestRate,  // 僅存款有值，其餘 null
      String currency,                // 僅存款有值（TWD／USD／TRANSIT_* 等），其餘 null；344.10(a) 在途款過濾與 344.11 warning 用
      String stableKey                // 排序 tie-break 用，見 344.8
  ) {}
  ```
  **刻意不放 `shares`**：344.8 的分攤全部以 `currentValue` 計算、344.15 的顯示名稱只用主檔 `name` ＋ `stockCode`，驗證段亦無一處消費股數——留一個沒有消費點的欄位，與本條「輸入面最小化」的原則自相矛盾。日後若要顯示概估股數，另立 AC 說明其消費點與捨入規則再加。
  **採扁平清單而非「群組→清單」的 Map**：分組是引擎的事（既有已有私有 helper 在做子類別分組），輸入端保持扁平才不可能組出「同一標的出現在兩個群組」這種不一致。**不得**在 `Holding` 放 `note` 或預先組好的文案——service 只送資料，`rationale` 一律由引擎組。

### Backend：分攤演算法

- [x] 344.6 新增 `public PortfolioAdviceResult withHoldingLevelRebalance(PortfolioAdviceResult enriched, HoldingBreakdown breakdown)`（**純函式**），比照既有 `withRebalancePlan(...)`／`withSubAllocationAmounts(...)` 的形狀，在 `enrich(...)` 之後串接。分攤群組鍵為 `(assetClass, subClass)`：
  - 股票桶與信託基金桶：依 `targetAllocation[].subAllocations` 的五個子類別各自成組；
  - 存款（現金）桶：`subAllocations` 恆為空陣列（Requirement 82 明訂），整桶為單一群組（`subClass = null`），`T_g` 取 `targetAllocation.deltaAmount`。

  **不得在桶層級對股票／基金分攤**（理由須寫進程式碼註解）：頁面上方的子類別目標表已印出「股票－收益型（高股息）現況 2,993,391 → 目標 3,357,230（增碼 363,839）」，若改用股票**類別** delta（−5,739,699）分攤，8 檔高股息 ETF 會全部被要求減碼，與同一頁上方直接矛盾。

- [x] 344.7 **群組差額 `T_g` 必須先捨入到整數元**：`T_g = subAllocations[].deltaAmount.setScale(0, RoundingMode.HALF_UP)`。方向（BUY／SELL）由**捨入前**的 `signum()` 決定，分攤總額用**捨入後**的 `abs()`。
  **禁止直接拿未捨入的 `subAllocation.deltaAmount` 當分攤總額**——Requirement 82 的 `subAllocation()` 只對 `targetAmount` 做 `divide(..., 0, HALF_UP)`，`currentValue` 是 scale=2 的逐筆加總原值，故 delta 帶兩位小數（實測信託基金長期債為 `345929.51`）；不先捨入會讓 `estimatedAmount` 出現小數，違反 `PortfolioAdviceResult.Rebalance.estimatedAmount` 既有的「新台幣，正數」整數元語意，且守恆不變式會對不上（實測：捨入後五格 Σ = 8,077,733 恰等於桶 delta 8,077,733，不捨入為 8,077,732.51）。

- [x] 344.8 **等比例分攤（股票／基金所有方向，＋存款 BUY 方向）**，每個群組獨立執行：
  ```
  v_i     = 標的現值.setScale(0, RoundingMode.HALF_UP)
  P       = { i : 通過 344.10 過濾 且 v_i > 0 }，V = Σ_{i∈P} v_i
  floor_i = v_i.multiply(T_g).divide(V, 0, RoundingMode.FLOOR)   ← 只做一次乘除
  rem_i   = v_i × T_g − floor_i × V                               ← 整數運算，零誤差
  R       = T_g − Σ floor_i                                       ← 數學上保證 0 ≤ R < |P|
  依 (rem_i 大→小, v_i 大→小, stableKey 字典序 小→大) 排序，前 R 筆各 +1 元
  ```
  **禁止先算權重再乘 `T_g`**（兩次捨入放大誤差）。**tie-break 必須是全序，禁止依賴 `HashMap` 迭代順序**——那會讓同一份輸入在不同次執行產生不同答案。`stableKey`：股票 `market|stockCode`、基金 `fundName`、存款 `bankId|depositType|currency|rowId`。
  選 FLOOR 而非 HALF_UP 的理由：FLOOR 保證 `Σ floor_i ≤ T_g` 且缺口小於標的數，只需單向補足且必然終止；HALF_UP 可能超過 `T_g`，得再定一套往回扣的規則。

- [x] 344.9 **waterfall（存款 SELL 方向，使用者拍板，覆蓋 344.8 的等比例）**：將通過過濾的存款列依
  ```
  (withdrawalOrder 小→大, annualInterestRate 小→大 NULLS FIRST, currentValue 大→小, stableKey 小→大)
  ```
  （欄位名一律用 `Holding` record 的 `currentValue`；其來源是 DB 的 `bank_deposit.amount`——`bank_deposit` 表**沒有** `current_value` 欄。requirements.md 若寫成 `amount` 指的是同一個東西。）
  排序後**逐筆抽取**：前一筆抽滿其 `v_i` 才動下一筆，最後一筆為部分抽取。**未被抽到的存款列完全不產生 `Rebalance`**（不是產生 `HOLD`，是完全不出現——沒有動作就不該佔用清單空間）。
  實測驗算（snapshot 15，需減 2,338,033）：活存富邦 150,082 → 活存國泰 47,562 → 活存華南 5,210 → 美元活存國泰 54,047 → 美元活存富邦 10,750 → 優利活存 Line Bank 82,372 → 定存富邦部分 1,988,010。合計 2,338,033，**只動到一筆定存**。

- [x] 344.10 **納入分攤的標的過濾：兩條串接，順序不可調換**：
  (a) 存款列 `currency` 以 `"TRANSIT_"` 開頭者一律排除，**不進分子也不進分母**；
  (b) 其餘標的中 `setScale(0, HALF_UP)` 後現值 `≤ 0` 者排除。
  被排除者不產生任何 `Rebalance`。
  **(a) 的判準是 `currency` 欄位、不是 `depositType`**，實測 snapshot 15 存的是 `deposit_type='信用卡待付款'` ＋ `currency='TRANSIT_TWD'`。寫成「`depositType` 以 TRANSIT_ 開頭」會讓條件**恆假**、在途款被當成可自由處分的部位攤進去。
  **判定方式必須沿用既有的「兩個字面值全等」寫法，不得改用前綴比對**：全樹沒有 `startsWith("TRANSIT_")`，也**沒有** `AssetService.isTransit(...)` 這支方法（那是區域變數）；既有三處都是
  `"TRANSIT_TWD".equals(currency) || "TRANSIT_USD".equals(currency)`（`AssetService.java:220`／`:239`／`:251`）。改用前綴等於在同一語意上開第四套判準，日後新增 `TRANSIT_EUR` 時新舊碼會靜默分歧。
  **單靠 (b) 不夠**：`transit_fund_type` 中「賣股待收款」「退稅」的 `payable=false`、**金額為正**（實查 `SELECT code,payable FROM transit_fund_type` 回 `信用卡待付款 t／買股待付款 t／賣股待收款 f／退稅 f`），`v > 0` 攔不住，會產生「賣股待收款 減碼 X 元」這種無法執行的建議。
  （反過來說，snapshot 15 現有的五筆 `TRANSIT_TWD` **全為負值**，光靠 (b) 就會被排除——所以 (a) 的真正承重情境是上述 `payable=false` 的正值在途款，不是信用卡待付款。）

- [x] 344.11 **存款群組的分子分母刻意不同源，不得回頭改動上層金額**：存款群組的 `T_g` 維持 Requirement 80／82 已定案的桶 `deltaAmount`（實測 −2,338,033，是**含**在途款的淨額口徑），而分母 `V` 是**排除**在途款後的正值合計（實測 8,544,212；淨額 8,544,212 − 102,124 = 8,442,088 = `asset_snapshot.total_deposit`）。
  **不得為了讓兩者同源去改動「② 我目前的資產配置」卡片或 `targetAllocation` 的存款金額**——那會讓 Requirement 25 的現金口徑一起漂移。差額由 344.12 的 clamp 與條件式 warning 承擔。
  存在被排除的在途款時附加 warning：「你的存款中有 {N} 元屬於在途／轉帳中（信用卡待付款、買股待付款等），不是可自由處分的部位，已排除在分攤之外，但仍計入上方的存款總額。」

- [x] 344.12 **減碼上限（clamp），溢出不得跨群組轉嫁**：SELL 方向時 `amount_i = min(分攤所得, v_i)`。產生溢出總額 `S > 0` 時，在「尚未觸頂」的標的間依同一套 FLOOR ＋ 最大餘額再跑一輪，最多重複 `|P|` 輪（每輪至少一筆固定觸頂，必然終止）。全部觸頂而 `S` 仍 > 0 時停止，把 `S` 記為該群組 shortfall 寫進該群組**最後一列**的 `rationale`（「本群組可賣出上限 {V} 元，仍差 {S} 元未能達成目標，需由其他群組補足」），**不得轉嫁給其他群組**（會讓上下兩區塊再度對不上）。BUY 方向不設上限。

- [x] 344.13 **同一標的跨多列先合併再分攤**：股票以 `(market, stockCode)` 合併、基金以 `fundName` 合併；**存款不合併**，一列 `bank_deposit` 即一個標的。合併列數 > 1 時，`rationale` 末句附「本筆為 {N} 個帳戶／券商的合計」，**不逐一列出各券商金額**。
  **合併鍵不得含 `broker`／`bank`**：實測 snapshot 15 的 `stock_holding` 46 列中，即使加上 broker 仍有 6 組重複（00919 在富邦有 3 列，VOO／VT／SGOV／00865B／00878 各 2 列）——那是分批買入沒併帳的資料雜訊，使用者分不出差別也無法分別執行；不合併的話 00919 會出現 4 行建議。存款不合併的理由：實測 `GROUP BY (bank_id, deposit_type) HAVING count>1` 回 0 rows，且同一銀行的兩筆定存可能到期日不同，合併後 `annualInterestRate` 該顯示哪一個無解。

- [x] 344.14 **最小操作金額門檻**：`public static final BigDecimal MIN_REBALANCE_AMOUNT = BigDecimal.valueOf(10_000);`（具名常數）。同一群組內分攤金額 `< 門檻` 的標的，合併為單一列：`holding` = `其餘 {N} 檔（每檔不足 10,000 元）`、`estimatedAmount` = 各筆合計、`action` 同群組方向。**`N == 1` 時保留原標的名稱不合併**，`rationale` 末句附加提示，且**提示文案分兩種**：股票／基金群組用「本筆金額低於 1 萬元，可視交易成本考慮略過」（門檻錨點是券商最低手續費）；**存款群組不得用「交易成本」措辭**——提領無手續費，且該筆是 waterfall 的**全額抽取**、略過會直接破壞該群組守恆（實測命中的華南活存 5,210 正是此情況），改用「本筆金額較小，可視作業便利性自行斟酌；略過會使存款群組合計短少 {金額} 元」（`{N}` 在本文件專指筆數，金額佔位符一律用 `{金額}`）。
  **不得把小額併進同群組最大一筆**——那會讓 0050 分到的金額不再等於「它的現值占比 × 群組差額」，直接破壞本功能唯一可解釋的規則，使用者第一個會問的就是「為什麼 0050 分到的比例跟它的占比對不上」。合併列同時保住守恆與可解釋性。
  **此門檻值未經回測**，是經驗值（可參考的錨點：券商最低手續費 20 元 ÷ 0.1425% ≈ 14,035 元），須比照既有 `TEMPLATE_DISCLAIMER` 的方式在程式碼註解標明「未經回測」，**不得寫成「研究顯示」**。實測當前快照命中 **3 筆**：GOOGL 7,296（股票-成長型，`11,104 × 5,263,797/8,010,622`）、00882 1,812（股票-收益型，`14,910 × 363,839/2,993,391`）、華南銀行 活存 5,210（存款群組，**waterfall 全額抽取**）。三個群組各只有一筆低於門檻，故**當前資料下不會產生任何合併列**，`N == 1` 分支是唯一會走到的路徑。
  **注意「北富邦美元活存 2,942」與「華南活存 1,426」不是正確的實測值**——那兩個數字是用**等比例**（`× 2,338,033/8,544,212`）算出來的，與 344.9 規定存款 SELL 走 waterfall 直接衝突：waterfall 下這兩筆都是**全額抽取**（美元活存富邦 10,750 ≥ 門檻不觸發、華南活存 5,210 < 門檻觸發）。

- [x] 344.15 **標的顯示名稱**：股票 = `{stock 主檔 name}（{stockCode}）`，查不到 `name` 時**只顯示代號**（不得輸出 `null（0050）`）；基金 = `fundName` 原值；存款 = `{bank.displayName} {depositType}`，`bank` 為 null 時只顯示 `depositType`。
  理由：使用者原話用代號（「例如 0050」）、下單要打代號故不能拿掉；但清單會出現 00865B／00697B／00751B／009804 這類他不見得記得住的代號，只給代號等於要他自己再去查。實測 snapshot 15 的 21 個 `(code, market)` 在 `stock` 主檔 100% 查得到 `name`（0050→元大台灣50、00919→群益台灣精選高息、GOOGL→Alphabet Inc. Class A Common Stock）。
  `stockNameMap` **必須與 `getCurrentAllocation()` 取自同一次 `stockMasterRepo.findAll()`**（做法見 344.20 指定的 `allocationContext()` 重構）——**不得逐筆查 DB、不得再 `findAll()` 一次、不得複製第二套建 Map 的程式碼**。

- [x] 344.16 **「從零建倉」子類別必須輸出，且必須與「系統沒做」在視覺上可區分**：某群組 `P_g = ∅`（無任何可分攤標的）且 **`T_g != null`** 且 `T_g ≠ 0` 時（`T_g` 為 null 代表無快照，依 344.20 整組略過），輸出**恰好一筆** `Rebalance`：
  - `assetClass` = 該桶名
  - `holding` = `{子類別}：尚無持有標的`
  - `action` = 新常數 `public static final String ACTION_UNSPECIFIED = "UNSPECIFIED";`
  - `estimatedAmount` = `T_g.abs()`
  - `rationale` = 「{桶}「{子類別}」目標 {X} 元、目前 0 元。本機引擎只能把金額按比重分攤到你已持有的標的，此子類別沒有部位可分攤，無法指名該買哪一檔；需要具名的基金／個股建議，請把上方「分析引擎」切換為「完整 AI 分析」後重新產生。」

  三個被否決的替代方案與理由（須寫進註解）：**略過** → 實測使用者信託基金四個零部位子類別合計 7,731,803 元（`1,220,811+2,441,622+2,848,559+1,220,811`），**占 `Σ|群組 T_g|` 16,883,143 的約 46%**（若以 `Σ|桶 deltaAmount|` 16,155,465 為分母則約 48%），略過會讓守恆破功且畫面憑空少掉最大一塊；**用 `HOLDING_OVERALL`** → 前端是 `{{ r.holding || r.assetClass }}`，render 結果與本任務之前一模一樣，使用者無法分辨「系統做不到」與「系統沒做」；**沿用 `BUY`** → 綠色「增碼」tag 是可執行動作的視覺語意，套在他無從執行的 773 萬上會誤導。
  `holding` 寫成自我說明字串而非只寫子類別名，是因為這筆會經 `GET /api/public/portfolio-advice/latest`（Requirement 79）以純 JSON 被讀走，脫離 tag 的視覺語境仍須成立。

- [x] 344.17 **`P_g = ∅` 且 `T_g != null` 且 `T_g < 0` 的防禦分支**：同形狀輸出、`action` 同為 `ACTION_UNSPECIFIED`，`rationale` 改為「該群組現況金額來自快照彙總欄位，但找不到對應的持有明細，請於『管理資產』重新儲存快照以重算彙總」。**不得靜默吞掉、不得拋例外**（把使用者可自行修復的資料問題變成 500）。理論上不可達（要減碼代表現況 > 目標 ≥ 0，該群組必有部位），但桶 `currentValue` 來自彙總欄位而 `P_g` 來自逐筆明細，`AssetService.recalcTotals` 只在四個路徑被呼叫，漂移時可達。

- [x] 344.18 **守恆不變式（本功能唯一有價值的正確性保證）**：
  - **無 shortfall 的群組**（絕大多數）：`Σ estimatedAmount`（含 344.14 的合併列）`== |T_g|`。此式等價於「照著這份清單做，就會到達上方那張目標配置表」。**不得為了實作方便放寬容差**。
  - **344.12 的 shortfall 群組**（全部標的皆 clamp 觸頂仍不足）：`Σ estimatedAmount == V`（該群組可賣出上限），且 `|T_g| − V == S`。**`S` 只寫進 `rationale` 文字、不進 `estimatedAmount`**（`Rebalance.estimatedAmount` 是實際可執行金額，塞一個做不到的數字進去會讓使用者照著下單卻下不掉），故此情境下 `Σ estimatedAmount < |T_g|` 是**正確行為**，不是 bug。
  最大餘額法下任一筆與理論比例值的偏差 `≤ 1 元`。

- [x] 344.19 **類別層級的三筆維持不變**：既有的三筆類別層級 `Rebalance`（`holding = HOLDING_OVERALL = "整體"`）**保留**，標的層級明細掛在其下（前端渲染時成為分組標題，見 344.23）。使用者仍需要「這一類總共要動多少」的總覽；移除它會破壞 Requirement 80／82 既有的行為與測試。

### Backend：串接與文案

- [x] 344.20 `PortfolioAdviceService.buildLocalResult(...)`（約 `:694`，現況為）：
  ```java
  PortfolioAdviceResult base = localEngine.evaluate(profile.getRiskTolerance(), years, current, projection);
  PortfolioAdviceResult withPlan = localEngine.withRebalancePlan(enrich(base, totalAssets));
  return localEngine.withSubAllocationAmounts(withPlan, current, profile.getRiskTolerance(), years);
  ```
  改為在最後再串一支 `withHoldingLevelRebalance(...)`，並在此方法內組出 `HoldingBreakdown`。

  **⚠ 逐筆持有列拿不到 `getCurrentAllocation()` 裡面那個 `AssetSnapshot`。** `getCurrentAllocation()`（`:314` 起）的 `AssetSnapshot s` 是**區域變數**（`:316`），回傳的 `CurrentAllocationDto`（`snapshotId`／`snapshotDate`／`totalAssets`／`List<Item>`，`Item(assetClass, value, pct, List<SubItem>)`）**不含任何逐筆持有列**。故：
  - **`buildLocalResult(...)` 新增第三個參數 `AssetSnapshot snapshot`**——`generate()` 已於 `:617` 取得 `AssetSnapshot snapshot = snapshotRepo.findLatest().orElse(null)`，直接把它傳進來即可，不必重查。由此讀 `snapshot.getStocks()`／`snapshot.getFunds()` 組 `HoldingBreakdown` 的股票與基金部分。`snapshot` 為 null 時（無快照）`HoldingBreakdown` 為空清單——但**此時 `T_g` 也是 null，不得走 344.16／344.17**：`generate()` 在無快照時 `totalAssets` 保持 null（`:630` 的 if 不進），`enrich(...)`（`:1391`）因 `totalAssets == null` 原封返回，`targetAmount`／`deltaAmount` 全為 null，`subAllocation()`（`LocalPortfolioAllocationEngine.java:485`）的 delta 亦全為 null。照 344.16 寫 `T_g.signum()`／`T_g.abs()` 會直接 **NPE**。此情境須比照既有慣例**整組略過、不輸出任何列**——`withRebalancePlan` 的 javadoc（`:394`）明載「差額未知（無資產快照）者略過——沒有金額就沒有可執行動作」，實作為 `:403` 的 `if (t == null || t.deltaAmount() == null) continue;`（無快照時 `rebalancePlan` 本來就是空的，不是三筆）。
  - 存款：以 `snapshot.getId()` 呼叫既有 `depositRepo.findWithBankBySnapshotId(...)`（`BankDepositRepository.java:17-18`，已 `LEFT JOIN FETCH d.bank`，否則取 `bank.getDisplayName()` 會觸發 N 次 lazy load）。
  - **不得改動 `getCurrentAllocation()` 的簽章或 `CurrentAllocationDto` 的形狀**——那是已對外的契約：`PortfolioAdviceController.java:73` 的 `GET /api/portfolio-advice/current-allocation`，由 `PortfolioAdviceBffController.java:68` 消費，另有 `PortfolioAdviceServiceEngineTest.java:216,220` 兩處直接呼叫。
  - **不得**為了把券商分佈寫進 `rationale` 而新增 join-fetch broker/bank 的 repository 方法（該需求已於 344.13 否決）。

  **不要在規格或註解裡宣稱「零額外查詢」或「同一 `@Transactional` session」**：`buildLocalResult` 呼叫 `getCurrentAllocation()` 是**自我呼叫**，Spring proxy 被繞過，`:314` 的 `@Transactional(readOnly = true)` 不生效；`generate()` 本身也沒有 `@Transactional`。目前 lazy collection 能載入靠的是 OSIV 預設值（全樹無 `open-in-view` 覆寫）。
  **⚠ `stockNameMap` 與三份 override Map 目前沒有出口，必須先做一個小重構，否則本條無解。** 這些全是 `getCurrentAllocation()` 的**區域變數**（`PortfolioAdviceService.java:326-329` 的 `stockClassOverride`／`stockStyleOverride`／`bondTermOverride`／`stockNameMap`，`:330` 是全檔唯一的 `stockMasterRepo.findAll()` 呼叫點，`:338` 的 `fundClassOverrideRepo.findAll()`、`:341` 的 `incomeThreshold` 同理），而回傳的 `CurrentAllocationDto` 不含其中任何一份。在「不得改 public 簽章／DTO」的前提下，實作者若不重構就只剩「再 `findAll()` 一次」或「複製那 15 行」兩條爛路——後者正好落進本條要避免的「兩套會漂移」。

  **指定做法**：把 `getCurrentAllocation()` 的本體抽成 private 方法（例如 `allocationContext()`），回傳一個 private record 同時帶出 `CurrentAllocationDto` ＋ `stockNameMap` ＋ 三份 override Map ＋ `incomeThreshold`；**public `getCurrentAllocation()` 改為單純委派並只回傳其中的 DTO，簽章與 `CurrentAllocationDto` 形狀完全不變**（對外契約不動）；`buildLocalResult` 改呼叫該 private 方法，一次取得分類所需的全部素材。

  逐筆持有的 `subClass` 判定**必須複用** Requirement 82 已落地的同一套 `AssetClassifier` 呼叫（`classifyStock`／`classifyFund` → `classifyStockStyle`／`classifyBondTerm`）與上述同一份 override Map，**不得寫第二套分類邏輯**——兩套一定會漂移，且「② 我目前的資產配置」與「再平衡明細」對同一檔股票會歸到不同子類別。

- [x] 344.21 **常數改名 ＋ 文案整條替換**：`NO_HOLDING_LEVEL_WARNING`（`LocalPortfolioAllocationEngine.java:85-88`）**改名為 `HOLDING_LEVEL_SCOPE_WARNING`**——原名意為「本機檔位**不產生**個股層級建議的明示限制」，新文案語意正好相反，留著原名會變成反話。改名須同步兩處既有測試引用：`PortfolioAdviceServiceEngineTest.java:240` 與 `LocalPortfolioAllocationEngineTest.java:189`（兩處都是 `contains(NO_HOLDING_LEVEL_WARNING)`，只換識別字、斷言邏輯不變）。該常數的 javadoc（`:85`）也要一併改寫。新文案須同時說清楚**做了什麼**與**沒做什麼**：
  > 「本檔位的調整動作已細到你**現有持有的**個別銀行、基金與個股，做法是把各子類別的差額按你目前的現值比重攤下去（存款的減碼則依提領優先序逐筆抽取，優先動活存以避免定存中途解約的利息損失）。**但它不會推薦你沒持有過的新標的**，也**不判斷哪一檔比較該賣**——等比例分攤預設同一子類別內每一檔一樣好，未考慮個別標的的套牢或獲利狀態、交易成本與最低手續費、稅務，以及基本面差異。需要具名新標的或個股優劣判斷，請切換為「完整 AI 分析」。」

  同時 `LocalPortfolioAllocationEngine` 類別 javadoc 中的「相對 LLM 的已知退化（明示接受）」段落（約 `:38-41`）須一併更新，不得留下與程式碼行為矛盾的註解。

- [x] 344.22 **存款動到定存時的條件式 warning**：waterfall 實際抽取到 `withdrawalOrder >= 90` 的存款列時，附加 warning：「本次減碼會動到定期存款 {合計} 元（{逐筆列出銀行與金額}）。定存中途解約通常按實際存期折算利息，是可量化的損失；若你的銀行不支援部分解約，實際可動用金額會與此處不同。」**只在真的抽到時出現**，不得無條件輸出。

### 前端

- [x] 344.23 **【本項於 arch-auditor 稽核後改設計、已重做完成】分組與排序搬進 BFF，前端只 render。**
  原設計把分組寫在 `AssetAllocationAdviceView.vue`，經架構稽核判為違反 `spec/steering/structure.md` §3.2 第 5 條與 CLAUDE.md BFF 規範第 1 節（「BFF 預先聚合／排序／過濾，**前端只負責 render**」），且前端為此手抄了後端六個字面值（`整體` 與五個子類別名）——目前值一致、無 bug，但屬兩份手抄、必然漂移。使用者已拍板「現在就搬進 BFF 再上線」。
  - **(1) BFF**：`bff/src/main/java/com/steven/assets/bff/portfolioadvice/PortfolioAdviceBffController.java` 在既有 `Mono.zip(...)` 的 `.map(...)` 內追加 `rebalanceGroups`。**必須先 `new HashMap<>(latest)` 複製再 put，不得直接對 `latest` put**——`PortfolioAdviceBffController.java:49` 的 `.onErrorReturn(Collections.emptyMap())` 在 business 不可用時回的是**不可變** Map，直接 put 會拋 `UnsupportedOperationException`，把既有「200 ＋ 空資料」的降級路徑變成 500。既有先例：`SnapshotDetailBffController.java:57-58`、`DashboardBffController.java:216-217` 皆複製後再 put。輸出形狀：
    ```
    rebalanceGroups: [{ assetClass, header, sections: [{ subClass, rows: [...] }] }]
    ```
    `header` = 該 `assetClass` 的類別層級那一筆（`holding` 等於 `"整體"`）。
    **整份 `rebalancePlan` 中不存在任何 `holding == "整體"` 的列時（`llm` 檔位、以及本任務落地前的舊建議），`rebalanceGroups` 一律輸出空陣列**——不得產生 `header: null` 的群組。理由：BFF 若為 `llm` 產生非空 groups，LLM 交錯輸出的列會被依 `assetClass` 重排，牴觸「`llm` 檔位維持現況渲染」；輸出空陣列讓前端走扁平 fallback、逐字維持現況。
    **子類別段落順序不得寫死清單**——由同一份回應的 `latest.targetAllocation[].subAllocations[].subClass` 的出現順序推導（那是引擎產生的正規順序：成長型→收益型（高股息）→短期債→中期債→長期債）。`subClass` 為 null 者（存款群組明細）併為單一無小標段落，置於最後。
    **`rows` 為空的 section 一律不輸出**（否則畫面會出現五個空小標）；`rebalanceGroups` 依各 `assetClass` 在 `rebalancePlan` 中**首次出現的順序**排列（排序是 BFF 職責，`structure.md` §3.2 第 5 條）。**推導清單以外的 `subClass`** 一律接在已知段落之後、無小標段落之前，**不得丟棄**（現行資料不會發生，屬防禦性要求——前端待搬的那段是「挑不到就丟」的形狀，照抄會靜默吞列）。
    **BFF 只准出現一個字面值 `"整體"`**（須加註解說明它鏡像 `LocalPortfolioAllocationEngine.HOLDING_OVERALL`）；五個子類別名稱**一個都不許複製**。
    分組邏輯抽成同 package 的獨立類別（例如 `RebalanceGrouper`）以便單元測試，**不要塞在 controller 方法裡**；本頁專屬故放 `bff/portfolioadvice/`，不放 `bff/common/`（後者是跨頁共用）。
    須有單元測試：(i) 依 `subAllocations` 推導的段落順序正確；(ii) `subClass` 為 null 者集中且置末；(iii) 無 `"整體"` 筆數的 `assetClass`（`llm` 形狀）→ `header: null`；(iv) **`latest` 為空 Map（下游失敗降級）、`targetAllocation` 缺漏或為 null、`subAllocations` 為 null（`llm` 檔位序列化為 null 鍵、非缺鍵）三種情況皆不拋例外且輸出空陣列**；(v) 推導清單以外的 `subClass` 不被丟棄。
  - **(2) 前端**：`AssetAllocationAdviceView.vue` **刪除** `REB_SUBCLASS_ORDER`、`sectionizeRebalanceDetails`、`HOLDING_OVERALL` 與 `groupedRebalancePlan` 內的分組邏輯，改為直接 `v-for` 走 `latest.rebalanceGroups`。`rebalanceGroups` 缺漏或為空時退回既有扁平 `rebalancePlan` 渲染（向後相容）。
  - **(3) 保留不動**：`rebalanceLabel()`／`rebalanceType()` 的 `UNSPECIFIED` case（標籤「無法指名」、tag `warning`）、預設全展開不摺疊、既有 CSS class。
  - **(4) 部署注意**：本項動到 `bff/`，`run-stack` 階段除 `business-services`／`frontend` 外**須一併重建 `bff`**。
  **【以下 (a)～(d) 為改設計前的原文，保留作為稽核痕跡；(b)(d) 已被上方 (1)(2) 取代，不得照做】**
  (a) 【仍有效，見上方 (3)】兩支 switch 各補 `UNSPECIFIED` case（標籤「無法指名」、tag `warning`）。
  (b) ~~以 `assetClass` 分組、組內依 `subClass` 五個字面值分段、順序固定寫死~~ **【已由上方 (1) 取代：分組與排序改在 BFF，段落順序由 `subAllocations` 推導，BFF 與前端皆不得持有五個子類別字面值】**
  (c) 【仍有效，見上方 (3)】預設全部展開、不做摺疊。
  (d) ~~向後相容由前端負責~~ **【已由上方 (1)(2) 取代：`rebalancePlan` 中不存在任何 `holding == "整體"` 的列時（`llm` 檔位與本任務落地前的舊建議），BFF 一律輸出空的 `rebalanceGroups`，前端據此退回既有扁平渲染，逐字維持現況、不重排 LLM 的輸出順序】**

- [x] 344.24 **不新增 API endpoint**：沿用既有 `GET /api/bff/portfolio-advice` 聚合端點，`rebalancePlan` 已在其回應內。BFF 為 `Map<String,Object>` 全量 passthrough，無須改動。

- [x] 344.25 **`llm` 檔位完全不受影響**：`runGeneration(...)` 一行不改，其 `rebalancePlan` 仍由 LLM 產生（本來就能給具名新標的）。本任務所有邏輯只在 `local`／`hybrid` 兩檔位生效，且兩檔位共用 `buildLocalResult()` 這唯一入口（`hybrid` 的 LLM 只改寫 `summary`／`riskAssessment` 兩個文字欄位，Requirement 80 該保證不受影響——標的層級明細屬於數字欄位，仍全部由本機決定）。

### 既有測試的連動

- [x] 344.26 既有測試的連動，**共三處會變紅，逐一更新而非刪除**（刪掉會失去「類別層級三筆仍在」這條保護，見 344.19）：
  - `PortfolioAdviceServiceEngineTest.java:233` — `assertEquals(3, result.rebalancePlan().size());`
  - `PortfolioAdviceServiceEngineTest.java:235` — `assertEquals("整體", r.holding(), ...)`
  - `PortfolioAdviceServiceEngineTest.java:236` — `assertTrue(List.of("BUY","SELL","HOLD").contains(r.action()));` ← **須加入 `"UNSPECIFIED"`**。這行不含 `HOLDING_OVERALL` 也不含 `rebalancePlan`，用直覺的 grep 抓不到，是最容易漏的一處。

  改斷言時注意：該測試的 fixture（`snapshot()`，`:558-567`）**只設 `totalDeposit`／`totalFundValue`／`totalStockValue`／`totalAssets`，完全沒有 `stocks`／`funds`**（`AssetSnapshot.java:88`／`:92` 的預設值是空 `ArrayList`，非 null），加上 `:115` 已 stub `depositRepo.findWithBankBySnapshotId(...)` 回空清單——**股票、基金、存款三桶的所有 `T_g ≠ 0` 群組全部走 344.16 的 `P_g = ∅` 分支**，各多產一筆 `UNSPECIFIED`。故 `:233` 的預期筆數是「類別層級三筆 ＋ 每個 `T_g ≠ 0` 的子類別／存款群組各一筆」，**不是「3 ＋ 股票明細 ＋ 1」**，實作時依當下模板比例實際數出。（`:117` 的 `stockRepo` stub 與本功能無關——依 344.20，股票來源是 `snapshot.getStocks()`。）

  搜尋指令用：
  ```bash
  grep -rn 'HOLDING_OVERALL\|rebalancePlan\|"BUY", "SELL", "HOLD"' backend/src/test/
  ```
  **已確認不受影響、不必動的**（寫在這裡讓實作者不必重查）：`PortfolioAdviceServiceEngineTest.java:221` 的管線一致性測試只斷言 `targetAllocation()`、不比對整個 result；`LocalPortfolioAllocationEngineTest.java:243-253` 測的是 `withRebalancePlan` 這一支方法本身，本任務不改該方法。

## 驗證

**單元測試（純函式，不啟 Spring context、不連 DB）**

至少涵蓋：
- (a) 守恆不變式 `Σ estimatedAmount == |T_g|`（**無 shortfall 的群組**，見 344.18）對**七個**實測群組全部成立，每一組都要標桶名以免撞名（其中前六組走 344.8 的 FLOOR ＋ 最大餘額法，**存款組走 344.9 的 waterfall**、演算法不同但守恆同樣須成立，見 (e)(f)）——
  股票-成長型 5,263,797／股票-收益型 363,839／股票-短期債 480,128／股票-中期債 107,250／股票-長期債 252,363／
  **信託基金-長期債 345,930（來源值 `345929.51`，全部群組中唯一會觸發 344.7 捨入者，漏掉這組等於捨入規則沒有任何測試覆蓋）**／存款 2,338,033。
  信託基金另四個子類別（成長型 1,220,811／收益型 2,441,622／短期債 2,848,559／中期債 1,220,811）現況皆為 0（`fund_holding` snapshot 15 只有一列「施羅德環球收益債券非常避險 (月配)」61,007.49），走 344.16 的 `P_g = ∅` 分支，不適用本條；
- (b) tie-break 為全序：同一份輸入把 `holdings` 清單打亂順序後，輸出的每一筆 `(holding, estimatedAmount)` 完全相同；
- (c) `T_g` 先捨入：輸入帶小數的 `deltaAmount`（如 `345929.51`）不得產生小數 `estimatedAmount`；
- (d) 在途款以 `currency` 前綴排除，且 `payable=false` 的**正值**在途款也被排除；
- (e) 存款 SELL 走 waterfall 且依 `withdrawalOrder` 排序、存款 BUY 走等比例；
- (f) 存款 waterfall 在實測資料上只動到一筆定存（斷言 `withdrawalOrder >= 90` 的列恰好一筆被輸出）；
- (g) clamp 觸頂後的重分配與 shortfall 記錄；
- (h) `P_g = ∅` 且 `T_g > 0` 產生 `ACTION_UNSPECIFIED` 一筆、`T_g < 0` 走 344.17 防禦分支；
- (i) 最小金額門檻的合併列，以及 `N == 1` 時不合併但附加提示；
- (j) 同標的跨券商／跨銀行合併（00919 三列合併為一筆）；
- (k) 股票顯示名稱在 `stock` 主檔查無 `name` 時只輸出代號、不得出現 `null（`；
- (l) `llm` 檔位既有行為不回歸；
- (m) `deposit_type.withdrawal_order` 的**產品 seed 值**（新 DB 建立時）與 **changeset 一次性回填**（已部署 DB）各自生效。**不得斷言「已調整過的值不被覆蓋」**——見 344.3，`AND withdrawal_order = 50` 的作用是讓 changeset 可重複執行，使用者若剛好把某類型調成 50，重跑仍會被覆蓋。

**建置與實機驗證**

```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true
```
> **必須用 `-DextraArgLine`，不得用 `-DargLine`**——後者會覆蓋掉 pom 既有的時區設定，造成 181 個測試 error，且錯誤訊息會偽裝成 byte-buddy 問題。

```bash
cd /Users/steven/Project/asset-management/.claude/worktrees/trading-radar-no-trades-a47b9f
cp /Users/steven/Project/asset-management/.env .env    # worktree 沒有 .env；env_file 相對 compose 檔解析
docker compose -p asset-management build --no-cache business-services frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services frontend
docker compose -p asset-management restart bff          # recreate 上游後必做，否則 BFF 握舊 IP 回 500
curl -sI http://localhost/ | head -1
```

端到端（容器內 `X-User-*` header，免走 Google OAuth）：
```bash
docker exec asset-business-services curl -s -X POST \
  -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" \
  -H "Content-Type: application/json" -d '{"riskTolerance":"BALANCED"}' \
  http://localhost:8080/api/portfolio-advice/generate | python3 -m json.tool
```
斷言：
1. `rebalancePlan` 含類別層級三筆（`holding == "整體"`）＋標的層級明細；
2. 存款明細只出現 waterfall 抽到的那幾筆，且恰有一筆 `withdrawalOrder >= 90`（定存）；
3. 股票明細出現 `元大台灣50（0050）` 這種「中文名（代號）」格式；
4. 信託基金四個零部位子類別各出現一筆 `action == "UNSPECIFIED"`；
5. 每個群組的 `Σ estimatedAmount` 等於該群組 `|deltaAmount|`（捨入後）；
6. `warnings` 含改名後的 `HOLDING_LEVEL_SCOPE_WARNING` 新文案、在途款 warning、定存 warning。

前端以瀏覽器開頁確認分組渲染與「無法指名」tag 顯示正常（需 Google 登入，由使用者自行確認或以 `run-stack` 截圖）。

## 完成報告

### 實際改動的檔案

| 檔案 | 內容 |
|---|---|
| `backend/src/main/resources/db/changelog/changes/v1.107.0-deposit-type-withdrawal-order.sql`（新） | 344.1：`ADD COLUMN IF NOT EXISTS withdrawal_order` ＋ 五筆既有資料回填 UPDATE |
| `backend/src/main/resources/db/changelog/db.changelog-master.yaml` | 尾端註冊上述 changeset |
| `backend/src/main/java/com/steven/assets/model/DepositTypeEntity.java` | 344.2：`withdrawalOrder` 欄位 ＋ `DEFAULT_WITHDRAWAL_ORDER = 50` |
| `backend/src/main/java/com/steven/assets/config/DataInitializer.java` | 344.3(a)：`DepositTypeSeed` 加 `withdrawalOrder`，六個內建類型各給值（不含使用者私有分類） |
| `backend/src/main/java/com/steven/assets/dto/InstitutionDto.java`／`service/InstitutionService.java` | 344.4：三個 DepositType DTO ＋ create／update 支援 `withdrawalOrder` |
| `frontend/src/views/DepositTypeSettingsView.vue` | 344.4 前端：列表欄位 ＋ 編輯對話框 `el-input-number`（沿用 `sortOrder` 形式）＋ 說明文字 |
| `backend/src/main/java/com/steven/assets/dto/PortfolioAdviceResult.java` | `Rebalance` record 新增第二欄 `subClass`（見下方偏差 1） |
| `backend/src/main/java/com/steven/assets/service/LocalPortfolioAllocationEngine.java` | 344.5～344.19、344.21、344.22：`HoldingBreakdown`／`Holding` record、`withHoldingLevelRebalance`、FLOOR ＋ 最大餘額、waterfall、clamp／shortfall、門檻合併、`ACTION_UNSPECIFIED` 兩分支、常數改名與四條新文案常數 |
| `backend/src/main/java/com/steven/assets/service/PortfolioAdviceService.java` | 344.20：`allocationContext()` 重構 ＋ `buildLocalResult(..., AssetSnapshot)` ＋ `holdingBreakdown(...)` ＋ `subClassLabel(...)` 抽出 ＋ 注入 `DepositTypeRepository` |
| `backend/src/test/.../LocalPortfolioAllocationEngineHoldingRebalanceTest.java`（新，15 tests） | 驗證 (a)～(j) |
| `backend/src/test/.../config/DepositTypeWithdrawalOrderSeedTest.java`（新，4 tests） | 驗證 (m) |
| `backend/src/test/.../PortfolioAdviceServiceEngineTest.java` | 344.26 三處連動 ＋ 驗證 (k)(l)（新增 4 tests） |
| `backend/src/test/.../LocalPortfolioAllocationEngineTest.java`／`PortfolioAdviceServiceCurrentAllocationTest.java` | 常數改名同步、建構子參數同步 |

### 驗證輸出

- `mvn -q -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true`
  → **Tests run: 1142, Failures: 0, Errors: 0, Skipped: 0 / BUILD SUCCESS**
- `node ./node_modules/.bin/vite build` → **exit 0**
- **Docker 重建與端到端驗證（主 agent 於全部實作完成後執行）**：
  `docker compose -p asset-management build --no-cache business-services bff frontend` → exit 0；
  `up -d --no-deps --force-recreate` 三支皆轉 `healthy`（compose 依序：business healthy → bff 才啟動，故無 DNS 舊 IP 問題）；
  `docker logs asset-bff --since 3m | grep -cE "Connection refused|500 Server Error"` → **0**。
  - **Liquibase**：`databasechangelog` 最新一筆為 `v1.107.0-deposit-type-withdrawal-order`／`EXECUTED`；
    `deposit_type` 回填結果 `活存=10／美元活存=20／優利活存 1.5%=30／定存=90／美元定存=91`，
    停用中的 `證券戶`／`信用卡待付款` 維持 50 —— 與 344.3 的 (a)(b) 兩段式設計逐項相符。
  - **防 stale image**：前端 bundle `AssetAllocationAdviceView-DEiFh_gB.js` 含 `rebalanceGroups`；
    `asset-bff` jar 含 `BOOT-INF/classes/.../portfolioadvice/RebalanceGrouper.class`；
    business jar 的 `LocalPortfolioAllocationEngine.class` 含 `withHoldingLevelRebalance`／`UNSPECIFIED`。
  - **端到端（容器內 `X-User-*` header，免走 Google OAuth）**：`POST /api/portfolio-advice/generate`（`BALANCED`）
    → `status=OK`、`model=local-allocation:v1`、`rebalancePlan` **37 筆**（類別層級 3 筆 ＋ 標的層級 34 筆）。
    - 存款走 waterfall：活存(富邦 237,454／國泰 42,099／華南 5,210) → 美元活存(國泰 54,047／富邦 10,750)
      → 優利活存(120,000) → **定存僅動一筆**（台北富邦 1,789,673）；國泰／台新定存與兩筆美元定存完全未出現。
    - 股票依子類別分攤：成長型全數 SELL（0050 2,378,656／2330 790,238／…）、收益型全數 BUY（00919 72,873／2881 63,808／…），
      同桶同時買賣如使用者拍板所預期；顯示名稱為「中文名（代號）」格式。
    - 信託基金四個零部位子類別各輸出一筆 `action=UNSPECIFIED`、`holding="{子類別}：尚無持有標的"`，長期債走既有持股。
  - **守恆不變式實測**：11 個群組（存款整桶 ＋ 股票五子類 ＋ 基金五子類）的 `Σ estimatedAmount` **全部精確等於**該群組
    捨入後的 `|T_g|`，零誤差、零 shortfall。
    附註：「信託基金桶 delta 8,054,472」與「其五個子類別 T_g 加總 8,054,473」相差 1 元——這是 **Requirement 82 既有**的
    獨立捨入行為（每筆 `subAllocation.deltaAmount = targetAmount − currentValue` 各自捨入），**非本任務引入**；
    本任務自身的不變式（每組 Σ == 該組 T_g）不受影響。
  - **`warnings` 七條**齊備且內容正確：⚠ 定性、改名後的 `HOLDING_LEVEL_SCOPE_WARNING` 新文案、風險/退休試算兩條既有提醒、
    股票桶含債券型標的（903,409 元）、在途款排除（−218,047 元）、**動到定存的條件式 warning**（1,789,673 元，逐筆列出銀行）。
  - **未執行**：前端畫面的視覺驗證。`GET /api/bff/portfolio-advice` 受 OAuth 保護，且不得代使用者輸入帳密；
    BFF 的 `rebalanceGroups` 聚合由 `RebalanceGrouperTest`（10 tests）覆蓋，前端 render 由 `vite build` 與程式碼審閱確認。
    使用者可自行登入頁面確認分組小標的視覺效果。

### 與原計畫的偏差

1. **`Rebalance` record 新增 `subClass` 欄位（任務檔未寫）。** 344.23(b) 要求「組內以子類別小標分段」，但原 `Rebalance` 沒有任何子類別欄位，資料面做不到。已新增為第二欄；**類別層級三筆與 `llm` 檔位一律 null**，`@JsonIgnoreProperties(ignoreUnknown = true)` 使既有 `llm` JSON（無此欄）仍正常解析為 null（有測試覆蓋）。
2. **`Holding` record 為 8 欄，多一個 `currency`。** 344.10(a) 的在途款判準是 `currency`、344.11 的 warning 需要被排除的金額，而驗證 (d) 明列為**純函式**單元測試——三個消費點都在引擎內，7 欄版本無法實作。`shares` 仍如原計畫不放（無消費點）。**AC 344.5 的 `Holding` record 定義已回填校正為 8 欄**（spec-review 複審 finding），不再與本條偏差說明及 344.10(a) 自相矛盾。
3. **`PortfolioAdviceService` 新增建構子參數 `DepositTypeRepository`。** 提領優先序存在 `deposit_type` 表（344.4 的「禁止 Enum 寫死」），服務端必須查得到。連動更新兩支既有測試的建構子呼叫（超出 344.26 列出的三處）。
4. **`PortfolioAdviceService.stockDisplayName(...)` 為 package-private static**（非 private），以便驗證 (k) 在不啟 Spring context 的情況下直接測。
5. **小額列（`N == 1`）保留原位置、不移到群組末尾。** 任務檔只寫「保留原標的名稱不合併」。存款走 waterfall 時列的先後即提領順序，把唯一一筆小額移到最後會讀不出提領順序（實測命中的華南活存 5,210 正是此情況）。`N ≥ 2` 的合併列仍置於群組末尾。
6. **`withRebalancePlan` 的類別層級 `rationale` 末句改寫**：原文「本檔位只到類別層級，實際要動哪一檔請自行決定」在本任務後成為與程式行為矛盾的使用者可見文字，改為「本類的總量如上，逐一標的的建議金額見以下明細」。
7. **344.11 的 warning 措辭微調**：實測在途款為負值（信用卡待付款），原句「有 {N} 元屬於在途」帶負號會讀不通，改為「有在途／轉帳中的部位（…）合計 {N} 元」，語意不變。
8. **344.23(b) 的「組內以子類別小標分段」已於後續補做完成**（先前因 `Rebalance` 無子類別欄位而卡住，偏差 1 補上 `subClass` 後解除）。實作為 `groupedRebalancePlan` 內新增 `REB_SUBCLASS_ORDER` 固定順序陣列 ＋ `sectionizeRebalanceDetails(rows)`，把每組 `details` 依 `subClass` 分段成 `sections`；順序由該陣列驅動、**不依賴物件鍵迭代順序**，`subClass` 為 null 者（存款群組）收進單一無小標段落。新增 CSS `.reb-sub-section`／`.reb-sub-title`（有底色的識別標籤，非灰字——少了它使用者會把「同桶同時買賣」讀成系統自相矛盾）。`vite build` exit 0。
   **【最終狀態】** 344.23 已依改後設計重做完成：BFF 新增 `RebalanceGrouper`（純函式 final class ＋ private ctor，`bff/portfolioadvice/` 本頁專屬）與 `RebalanceGrouperTest`（10 tests）；`PortfolioAdviceBffController` 以 `new HashMap<>(t.getT1())` 複製後 put `rebalanceGroups`（保住 `onErrorReturn(Collections.emptyMap())` 的降級路徑）。段落順序由 `targetAllocation[].subAllocations[].subClass` 推導，**BFF 可執行程式碼只有 `"整體"` 一個字面值、五個子類別名零複製**（六份手抄降為一份）。前端刪除 `REB_SUBCLASS_ORDER`／`sectionizeRebalanceDetails`／`HOLDING_OVERALL`／分組邏輯，改純 `v-for` 走 `rebalanceGroups`，扁平 fallback 與 HEAD 逐字一致。驗證：bff 133 tests、backend 1142 tests 全綠，`vite build` exit 0。arch-auditor 複審 **0 critical／0 major／0 minor**，確認上輪 major 已消除。

   **【後續更正】** 此前端實作已於 arch-auditor 稽核後被 344.23(1)(2) 推翻——分組與排序改在 BFF（`rebalanceGroups`），前端刪除 `REB_SUBCLASS_ORDER`／`sectionizeRebalanceDetails`／`HOLDING_OVERALL` 與分組邏輯、改為純 render。本段保留作為 as-built 歷史痕跡。
