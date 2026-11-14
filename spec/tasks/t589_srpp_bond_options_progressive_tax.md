# [t589] 補齊債券觀察選項與薪資年度累進稅

**對應 Requirements:** Requirement 208（SRPP設定、情境試算與正式來源隔離）
**前置任務:** t585、t586、t587
**Liquibase changeset:** 無

## 背景

使用者指出兩個已部署介面的問題：台股債券波段只能選正式策略原先三檔，漏掉現有台股債券ETF 00719B與00865B；所得稅的20%應理解為最高適用的邊際級距，不能將所有薪資乘20%冒充年度應納稅額。現有V2情境只有薪資現金預扣，正式D193已有累進稅，尚未接入此情境。

本任務在Requirement208既有使用者故事下修正上述兩項行為，並作為較新的限縮設計修訂：取代t587「債券可選範圍等於正式symbols三檔」的限制，以及t586「情境未含年度薪資結算」的限制；不改變其他草稿／正式、資產來源、月頻模型與帳號隔離契約。已確認共用需求與設計的既有分層適用；兩份共用文件正由另一工作區修改，本任務不與其同檔並寫，本檔完整列出本次設計與驗收。

## 要做什麼

### 債券選項

1. `shortBondSymbols.allowedSymbols`為00679B、00697B、00719B、00751B、00865B五檔台股債券ETF；目前正式model/defaultValue/currentValues仍維持原00679B、00751B、00697B三檔與順序，不能自動增選或改正式雷達。美股SGOV不混進台股欄位。catalog仍V2、共76欄；本欄仍editable=true、simulationSupported=false、formalSupported=false、retirementImpact=NOT_MODELED。
2. Python沿同版catalog白名單驗證，保留使用者順序、空陣列合法，拒絕重複、空字串、未知代號、0050、SGOV。前端沿原allowedSymbols renderer，不自行另建名單。
3. 業務服務新增內部同版catalog allowed-symbol accessor；只讀已核對manifest SHA的classpath catalog，immutable回傳，不呼叫worker、最新資產、vendor、日曆刷新。短線草稿以該白名單驗證，不能再以正式三檔為上限；無草稿的正式觀察清單仍用原三檔。
4. 新兩檔的逐檔明細入口允許同版catalog範圍，但仍只委派既有純讀radar.getStockEvaluation；原owner universe gate、錯誤404、缺資料WAIT、SHORT最終方向與無交易授權不變。不得改用有writer/refresh的getStockDetail。草稿多選不表示正式採用或通過全部交易閘門。

### 年度薪資所得稅

5. 新情境結果engineVersion=`SRPP_SCENARIO_CASHFLOW_V3`，舊V1/V2結果仍可讀，原canonical JSON/resultSha256不得改。使用同一seed與同一凍結輸入比較基準／情境，禁止AI計算稅額。
6. 將現有115年度純稅階常數與progressive_tax抽至無I/O的`resident_income_tax.py`，原`current_cash_income.py`保留原同名exports/函式契約並委派，正式D193完整股利／AMT稅輸出必須保持一致。Worker只匯出這個純模組與`scenario_tax.py`，不得匯入含urllib/抓取路徑的current_cash_income或monte_carlo。官方來源：https://www.mof.gov.tw/singlehtml/384fb3077bb349ea973e7fc6f13b6974?cntId=34b463dc8f1b49f29d440d92a6fd5139 。115年度（2026所得、2027申報）級距為610000／1380000／2770000／5190000元，率為5%／12%／20%／30%／40%。逐級應稅額=max(0,min(淨額,上界)-下界)；累加各級距稅額，不以20%封頂、不以整筆薪資乘最高稅率。規劃未公布年度須明示沿115年度代理，不能稱未來已公告法律。
7. 本次只計本人薪資與獎金的單身居民規劃稅，沿現有明示的單身/標準扣除額來源；不是完整全戶申報、投資所得／股利／AMT試算。年度薪資先扣依法不計入薪資所得的勞退自提，再減適用免稅額（未滿70歲101000、符合70歲條件151500）、單身標準扣除額136000、薪資所得特別扣除額min(薪資所得,227000)，所得淨額不得負。不能把健保眷屬數當成報稅扶養人數。來源model內已存在的免稅/標扣參數沿同版讀取，不增猜家戶或列舉資料。
8. 從完整曆年薪資帳彙總薪資、獎金、自提、已預扣；年中asOf前收入只納稅基，不重播入帳。退休年按完整來源帳及發薪日／獎金發放日處理，退休後實際支付的應收薪資仍歸所屬收付年度，不把全年收入錯算為asOf以後片段。只處理來源支持的2026年起年度；既往資料為薪資來源回推的規劃代理，不偽稱已核對實際扣繳憑單。
9. `withholdingRate`仍是薪資已預扣的現金預繳比例，來源D076值不改；欄名與說明改為「薪資預扣率（預繳，非最終稅率）」。它不是使用者設定的最高稅級。最高適用稅率與有效稅率由年度所得淨額自動算出；若所得跨入30%或40%，依法計算，不能鎖在20%。預扣率只影響預繳/補退與現金時點，不影響年度應納稅額。
10. 次年5月31日只入帳`預扣－應納`差額（正退、負補），並延續同版AnnualTaxBudget的含稅生活預算及退款留在資產語義，不把全部年度稅額再扣一次。asOf前已結算年度不重播；期末未到結算且有淨應補稅負債必須列終點扣款，不提前入帳未到期退款。測試須區分相同稅基的最終稅額不隨預扣率改變，和現金時點/非稅預算可能改變投資路徑。
11. Payroll新增可選`annualIncomeTax`，僅V3必有，舊V1/V2缺此欄仍維持原wire/hash（null時省略此新property）。固定graph為immutable records，money全部BigDecimal／固定兩位Decimal字串，rate保持number；backend typed→實際HTTP→BFF須維持精確原結果hash。物件：`scope=SALARY_ONLY_SINGLE_RESIDENT_PLANNING`、ruleYear=2026、sourceUrl、futureYearPolicy、historicalBasis、years、notes。year欄：taxYear、settlementDate、grossSalaryNominalTwd、selfPensionNominalTwd、taxableSalaryNominalTwd、personalExemptionNominalTwd、standardDeductionNominalTwd、salaryDeductionNominalTwd、taxableIncomeNominalTwd、marginalRate、effectiveRate、brackets、liabilityNominalTwd、withheldNominalTwd、settlementNominalTwd、treatment。bracket欄：lowerNominalTwd、upperNominalTwd（最高級距null）、rate、taxableSliceNominalTwd、taxNominalTwd；treatment閉合集為SETTLED_BEFORE_AS_OF／CASH_SETTLEMENT／TERMINAL_ACCRUAL。零稅基marginal/effective為0，最高適用級距按有正應稅切片的最高率呈現。
12. UI薪資明細中提供年度累進稅表及可展開各級距、扣除額、應納稅額、預扣與補退的結果，沿BFF已排序投影只render/格式化，不在Vue重算稅額。明示單身薪資規劃範圍及未來級距代理；舊結果缺明細時明示舊版無此明細，不補0。保留LOCAL/AI兩模式；LOCAL零AI，AI僅解釋相同不可變數值。

### 發布與邊界

13. 不改DB schema、正式薪資來源個人值、正式觀察預設、交易／寄信／producer／排程。Source mirrors/export/manifest/registry必同版；新source module自動納入原policy bundle，不放寬工具dirty-root限制。所有變更完成後依既有雙repo測試、獨立審查、Git no-ff merge/push、insert-only registry、正確main原.env重建/重建容器及精確身份回讀，合法日報時窗未執行記NOT_YET_EXECUTED。

- [ ] 589.1 補齊五檔選項及純讀草稿/明細邊界，原預設三檔不變。
- [ ] 589.2 共用純累進稅與V3年度薪資稅/補退/終點負債，原正式稅函式parity。
- [ ] 589.3 typed精度、舊結果相容、BFF投影與前端明細。
- [ ] 589.4 完成獨立審查、測試、受控來源發布、雙repoGit與部署驗收。

## 驗證

SRPP新增`test_scenario_income_tax.py`與擴增既有settings測試；需測各稅階上下界、零/低所得、20%案例明確小於全薪×20%、30/40%不封頂、免扣除與自提各一次、全年度含asOf前、跨年發薪/獎金、退休年、預扣抵減、五月/終點、預扣率不改final liability、原正式tax parity、純計算容器無vendor/import污染。債券須測五檔合法、原預設不變、非法/跨market拒絕、順序/空/重複、草稿新兩檔與owner/缺資料邊界。

```bash
PYTHONDONTWRITEBYTECODE=1 /tmp/srpp-settings-python/bin/python scripts/test_settings_cashflow_v2.py
PYTHONDONTWRITEBYTECODE=1 /tmp/srpp-settings-python/bin/python scripts/test_scenario_income_tax.py
PYTHONDONTWRITEBYTECODE=1 /tmp/srpp-settings-python/bin/python scripts/test_settings_simulation.py
PYTHONDONTWRITEBYTECODE=1 /tmp/srpp-settings-python/bin/python scripts/policy_bundle.py check --purpose daily --consumer Codex
PYTHONDONTWRITEBYTECODE=1 /tmp/srpp-settings-python/bin/python scripts/daily_runtime_contract.py check
```

Asset擴增既有SrppSettings/ShortTerm wire/service測試；新測試尚待本任務新增，不宣稱已存在。需真的通過typed wire精度與舊結果hash、MVC錯誤狀態、純讀委派與跨owner、UI新兩檔可選/可保存/預設未增選、年度稅表與各級距顯示。測試使用合成資產，不以私人完整資料作測試fixture；正式驗收不得呼叫可能refresh/vendor的LOCAL入口，須先具名查證安全界線。

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true '-Dtest=SrppSettings*Test,SrppShortTerm*Test' test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml '-Dtest=SrppSettings*Test,SrppShortTerm*Test' test
npm --prefix frontend run test
npm --prefix frontend run build
```

部署只重建受影響服務，使用main既有.env，私下比對effective config；JAR/worker/catalog同bundle、已有HTTPS憑證可信、BFF自然DNS恢復。UI操作與worker HTTP合成試算、正式owner草稿新增兩檔保存/回讀、短線純讀/新年度稅schema為必要驗收；Google登入E2E或真AI不可用須具名區分，不偽稱實際解讀成功。唯讀9090精確新hash診斷與正式時窗採用分開，不手動報告／寄信／交易。

## 完成報告

待完成後回填，並保存可覆核驗收紀錄。
