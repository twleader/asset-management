# [t534] SRPP D-205門檻與已知基金分類修復

**對應 Requirements:** Requirements 187／193，已驗證不可變SRPP計算包與正式政策身分發布。
**前置任務:** t530／t531
**Liquibase changeset:** 無

## 背景

2026-11-02實際凍結來源中42檔股票估值全部為TARGET_SESSION_PRICE，但24B2「施羅德環球收益債券非常避險 (月配)」缺DB分類override，導致三檔債券全部CAPACITY_VALUATION_UNVERIFIED。既有正式SRPP政策已把24B2列為債券，應以精確身分映射恢復已知事實。使用者同時接受VOO／VT買進與D-174回落的新門檻；不能借修復解除真正缺來源的分帳、掛單或券商擷取缺口。

## 要做什麼

- [ ] 534.1 從SRPP新model的頂層fund_asset_classifications讀取完整列：fund_code=24B2、fund_name=施羅德環球收益債券非常避險 (月配)、asset_class=BOND。authority驗model／manifest原文雜湊後才解析；重複、空代號／名稱或非BOND／STOCK分類拒絕啟動。
- [ ] 534.2 adapter優先採DB明確分類，否則僅fundCode與fundName雙精確match才採policy分類。凍結policy rows、DB rows、resolvedFundClassSources與未知missing，其他基金不可名稱猜測。所有既有資產L0及估值來源閘門保留。
- [ ] 534.3 decision／report共用純函式雷達封閉V1／V2相容檢查。核實代碼、市場、日期、LIVE、dataComplete及時效；V2官方均線CONFLICT或必要欄位缺漏不能放行，原事實保存與typed否決分開。
- [ ] 534.4 Java strategicGate對overheat_required_symbols=[VOO,VT]僅position>=0.90且hits>0才HIGH_ZONE_OVERHEAT_SIGNAL，低於0.90不因本閘禁止；009826／高股息維持>=0.90直接擋、0.80起hits擋；SGOV原平均除息價法不變。coreTiming讀新policy 0.015，不改高檔arming與KD。
- [ ] 534.5 同步D-205／2.54.0（模型3.22.0）完整model／manifest、原golden產生器輸出及受追蹤insert-only SQL。expected變動只接受新授權規則可解釋的差異。DB schema／Swagger不變；沒有券商委託、匯款、寄信或request-time外抓。
- [ ] 534.6 通過獨立spec／架構審查與測試後，完成feature commit／main no-ff merge／push／remote readback，再依正式main設定發布registry及business-services。新hash／registry／JAR須精確一致。正式report GET受台北09:05–13:30交易日時窗限制，越窗明列NOT_YET_EXECUTED。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=DecisionInputCaptureAdapterTest,DecisionPolicyAuthorityTest,SrppReportModuleCalculatorTest,SrppReportStateTopupCalculatorTest,SrppReportShortUsCalculatorTest test
python3 scripts/check-srpp-report-authority.py --srpp-root /Users/steven/.codex/worktrees/trading-evidence-repair/SRPP --check
```

覆蓋DB override優先、雙欄精確match、錯名／錯代號／未知基金不放行、V1／V2／CONFLICT／缺欄、VOO／VT位置0.90前後與四項過熱訊號、其他標的不變、D-174 1.5%前後同輸入parity。依既有run-stack做image rebuild／container recreate並檢查JAR authority。只有合法台北交易日時窗可read-only report-calculations；其餘純讀calendar／quotes／資產連通性與metadata不宣稱金融資格通過。

## 完成報告

- 已完成四支production與三支測試修正：精確基金policy mapping與不可變authority、DB override優先／同名異碼隔離、decision/report共用雷達封閉版本與V2否決、VOO／VT雙條件高點閘門。正式model為D-205／2.54.0／模型3.22.0；D-174仍由policy讀取1.5%，未改其他KD或arming條件。
- 獨立spec與架構diff審查皆0 confirmed findings；spec-check為BLOCK0／CHECK0。完整backend 3383 tests、0 failures／0 errors／2既有skipped。49項聚焦測試覆蓋精確基金與未知負例、雷達版本／官方否決、VOO／VT與原標的分支、1.5%前後邊界。
- 最終bundle為`3a9056d7b6bfa36eb1522f560f300fa48e00cdbfb1bbe7d94c86092f527fd80c`，model raw SHA為`5e2bf2d5c4f947f06d9d5b5729fc7a2f494b206c8d2f512e802201cec1124369`。model與manifest已逐位元對齊SRPP feature正本，authority checker PASS；SQL由原Ruby工具重產且insert-only。主22案例golden唯一金融expected變動為原2.99%回落由不通過改為通過，符合3%→1.5%授權，其餘expected不變；state22、short/US13、income/payroll25重產逐值原樣。Ruby registry5cases／261assertions與Python authority4tests均通過。
- 原始11/2 13:24:54凍結包證明所有42檔股票估值均TARGET_SESSION_PRICE，唯一基金code／name與新policy完全匹配，而DB override僅bondTerm=LONG、assetClass=null；新映射修復此資料缺口。國泰／富邦分帳、全券商真委託、未證策略歸屬與失敗券商來源仍不可用，沒有以空集合／推測解除。
- 最終Git landing、registry、JAR及普通9090 GET的實際發布收據由本輪release階段保存；本次實作驗收在台北13:30後，正式新hash report-calculations readback維持`NOT_YET_EXECUTED_AFTER_1330`，不以健康檢查或離線測試冒充完整金融採用。
