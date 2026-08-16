# [t341] 資產配置建議「股票」「信託基金」子類別細分（成長型／收益型／短中長期債）

**對應 Requirements:** Requirement 82（資產配置建議頁的「股票」「信託基金」兩個頂層類別，現況與建議目標配置皆再細分成長型／收益型（高股息）／短期債／中期債／長期債五個子類別，接上既有 Requirement 25/26/27 的分類骨架）
**前置任務:** t339（`LocalPortfolioAllocationEngine` 三態引擎既有結構，本任務在其上擴充，不重寫）
**Liquibase changeset:** 無（完全複用既有 `asset_class`／`stock_style`／`bond_term`／`fund_class_override` 表與其 override 機制，不新增欄位、不新增表）

## 背景

「資產配置建議」頁（`AssetAllocationAdviceView.vue`）目前把使用者的資產壓縮成「存款（現金）／信託基金／股票」三個數字，現況與建議目標配置都只有這三列。但「信託基金」與「股票」內部組成差異很大——同樣是「股票 55%」，全部是成長股跟一半是高股息 ETF 意義完全不同；同樣是「信託基金 40%」，全部是短債基金跟一半是長期債券基金的利率風險完全不同。使用者（本專案唯一使用者）反映：不分子類別，不知道具體該怎麼配置。

系統其實已經有完整的分類骨架，只是沒有接到這個功能：`AssetClassifier`（`backend/src/main/java/com/steven/assets/service/AssetClassifier.java`）提供 `classifyStock`／`classifyFund`（判定現金/債券/股票）、`classifyStockStyle`（判定成長/收益型，殖利率門檻可調整）、`classifyBondTerm`（判定短/中/長期）；個股 override 存 `stock` 表的 `asset_class`／`stock_style`／`bond_term` 欄；基金 override 存 `fund_class_override` 表（PK=`fund_name`）。這套骨架被 `AssetService.getAssetHistory()`／`getHoldingsClassified()` 用在「資產配置分佈」圓餅圖（Requirement 25/26/27 已落地），但 `PortfolioAdviceService.getCurrentAllocation()` 至今**只讀 `AssetSnapshot` 的三個彙總欄位**（`totalDeposit`／`totalFundValue`／`totalStockValue`），完全不 join 任何分類表——這是本任務要補的缺口，不是重新設計分類邏輯。

**正確行為（本任務完成後）**：
1. 現況：「股票」「信託基金」兩個頂層桶底下各自列出成長型／收益型（高股息）／短期債／中期債／長期債，只顯示金額 > 0 的子類別。
2. 建議目標配置：`local`／`hybrid` 檔位的「股票」「信託基金」兩列同樣列出目標子分配（依風險承受度×距退休年數的固定次分配表），與現況並列比較，能看出「目前股票裡收益型太少」之類的具體落差。
3. `llm`（完整 AI）檔位不受影響——其 prompt／解析／`runGeneration(...)` 一行不改，子分配欄位在該檔位固定為 `null`。
4. 頂層三類（存款/信託基金/股票）的金額、占比、`targetAmount`、`deltaAmount` 完全不變——這是 Requirement 80 的既有保證，本任務不得破壞。

## 要做什麼

### Backend：現況子分類

- [x] 341.1 `PortfolioAdviceService`（`backend/src/main/java/com/steven/assets/service/PortfolioAdviceService.java`）新增以下欄位注入（皆為既有 Spring bean，非新建）：`AssetClassifier assetClassifier`、`StockRepository stockMasterRepo`、`FundClassOverrideRepository fundClassOverrideRepo`、`StockStyleRepository stockStyleRepo`。
- [x] 341.2 改寫 `getCurrentAllocation()`（現況 `:291-306`，邏輯完全比照 `AssetService.getHoldingsClassified()` `:531-587` 的查表模式，**含該方法既有的 `@Transactional(readOnly = true)` 註解**——本方法新增讀取 `s.getStocks()`／`s.getFunds()` 兩個 lazy collection，雖然現行呼叫路徑皆在 request 執行緒內、專案未關閉 OSIV 預設值，理論上不會拋 `LazyInitializationException`，但既然宣稱「完全比照」就要真的比照，且可避免日後專案關閉 OSIV 時的隱性風險）：
  ```java
  @Transactional(readOnly = true)
  public CurrentAllocationDto getCurrentAllocation() {
      AssetSnapshot s = snapshotRepo.findLatest().orElse(null);
      if (s == null) {
          return CurrentAllocationDto.empty();
      }
      BigDecimal deposit = nz(s.getTotalDeposit());
      BigDecimal fund = nz(s.getTotalFundValue());
      BigDecimal stock = nz(s.getTotalStockValue());
      BigDecimal total = s.getTotalAssets() != null ? s.getTotalAssets() : deposit.add(fund).add(stock);

      // 一次查表建 Map（比照 AssetService.getHoldingsClassified 既有模式，不逐筆查 DB）
      Map<String, String> stockClassOverride = new HashMap<>();
      Map<String, String> stockStyleOverride = new HashMap<>();
      Map<String, String> bondTermOverride = new HashMap<>();
      Map<String, String> stockNameMap = new HashMap<>();
      for (Stock sm : stockMasterRepo.findAll()) {
          String key = sm.getMarket() + "|" + sm.getCode();
          stockNameMap.put(key, sm.getName());
          if (sm.getAssetClass() != null && !sm.getAssetClass().isBlank()) stockClassOverride.put(key, sm.getAssetClass());
          if (sm.getStockStyle() != null && !sm.getStockStyle().isBlank()) stockStyleOverride.put(key, sm.getStockStyle());
          if (sm.getBondTerm() != null && !sm.getBondTerm().isBlank()) bondTermOverride.put(key, sm.getBondTerm());
      }
      Map<String, FundClassOverride> fundOverride = new HashMap<>();
      for (FundClassOverride fo : fundClassOverrideRepo.findAll()) {
          fundOverride.put(fo.getFundName(), fo);
      }
      BigDecimal incomeThreshold = stockStyleRepo.findByCode(AssetClassifier.INCOME)
              .map(StockStyle::getDividendThreshold).orElse(null);

      // 股票桶：逐筆分類累加
      Map<String, BigDecimal> stockSub = newSubMap();
      for (StockHolding st : s.getStocks()) {
          BigDecimal val = st.getCurrentValue() != null ? st.getCurrentValue() : BigDecimal.ZERO;
          String key = st.getMarket() + "|" + st.getStockCode();
          String cls = assetClassifier.classifyStock(st.getStockCode(), st.getMarket(), stockClassOverride.get(key));
          if (AssetClassifier.BOND.equals(cls)) {
              String term = assetClassifier.classifyBondTerm(st.getStockCode(), st.getMarket(),
                      stockNameMap.get(key), bondTermOverride.get(key));
              addToSub(stockSub, term, val);
          } else {
              String style = assetClassifier.classifyStockStyle(st.getStockCode(), st.getMarket(),
                      stockStyleOverride.get(key), st.getDividendRate(), incomeThreshold);
              addToSub(stockSub, style, val);
          }
      }
      // 信託基金桶：逐筆分類累加
      Map<String, BigDecimal> fundSub = newSubMap();
      for (FundHolding fh : s.getFunds()) {
          BigDecimal val = fh.getCurrentValue() != null ? fh.getCurrentValue() : BigDecimal.ZERO;
          String fname = fh.getFundName();
          FundClassOverride ov = fundOverride.get(fname);
          String cls = assetClassifier.classifyFund(fname, ov != null ? ov.getAssetClass() : null);
          if (AssetClassifier.BOND.equals(cls)) {
              String term = assetClassifier.classifyBondTerm(null, null, fname, ov != null ? ov.getBondTerm() : null);
              addToSub(fundSub, term, val);
          } else {
              String style = assetClassifier.classifyStockStyle(null, null,
                      ov != null ? ov.getStockStyle() : null, null, incomeThreshold);
              addToSub(fundSub, style, val);
          }
      }

      List<CurrentAllocationDto.Item> items = new ArrayList<>();
      items.add(new CurrentAllocationDto.Item("存款（現金）", deposit, pct(deposit, total), List.of()));
      items.add(new CurrentAllocationDto.Item("信託基金", fund, pct(fund, total), toSubItems(fundSub, fund)));
      items.add(new CurrentAllocationDto.Item("股票", stock, pct(stock, total), toSubItems(stockSub, stock)));
      return new CurrentAllocationDto(s.getId(), s.getSnapshotDate(), total, items);
  }
  ```
  新增私有 helper：**5 個子類別字面字串常數只在 `LocalPortfolioAllocationEngine` 宣告一次（見 341.4 的 `SUBCLASS_GROWTH` 等 `public static final`），`PortfolioAdviceService` 一律引用 `LocalPortfolioAllocationEngine.SUBCLASS_GROWTH` 等，不得在本類另宣告一份同字面值的常數**——`PortfolioAdviceService` 已注入 `LocalPortfolioAllocationEngine localEngine`（既有欄位），直接引用即可，不構成新依賴、不影響既有 Spring wiring（`LocalPortfolioAllocationEngine` 不依賴 `PortfolioAdviceService`，無循環注入風險）：
  ```java
  private static Map<String, BigDecimal> newSubMap() {
      Map<String, BigDecimal> m = new LinkedHashMap<>();
      m.put(LocalPortfolioAllocationEngine.SUBCLASS_GROWTH, BigDecimal.ZERO);
      m.put(LocalPortfolioAllocationEngine.SUBCLASS_INCOME, BigDecimal.ZERO);
      m.put(LocalPortfolioAllocationEngine.SUBCLASS_BOND_SHORT, BigDecimal.ZERO);
      m.put(LocalPortfolioAllocationEngine.SUBCLASS_BOND_MID, BigDecimal.ZERO);
      m.put(LocalPortfolioAllocationEngine.SUBCLASS_BOND_LONG, BigDecimal.ZERO);
      return m;
  }

  private static void addToSub(Map<String, BigDecimal> m, String classifierCode, BigDecimal val) {
      String label = switch (classifierCode) {
          case AssetClassifier.GROWTH -> LocalPortfolioAllocationEngine.SUBCLASS_GROWTH;
          case AssetClassifier.INCOME -> LocalPortfolioAllocationEngine.SUBCLASS_INCOME;
          case AssetClassifier.SHORT -> LocalPortfolioAllocationEngine.SUBCLASS_BOND_SHORT;
          case AssetClassifier.MID -> LocalPortfolioAllocationEngine.SUBCLASS_BOND_MID;
          case AssetClassifier.LONG -> LocalPortfolioAllocationEngine.SUBCLASS_BOND_LONG;
          default -> LocalPortfolioAllocationEngine.SUBCLASS_GROWTH; // 理論不可達，classifyStockStyle/classifyBondTerm 值域固定
      };
      m.merge(label, val, BigDecimal::add);
  }

  private static List<CurrentAllocationDto.SubItem> toSubItems(Map<String, BigDecimal> sub, BigDecimal bucketTotal) {
      List<CurrentAllocationDto.SubItem> result = new ArrayList<>();
      for (Map.Entry<String, BigDecimal> e : sub.entrySet()) {
          if (e.getValue().signum() > 0) {
              result.add(new CurrentAllocationDto.SubItem(e.getKey(), e.getValue(), pct(e.getValue(), bucketTotal)));
          }
      }
      return result;
  }
  ```
  `pct(value, total)` 沿用既有私有方法（`PortfolioAdviceService` 內已存在，簽名 `private BigDecimal pct(BigDecimal value, BigDecimal total)`，`total<=0` 回 0，四捨五入 1 位小數）；本任務對 `Item.pct` 呼叫不變，對 `SubItem.pct` 改傳該桶金額（`bucketTotal`）而非資產總額 `total`。**不得新增第二次快照查詢**——`s.getStocks()`／`s.getFunds()` 沿用同一個 `s`（`AssetSnapshot` 既有 `@OneToMany(mappedBy="snapshot")`，同一 transaction 內存取不觸發額外查詢）。

- [x] 341.3 `CurrentAllocationDto`（`backend/src/main/java/com/steven/assets/dto/CurrentAllocationDto.java`）改為：
  ```java
  public record CurrentAllocationDto(
          Long snapshotId, LocalDate snapshotDate, BigDecimal totalAssets, List<Item> items
  ) {
      public record Item(String assetClass, BigDecimal value, BigDecimal pct, List<SubItem> subItems) {}
      public record SubItem(String subClass, BigDecimal value, BigDecimal pct) {}
      public static CurrentAllocationDto empty() { return new CurrentAllocationDto(null, null, null, List.of()); }
  }
  ```
  `Item` record 新增第四個欄位 `subItems`（既有呼叫端 `new CurrentAllocationDto.Item(...)` 全數改為四參數，見 341.2）。「存款（現金）」的 `subItems` 一律 `List.of()`。

### Backend：次分配對照表與目標子配置

- [x] 341.4 `LocalPortfolioAllocationEngine`（`backend/src/main/java/com/steven/assets/service/LocalPortfolioAllocationEngine.java`）新增子類別具名常數（與 `CLASS_CASH`／`CLASS_FUND`／`CLASS_STOCK` 同一風格，緊接其後）：
  ```java
  public static final String SUBCLASS_GROWTH = "成長型";
  public static final String SUBCLASS_INCOME = "收益型（高股息）";
  public static final String SUBCLASS_BOND_SHORT = "短期債";
  public static final String SUBCLASS_BOND_MID = "中期債";
  public static final String SUBCLASS_BOND_LONG = "長期債";
  ```
  （這是本任務唯一宣告這 5 個子類別字面字串的地方——341.2 的 `PortfolioAdviceService` helper 一律引用 `LocalPortfolioAllocationEngine.SUBCLASS_GROWTH` 等，不得另宣告一份同字面值的常數。`withSubAllocationAmounts`（341.9）要用這幾個字串去 `CurrentAllocationDto.SubItem.subClass()` 找對應金額，單一宣告來源才能保證兩邊逐字一致、不會打字不一致。）

- [x] 341.5 新增 `SubTemplate` record 與 `SUB_TEMPLATES`：
  ```java
  public record SubTemplate(
      BigDecimal stockGrowthPct, BigDecimal stockIncomePct,
      BigDecimal stockBondShortPct, BigDecimal stockBondMidPct, BigDecimal stockBondLongPct,
      BigDecimal fundGrowthPct, BigDecimal fundIncomePct,
      BigDecimal fundBondShortPct, BigDecimal fundBondMidPct, BigDecimal fundBondLongPct) {}

  private static final Map<TemplateKey, SubTemplate> SUB_TEMPLATES = buildSubTemplates();

  private static Map<TemplateKey, SubTemplate> buildSubTemplates() {
      Map<TemplateKey, SubTemplate> m = new LinkedHashMap<>();
      putSub(m, RISK_AGGRESSIVE, Horizon.LONG,      85, 15,  0, 0, 0,   55, 15, 10, 10, 10);
      putSub(m, RISK_AGGRESSIVE, Horizon.MEDIUM,    80, 20,  0, 0, 0,   45, 20, 10, 15, 10);
      putSub(m, RISK_AGGRESSIVE, Horizon.SHORT,     70, 30,  0, 0, 0,   35, 25, 15, 15, 10);
      putSub(m, RISK_AGGRESSIVE, Horizon.IMMINENT,  60, 40,  0, 0, 0,   25, 30, 25, 15, 5);
      putSub(m, RISK_BALANCED,   Horizon.LONG,      75, 25,  0, 0, 0,   45, 20, 10, 15, 10);
      putSub(m, RISK_BALANCED,   Horizon.MEDIUM,    65, 35,  0, 0, 0,   35, 25, 15, 15, 10);
      putSub(m, RISK_BALANCED,   Horizon.SHORT,     55, 45,  0, 0, 0,   25, 30, 20, 15, 10);
      putSub(m, RISK_BALANCED,   Horizon.IMMINENT,  45, 55,  0, 0, 0,   15, 30, 35, 15, 5);
      putSub(m, RISK_CONSERVATIVE, Horizon.LONG,    60, 40,  0, 0, 0,   35, 25, 15, 15, 10);
      putSub(m, RISK_CONSERVATIVE, Horizon.MEDIUM,  50, 50,  0, 0, 0,   25, 30, 20, 15, 10);
      putSub(m, RISK_CONSERVATIVE, Horizon.SHORT,   40, 60,  0, 0, 0,   20, 30, 25, 20, 5);
      putSub(m, RISK_CONSERVATIVE, Horizon.IMMINENT,30, 70,  0, 0, 0,   10, 30, 40, 15, 5);
      return Map.copyOf(m);
  }

  private static void putSub(Map<TemplateKey, SubTemplate> m, String risk, Horizon horizon,
                              int sGrowth, int sIncome, int sShort, int sMid, int sLong,
                              int fGrowth, int fIncome, int fShort, int fMid, int fLong) {
      if (sGrowth + sIncome + sShort + sMid + sLong != 100) {
          throw new IllegalStateException("股票次分配比例加總須為 100：" + risk + "/" + horizon);
      }
      if (fGrowth + fIncome + fShort + fMid + fLong != 100) {
          throw new IllegalStateException("基金次分配比例加總須為 100：" + risk + "/" + horizon);
      }
      m.put(new TemplateKey(risk, horizon), new SubTemplate(
          BigDecimal.valueOf(sGrowth), BigDecimal.valueOf(sIncome),
          BigDecimal.valueOf(sShort), BigDecimal.valueOf(sMid), BigDecimal.valueOf(sLong),
          BigDecimal.valueOf(fGrowth), BigDecimal.valueOf(fIncome),
          BigDecimal.valueOf(fShort), BigDecimal.valueOf(fMid), BigDecimal.valueOf(fLong)));
  }
  ```
  **設計原則（須保留於程式碼註解）**：債券曝險一律經由信託基金達成，股票桶目標次分配固定只在成長/收益二者分配（`stockBondShort/Mid/LongPct` 全部為 0）；若使用者股票帳戶仍持有債券型標的（如直接持有 `00679B`），現況子分類（341.2）仍如實顯示非 0 金額，目標給 0，形成建議減碼的落差（見 341.8 的提醒文案）。方向性：距退休年數縮短或風險承受度降低時，成長比重下降、收益比重上升；基金債券期別隨距退休年數縮短由長轉短（降低利率存續期風險）。

- [x] 341.6 `PortfolioAdviceResult`（`backend/src/main/java/com/steven/assets/dto/PortfolioAdviceResult.java`）的 `TargetAllocation` record 新增第七個欄位 `subAllocations`，並新增 `SubAllocation` nested record：
  ```java
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record TargetAllocation(
          String assetClass, BigDecimal targetPct, BigDecimal currentValue,
          BigDecimal targetAmount, BigDecimal deltaAmount, String rationale,
          List<SubAllocation> subAllocations
  ) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record SubAllocation(
          String subClass, BigDecimal targetPct, BigDecimal currentValue,
          BigDecimal targetAmount, BigDecimal deltaAmount, String rationale
  ) {}
  ```
  既有所有建構 `new PortfolioAdviceResult.TargetAllocation(...)` 呼叫點都要能相容七參數，**共有三處，缺一處會編譯失敗**：
  1. `LocalPortfolioAllocationEngine.allocation()` 私有方法（`evaluate()` 內組裝，見 341.7）。
  2. `llm` 檔位解析 Claude JSON 回應時 Jackson 自動映射——JSON 缺 `subAllocations` 欄位時，record 的 canonical constructor 對缺席欄位會收到 `null`（Jackson 對 record 的預設行為：JSON 沒有的欄位傳 `null` 給對應建構參數，除非該型別是 primitive；本欄位型別為 `List<SubAllocation>` 非 primitive，故安全），`llm` 檔位反序列化出的 `TargetAllocation.subAllocations()` 為 `null`，符合 Requirement 82 對 `llm` 檔位的既定行為（不拋錯、欄位為 null）。
  3. **`PortfolioAdviceService.enrich(PortfolioAdviceResult r, BigDecimal totalAssets)`（`:1290` 一帶，三檔位共用的金額回填方法，class 頂端 javadoc 明載「三檔共用同一支 `enrich(...)` 做金額算術，不得為本機檔位另寫一份」）——此方法內部同樣手動 `new PortfolioAdviceResult.TargetAllocation(t.assetClass(), t.targetPct(), t.currentValue(), targetAmount, delta, t.rationale())` 重建整個 `targetAllocation` 清單（目前 6 個位置參數），**這是三個引擎（`local`／`hybrid`／`llm`）都會經過的必經路徑**，比前兩點更容易漏改。改為 7 參數，第 7 個原樣傳遞 `t.subAllocations()`（`enrich()` 本身不計算子分配金額，只搬運既有值——子分配金額由 341.9 的 `withSubAllocationAmounts` 在 `enrich()` 之後才計算，此時 `t.subAllocations()` 對 `local`/`hybrid` 仍是 341.7 給的 `List.of()` 佔位、對 `llm` 是 Jackson 給的 `null`，兩者都原樣傳遞即可，不在 `enrich()` 內做任何子分配邏輯）。**

- [x] 341.7 `LocalPortfolioAllocationEngine.allocation()` 私有方法（現況只回六參數）改為固定回傳 `subAllocations = List.of()`（`evaluate()` 階段還不知道頂層 `targetAmount`，子分配的金額待 341.9 的 `withSubAllocationAmounts` 補上；`evaluate()` 階段可先把 `targetPct`／`rationale` 準備好或留給 341.9 一併處理，兩種寫法皆可，任選其一但須一致）。

- [x] 341.8 新增類別常數 `STOCK_BOND_HOLDING_WARNING` 與觸發邏輯（放在 `evaluate()` 內，`warnings` 組裝處）：
  ```java
  static final String STOCK_BOND_HOLDING_WARNING_TEMPLATE =
          "你的股票部位中有 %s 元被歸類為債券型標的（如債券 ETF），本模型的目標配置假設債券曝險一律經由信託基金達成，"
                  + "故此處目標次分配為 0、會顯示為建議減碼；若為刻意持有可忽略此提示。";
  ```
  在 `evaluate()` 組 `warnings` 時，取「股票」桶（`CLASS_STOCK`）對應的 `subItems`（來自 `currentAllocation`），加總 `SUBCLASS_BOND_SHORT`／`SUBCLASS_BOND_MID`／`SUBCLASS_BOND_LONG` 三者金額；若加總 > 0，`warnings.add(String.format(STOCK_BOND_HOLDING_WARNING_TEMPLATE, money(合計)))`（`money(...)` 沿用既有私有方法，千分位、四捨五入至元）。「信託基金」桶不觸發此提醒（信託基金本就允許債券部位，非例外情況）。

- [x] 341.9 新增 `withSubAllocationAmounts(PortfolioAdviceResult enriched, CurrentAllocationDto currentAllocation, String riskTolerance, Integer yearsToRetirement)` 純函式方法，比照既有 `withRebalancePlan(...)` 在 `PortfolioAdviceService.enrich(...)` 之後串接（見 341.10 呼叫順序）：
  - 依 `riskTolerance`／`yearsToRetirement` 正規化取 `SubTemplate`（沿用既有 `normalizeRisk`／`horizonOf`）。
  - 對 `enriched.targetAllocation()` 逐筆：`assetClass` 為 `CLASS_STOCK` 時，用 `SubTemplate` 的 `stock*Pct` 五欄與該筆的 `targetAmount()`（頂層已由 `enrich()` 回填）算五個 `SubAllocation`：`targetAmount = 頂層targetAmount × subPct / 100`（`BigDecimal` 乘除，`RoundingMode.HALF_UP`，比照既有 `pctOf`／`money` 精度慣例）、`currentValue` 從 `currentAllocation` 對應「股票」`Item.subItems()` 依 `subClass` 名稱比對取得（找不到視為 0）、`deltaAmount = targetAmount − currentValue`、`rationale` 比照既有 `rebalanceRationale` 風格組一句話。**目標比例為 0 的子類別（股票的三個債券期別）仍須輸出一筆 `SubAllocation`（`targetPct=0`），不得省略**——否則使用者持有債券型股票時，落差呈現會缺一列，看不到「目標=0、現況=X」的對比。
  - `assetClass` 為 `CLASS_FUND` 時，同理用 `fund*Pct` 五欄。
  - `assetClass` 為 `CLASS_CASH` 時，`subAllocations` 固定 `List.of()`。
  - 回傳新的 `PortfolioAdviceResult`（`targetAllocation` 欄位替換為补上 `subAllocations` 後的新 list，其餘欄位原樣複製，比照 `withRebalancePlan` 的既有寫法）。

- [x] 341.10 `PortfolioAdviceService` 呼叫順序（`generate(...)`／`runGeneration(...)` 內 `local`／`hybrid` 分支，現況為 `evaluate() → enrich(result, totalAssets) → withRebalancePlan(enriched)`）改為：
  ```
  evaluate() → enrich(result, totalAssets) → withRebalancePlan(enriched) → withSubAllocationAmounts(enriched2, currentAllocation, riskTolerance, yearsToRetirement)
  ```
  `withRebalancePlan` 與 `withSubAllocationAmounts` 彼此獨立（各自只讀寫自己負責的欄位），呼叫順序可互換，但兩者都必須在 `enrich(...)` 之後（依賴頂層 `targetAmount`）。`llm` 檔位的 `runGeneration(...)` **完全不呼叫**這兩支新方法——維持 Requirement 80 既有的「一行不改」保證，`llm` 檔位輸出的 `TargetAllocation.subAllocations` 全部為 `null`（Jackson 反序列化缺欄位的自然結果，見 341.6）。

### Backend：rebalancePlan 不擴充（明確排除項）

- [x] 341.11 確認 `withRebalancePlan(...)`（既有方法）**不修改**——不新增子類別層級的 `Rebalance` 物件。子類別的落差呈現只透過 `SubAllocation.deltaAmount`（341.9），不產生對應 BUY/SELL 動作。

### Backend：既有測試檔的手動建構呼叫點必須同步更新，否則整個 backend 測試模組編譯中止

`TargetAllocation`／`Item` 兩個 record 新增欄位、`PortfolioAdviceService` 新增 4 個 `final` 欄位（341.1）後，以下三處**既有測試檔的手動建構呼叫**若不同步更新會編譯失敗——Java 模組編譯是整批進行的，任一處失敗，`mvn test` 會在 test-compile 階段整個中止，不會跑到任何一個測試（不只本任務新增的測試，整個 backend 測試模組都跑不了）：

- [x] 341.15 `backend/src/test/java/com/steven/assets/service/PortfolioAdviceServiceEngineTest.java:121-123` 的 `newService(...)` helper（被同檔案所有測試方法共用，`grep -c "@Test"` 現為 19）：
  ```java
  private PortfolioAdviceService newService(LocalPortfolioAllocationEngine engine, String apiKey) {
      PortfolioAdviceService svc = new PortfolioAdviceService(
              profileRepo, expenseRepo, adviceRepo, settingRepo, snapshotRepo,
              depositRepo, fundRepo, stockRepo, projectionService, objectMapper, tenantGuard, engine);
      ...
  }
  ```
  `PortfolioAdviceService` 用 `@RequiredArgsConstructor`，建構子參數順序＝欄位宣告順序（`PortfolioAdviceService.java:186-198`：`profileRepo`／`expenseRepo`／`adviceRepo`／`settingRepo`／`snapshotRepo`／`depositRepo`／`fundRepo`／`stockRepo`／`projectionService`／`objectMapper`／`tenantGuard`／**此處插入 341.1 新增的 4 個欄位**／`localEngine`）。341.1 新增的 `assetClassifier`／`stockMasterRepo`／`fundClassOverrideRepo`／`stockStyleRepo` 須加在 `tenantGuard` 之後、`localEngine`（對應此 helper 的 `engine` 參數）之前。**該測試檔不是 `@Mock` 註解風格**（全檔無 `@ExtendWith(MockitoExtension.class)`、無任何 `@Mock` 註解）——實際既有慣例是「純欄位宣告 ＋ `setUp()`（`:86-97`）內手動 `mock(Xxx.class)` 賦值」，例如既有 `tenantGuard = mock(TenantGuard.class);`（`:97`）。故新增 4 個欄位須比照此既有慣例：宣告 `private AssetClassifier assetClassifier;`／`private StockRepository stockMasterRepo;`／`private FundClassOverrideRepository fundClassOverrideRepo;`／`private StockStyleRepository stockStyleRepo;`，並在 `setUp()` 內對應補上 `assetClassifier = mock(AssetClassifier.class);` 等 4 行賦值（測試中不需特別 stub 行為的方法呼叫，mock 預設回傳 null／空集合即可），再於 `newService(...)` 呼叫的對應位置插入這 4 個欄位。需新增 import：`com.steven.assets.repository.StockRepository`、`com.steven.assets.repository.FundClassOverrideRepository`、`com.steven.assets.repository.StockStyleRepository`（`AssetClassifier` 與本測試檔同屬 `com.steven.assets.service` package，不需 import）。
- [x] 341.16 `backend/src/test/java/com/steven/assets/service/LocalPortfolioAllocationEngineTest.java:275` 的 `alloc(...)` helper（6 參數建構 `TargetAllocation`）補第 7 個參數，測試情境不特別驗證子分配內容時可傳 `List.of()`。
- [x] 341.17 `backend/src/test/java/com/steven/assets/service/LocalPortfolioAllocationEngineTest.java:285-289` 的 `currentAllocation(...)` helper（`new CurrentAllocationDto.Item(...)` 三處呼叫，3 參數）各補第 4 個參數 `List.of()`（該測試資料本身不含逐筆持股，用空清單即可，不影響既有斷言）。

### 前端

- [x] 341.12 `AssetAllocationAdviceView.vue` 的「② 我目前的資產配置」區塊（現況 `:212-232`，`allocationItems` computed 於 `:522`）：`it.subItems`（來自 `CurrentAllocationDto.Item.subItems`）非空時，在該 `.alloc-row` 底下渲染子列——沿用既有 `.alloc-row`／`.bar-wrap`／`.bar.cur` class，新增 `.alloc-row.sub`（CSS：`.alloc-name` 寬度縮減如 `120px`、字級調小如 `13px`、整列 `padding-left` 縮排如 `24px`，`.bar-wrap` 高度可略降如 `14px`）。子列僅渲染 `it.subItems` 中的項目（`getCurrentAllocation()` 已只回傳金額 > 0 的子類別，前端不必再過濾）。父列（股票／信託基金）點擊可展開/收合（本地 `ref` 布林陣列或以 `assetClass` 為 key 的 `reactive` 物件即可，不引入 `el-collapse` 或其他新元件庫）；「存款（現金）」`subItems` 恆空，不顯示展開箭頭／不可點擊。
- [x] 341.13 「建議目標配置」區塊（現況 `:309-326`）：`t.subAllocations`（來自 `PortfolioAdviceResult.TargetAllocation.subAllocations`）非空時同樣渲染子列（沿用 `.bar.tgt` class）；`t.subAllocations` 為 `null` 或空陣列時（`llm` 檔位、或本次建議早於本任務落地）不渲染子列、父列行為與現況完全一致（不報錯、不顯示空區塊）。子列同時顯示 `targetPct`／`currentValue`／`targetAmount`／`deltaAmount`（比照父列既有 `alloc-amounts`／`delta` 呈現方式），`targetPct=0` 且 `currentValue` 亦為 0（或未出現於現況 `subItems`）的子列**不渲染**（避免全部五類都印出來、多數是 0 造成雜訊；但 `targetPct=0` 而 `currentValue>0` 的子列須渲染，用來呈現「建議減碼」的落差，呼應 341.8 的提醒）。
- [x] 341.14 前端不新增 API 呼叫——`GET /api/bff/portfolio-advice`（既有聚合端點）回傳的 `currentAllocation.items[].subItems` 與 `latest.targetAllocation[].subAllocations` 已包含子類別資料，本任務不新增／不修改任何 BFF 或 business API 路徑。

## 驗證

**單元測試（純函式，不啟 Spring context）**：
- [ ] `LocalPortfolioAllocationEngineTest`（或既有測試類）新增：`SUB_TEMPLATES` 12 格皆通過建表期 assert（`stockGrowthPct+stockIncomePct+stockBondShortPct+stockBondMidPct+stockBondLongPct == 100`、`fund*` 五欄同理），可用迴圈跑滿 12 組 `TemplateKey` 逐一斷言。
- [ ] `withSubAllocationAmounts(...)`：給定固定 `enriched`（含頂層 `targetAmount`）與 `currentAllocation`，斷言子分配 `targetAmount` 之和（同一頂層桶內）等於頂層 `targetAmount`（允許 `BigDecimal` 尾差 ≤ 1 元，四捨五入誤差）；`deltaAmount = targetAmount − currentValue` 逐筆核對。
- [ ] `STOCK_BOND_HOLDING_WARNING`：股票桶 `subItems` 含非 0 的短/中/長期債金額時觸發、金額加總正確；不含時不觸發；信託基金桶同樣情形不觸發此則提醒。

**Service 層測試（含 DB，`@DataJpaTest` 或既有整合測試風格）**：
- [ ] `getCurrentAllocation()` 對同一份測試快照的子分類結果，與 `AssetService.getHoldingsClassified(sameSnapshotId)` 用同一組個股/基金 override 資料時分類結果一致（同一組 `AssetClassifier` 呼叫，不得出現兩套分類邏輯分岔）。
- [ ] 子類別金額加總 == 頂層桶金額：對含多筆股票與基金持股的測試快照，分別驗證「股票」與「信託基金」兩桶。
- [ ] 「存款（現金）」`subItems` 恆為空陣列。
- [ ] `llm` 檔位既有測試全數通過不回歸（`subAllocations` 為 `null`，反序列化不拋錯）。

**建置與實機驗證**：
```bash
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test -Dtest=PortfolioAdviceServiceEngineTest,LocalPortfolioAllocationEngineTest
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
cd /Users/steven/Project/asset-management && docker compose -p asset-management build --no-cache business-services frontend
docker compose -p asset-management up -d --no-deps --force-recreate business-services frontend
curl -s http://localhost:8080/actuator/health
# 實機以 X-User-* header 模擬租戶驗證（免走 Google 登入）
curl -s -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" \
  http://localhost:8080/api/portfolio-advice/current-allocation | python3 -m json.tool
curl -s -X POST -H "X-User-Id: 1" -H "X-User-Role: ADMIN" -H "X-User-Status: ACTIVE" \
  -H "Content-Type: application/json" -d '{"riskTolerance":"BALANCED"}' \
  http://localhost:8080/api/portfolio-advice/generate | python3 -m json.tool
```
確認 `current-allocation` 回應的「股票」「信託基金」兩個 `Item` 皆含非空 `subItems`（若測試環境有對應持股）；`generate` 在預設 `engine=local`（Task 339 既有預設）時同步回終態，`targetAllocation` 中「股票」「信託基金」兩筆含 `subAllocations`（五筆，含 `targetPct=0` 的列）。前端以 `.claude/skills/run-stack` 開頁面截圖確認「② 我目前的資產配置」與「建議目標配置」皆可展開子類別列。

## 完成報告

**後端部分（341.1～341.11、341.15～341.17）已完整實作，完全依任務檔逐項落實，未偏離原計畫。**

### 修改的檔案

- `backend/src/main/java/com/steven/assets/dto/CurrentAllocationDto.java`——`Item` 新增第四欄
  `subItems`，新增 `SubItem` record。
- `backend/src/main/java/com/steven/assets/dto/PortfolioAdviceResult.java`——`TargetAllocation`
  新增第七欄 `subAllocations`，新增 `SubAllocation` record。
- `backend/src/main/java/com/steven/assets/service/LocalPortfolioAllocationEngine.java`——新增
  `SUBCLASS_GROWTH`／`SUBCLASS_INCOME`／`SUBCLASS_BOND_SHORT`／`SUBCLASS_BOND_MID`／`SUBCLASS_BOND_LONG`
  五個常數（唯一宣告處）、`STOCK_BOND_HOLDING_WARNING_TEMPLATE`、`SubTemplate` record 與
  `SUB_TEMPLATES`／`buildSubTemplates`／`putSub`、`subTemplateOf(...)`（新增的公開存取器，供
  `PortfolioAdviceService.withSubAllocationAmounts` 呼叫鏈與測試使用）、`withSubAllocationAmounts(...)`
  （連同 `buildSubAllocations`／`subAllocation`／`currentSubValuesOf`／`stockBondHoldingAmount` 私有
  helper）、`allocation()` 補上第七參數 `List.of()`、`evaluate()` 內加上
  `STOCK_BOND_HOLDING_WARNING_TEMPLATE` 觸發邏輯。
- `backend/src/main/java/com/steven/assets/service/PortfolioAdviceService.java`——新增 4 個欄位
  `assetClassifier`／`stockMasterRepo`／`fundClassOverrideRepo`／`stockStyleRepo`（插入
  `tenantGuard` 之後、`localEngine` 之前）；`getCurrentAllocation()` 改為含子分類查表邏輯並加上
  `@Transactional(readOnly = true)`；新增 `newSubMap`／`addToSub`／`toSubItems` 私有 helper；
  `enrich()` 第七參數改傳 `t.subAllocations()`；`buildLocalResult()` 串接
  `withSubAllocationAmounts` 於 `withRebalancePlan` 之後。
- `backend/src/test/java/com/steven/assets/service/PortfolioAdviceServiceEngineTest.java`——
  `newService(...)` helper 補 4 個新欄位（`setUp()` 內以 `mock(Xxx.class)` 賦值，沿用該檔既有慣例，
  非 `@Mock` 註解）；既有測試 `amounts_comeFromTheExistingEnrichArithmetic` 因新增
  `withSubAllocationAmounts` 管線步驟而更新比對邏輯（改為走完整鏈 `enrich → withRebalancePlan →
  withSubAllocationAmounts` 再比對，維持「共用同一段算術、無第二份實作」的驗證意圖）。
- `backend/src/test/java/com/steven/assets/service/LocalPortfolioAllocationEngineTest.java`——
  `alloc(...)` 補第七參數 `List.of()`；`currentAllocation(...)` 三處 `Item` 建構補第四參數
  `List.of()`；新增 7 個測試方法（見下）與對應測試資料 helper
  （`currentAllocationWithSubItems`／`currentAllocationWithStockBondHolding`／`findByClass`／
  `findBySubClass`）。
- `backend/src/test/java/com/steven/assets/service/PortfolioAdviceServiceCurrentAllocationTest.java`
  （新檔）——`getCurrentAllocation()` 的 Service 層測試，使用真實 `AssetClassifier`（非 mock）驗證與
  `AssetService.getHoldingsClassified()` 分類結果一致。

### 新增的測試方法

**`LocalPortfolioAllocationEngineTest`**（純函式，7 個新測試）：
- `subTemplate_everyOfTwelveCellsSumsTo100ForBothStockAndFund`
- `subTemplate_stockBondPctsAreAlwaysZero`
- `withSubAllocationAmounts_producesFiveSubItemsPerBucketWithCorrectAmounts`
- `evaluate_triggersStockBondHoldingWarningWhenStockBucketHoldsBondSubclasses`
- `evaluate_doesNotTriggerStockBondHoldingWarningWhenNoBondSubclassInStockBucket`
- `evaluate_fundBucketBondHoldingsDoNotTriggerTheStockOnlyWarning`

**`PortfolioAdviceServiceCurrentAllocationTest`**（新檔，Service 層、Mockito 風格）：
- `getCurrentAllocation_subclassificationMatchesGetHoldingsClassified`（與
  `AssetService.getHoldingsClassified()` 逐筆彙總比對，同一組 override 資料）
- `getCurrentAllocation_subclassAmountsSumToBucketTotal`
- `getCurrentAllocation_depositSubItemsAlwaysEmpty`
- `getCurrentAllocation_onlyIncludesSubclassesWithPositiveAmount`

### 測試結果

```
mvn -f backend/pom.xml test -Dtest=PortfolioAdviceServiceEngineTest,LocalPortfolioAllocationEngineTest
  → Tests run: 35, Failures: 0, Errors: 0（其中 PortfolioAdviceServiceEngineTest 19 個既有測試全數通過）

mvn -f backend/pom.xml test -Dtest=PortfolioAdviceServiceCurrentAllocationTest
  → Tests run: 4, Failures: 0, Errors: 0

mvn -f backend/pom.xml test（全量）
  → Tests run: 1113, Failures: 0, Errors: 0, Skipped: 0（含既有測試不回歸）
```

### 與原計畫的偏差

1. **新增 `LocalPortfolioAllocationEngine.subTemplateOf(...)` 公開存取器**——任務檔未明文列出，
   但 `SUB_TEMPLATES` 為 private，`withSubAllocationAmounts` 測試需要一個對外入口驗證 12 格模板，
   比照既有 `templateOf(...)` 的風格新增，屬最小必要擴充，不影響任何既有契約。
2. **`amounts_comeFromTheExistingEnrichArithmetic`（既有測試）需要修改**——任務檔的 341.15～341.17
   只列出因「新增建構參數／欄位」而編譯失敗的三處，但此測試在 341.10 新增
   `withSubAllocationAmounts` 管線步驟後，因手動重放 `enrich()` 未包含後續步驟而斷言失敗（並非編譯
   失敗，是執行期斷言不符）。已改為重放完整鏈（`enrich → withRebalancePlan →
   withSubAllocationAmounts`）再比對，測試原意（金額算術與子分配管線皆為單一共用實作、無分岔）不變。
3. **`enrich()` 呼叫鏈的實際串接順序**——採用「先 `withRebalancePlan`、後
   `withSubAllocationAmounts`」（`buildLocalResult()`），任務檔 341.10 明載兩者順序可互換，故此順序
   在允許範圍內。

未執行 341.12～341.14（前端）、以及「建置與實機驗證」段落的 Docker build／`run-stack` 截圖驗證
（依指示留給彙整階段處理）。

### 前端（341.12～341.14，另一支 subagent 完成）

`frontend/src/views/AssetAllocationAdviceView.vue` 為唯一改動檔案。「② 我目前的資產配置」與「建議目標配置」兩區塊皆改為可展開：父列（股票／信託基金）新增展開箭頭與 `.alloc-block` 包裹，子列沿用既有 `.alloc-row`／`.bar-wrap`／`.bar.cur`／`.bar.tgt`，新增 `.alloc-row.sub`（縮排＋字級縮小）。展開狀態以純 `ref({})` 管理（`currentSubExpanded`／`targetSubExpanded`），未引入 `el-collapse` 或其他元件庫。`visibleSubAllocations(t)` 過濾規則：`targetPct≠0` 或 `currentValue>0` 才渲染（呼應 STOCK_BOND_HOLDING_WARNING 情境要顯示「目標=0、現況>0」的減碼落差列）。向後相容：`subItems`／`subAllocations` 為 `undefined`／`null`／空陣列時不顯示展開箭頭、父列行為與改動前一致。`vite build` 通過（`✓ built in 4.34s`，僅既有 chunk-size 警告，與本次改動無關）。

### 彙整階段（主 agent）

1. **驗收**：重新獨立跑過 `mvn -f backend/pom.xml test -DextraArgLine=-Dnet.bytebuddy.experimental=true`（`feedback_mockito_java25_bytebuddy` 慣例，`-DargLine` 會炸）→ `BUILD SUCCESS`，`Tests run: 1113, Failures: 0, Errors: 0`；`frontend` 端獨立重跑 `vite build` 亦通過。
2. **架構稽核**：派 `arch-auditor`（diff-scoped，餵完整 diff）查證 Clean Architecture 分層／資料庫正規化／禁止 Enum 寫死／BFF 規範／Requirement 80 既有保證／DI／可測試性／前端元件庫依賴共 8 個面向，**零 finding**，已跑 `bash .claude/hooks/arch-review-pass.sh` 記錄通過。
3. **run-stack 實機驗證**：本次變更尚未 merge 進 main，從本 worktree `docker compose -p asset-management build --no-cache business-services frontend` → `up -d --no-deps --force-recreate` → `restart bff`（recreate 上游服務後的既有慣例，避免 DNS 快取指向舊 IP）。兩容器皆轉 `healthy`，`docker logs asset-bff` 確認零殘留 `Connection refused`／`500` 錯誤。
4. **端到端驗證（容器內 `X-User-*` header，免走 Google 登入）**：
   - `GET /api/portfolio-advice/current-allocation`：對真實最新快照（20,346,849 元，與使用者原始截圖數字一致：存款 41.5%／信託基金 0.3%／股票 58.2%）驗證子分類正確算出——「股票」桶底下 成長型 67.6%／收益型（高股息）25.3%／短期債 4.1%／中期債 0.9%／長期債 2.1%（合計 100%，且加總＝頂層股票金額）；「信託基金」桶（金額小）僅長期債 100%；「存款（現金）」`subItems` 為空陣列。
   - `POST /api/portfolio-advice/generate`（`riskTolerance=BALANCED`，`engine=local` 預設）：`targetAllocation` 三筆皆含 `subAllocations`，金額算術正確（子分配 `targetAmount` 加總＝頂層 `targetAmount`，`deltaAmount = targetAmount − currentValue` 逐筆核對無誤），「股票」桶三個債券期別子類別 `targetPct=0` 但 `currentValue>0`（呈現使用者實際持有的債券型股票部位）。
   - `warnings` 陣列確認 `STOCK_BOND_HOLDING_WARNING` 正確觸發：「你的股票部位中有 839,741 元被歸類為債券型標的...」，金額＝480,128＋107,250＋252,363（股票桶短/中/長期債三者加總），核算無誤。
   - 前端視覺驗證因應用需 Google OAuth 登入、且不得代使用者輸入帳密，未完成瀏覽器截圖；已改以上述後端真實資料端到端驗證取代，前端邏輯另由 `vite build` 與程式碼審閱確認向後相容。使用者可自行登入頁面肉眼確認子類別展開效果。

**結論：spec 三輪對抗式審查通過、架構稽核零發現、後端 1113 測試全過、前端 build 通過、真實快照端到端驗證行為正確。尚未 commit——依專案規範等待使用者明確指示再進 commit/merge 流程。**
