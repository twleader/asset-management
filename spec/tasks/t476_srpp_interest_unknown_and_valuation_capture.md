# [t476] SRPP 利率缺值與跨時點估值保守採用

**對應 Requirements:** Requirement 163（SRPP 不可變計算 package 的缺值品質與可重播性修訂）
**前置任務:** t452、t467
**Liquibase changeset:** 無

## 背景

2026-10-05 09:05 計算 context 與稍後取得的 direct assets 擁有相同 snapshot ID、持久化總額與列數，但 live 股票估值差 8,227 元。前者約 09:15 擷取、後者約 09:16:34 取得；把全部金額要求相同，會因盤中報價自然變動而整包拒用。另有 14 筆存款中的 10 筆 `annualInterestRate`／`estimatedAnnualInterest` 為 null：3 筆 `TRANSIT_TWD` 可明確不計息，7 筆一般存款利率未知。現行 SRPP 分組與收益計算把這些 null 經共用快照公式折為 0，再標 `ESTIMATE`，使未知被呈現為確定零。這是 SRPP 輸出品質缺陷；不得更動既有快照聚合公式或用臆測利率補值。

目前公式 manifest 為 `ASSET_MGMT_SRPP_V1`，registry 以政策 hash 作不可更新主鍵，並要求登錄 manifest 與程式內建常數精確相同。直接改既有常數會使已登錄政策及舊 package 查詢失效。2026-10-05 唯讀盤點：正式 registry 只有一筆 V1，hash 前綴 `4a755c23ba0a`，該 hash 有 174 個 package；當時 SRPP 檔案中的政策 hash 前綴 `32a6a997f8db`，未登錄。SRPP 對本次消費端修正仍持續重建政策包，該 hash 不是公式 manifest 的一部分；只能在 SRPP 最終變更完成後重新驗證完整 hash 與資料庫登錄狀態，不得沿用任何中途暫定值。

## 要做什麼

- [ ] 476.1 在 SRPP 專用模組計算建立缺值品質判定：一般 `TWD`／`USD` 存款 `annualInterestRate=null` 時未知；分組中任一列未知，整組 `estimatedAnnualInterest` 回 `value=null`、`unit="TWD"`、`quality="UNAVAILABLE"`、`reasonCodes=["DEPOSIT_INTEREST_RATE_UNKNOWN"]`、`sourceIds=[]`，assets 模組改 `PARTIAL` 並帶相同原因；未知利率只拒收受影響估息／收益欄，已通過靜態快照對帳的金額欄仍可獨立採用。已知利率含明確 0 仍委派 `SnapshotAggregateCalculator.depositEstimatedInterest(amount, rate, currency)`，不另寫數值公式。`TRANSIT_TWD`／`TRANSIT_USD` 不計息，即使 rate=null 也可明確輸出 `ESTIMATE` 0。既有八項金額對帳、snapshot/live 值與快照資料不改。
- [ ] 476.2 將未知一般存款 ID 以 `DEPOSIT-<id>` 加入 `missingIncomeRowIds`。任何未知一般利率使 `cashIncome.depositInterest` 成為 `UNAVAILABLE`＋`DEPOSIT_INTEREST_RATE_UNKNOWN`，有值的 `sourceAccruedAnnualIncome` 亦不可用同一原因標成完整估計。快照 `estimatedAnnualDividend=null` 時仍回既有 `SNAPSHOT_ESTIMATED_DIVIDEND_MISSING`；股票與基金分類各依自己缺值狀態輸出，不受存款未知污染。缺值時不執行三項分類和的完整收益對帳，不產生因缺值而來的 `INCOME_RECONCILIATION_MISMATCH`；cashIncome 及所有淨額／稅後欄位維持原 PARTIAL／UNAVAILABLE。
- [ ] 476.3 新增固定 `ASSET_MGMT_SRPP_V2`、manifest schema `SRPP_FORMULA_MANIFEST_V2`、assets calculation ID `ASSET_MGMT_ASSETS_RECON_V2`、cash income ID `ASSET_MGMT_GROSS_INCOME_V2`；allocation、funding、completedTechnicals ID 保持 V1。唯一 V2 manifest 規格是本 task 下方完整 JSON，並以 `spec/fixtures/srpp_formula_manifest_v2.json` 保存相同 canonical 內容；RFC 8785 JCS SHA-256 必須為 `0f3d9b67dcb7d519f8ef5e9ee378d86eaf26228409199bc487036732e0186e86`。Java `SrppFormulaCatalog` 是單一 runtime manifest 提供者，其 V2 JCS 必須與 fixture 完全相同；離線 registry SQL、OpenAPI 例子及 backend/BFF 測試均由同一 fixture/digest 驗證，不能自由定義另一份 manifest。registry 驗證按 V1／V2 各自固定 manifest 精確分派；固定舊 package ID 查詢在既有七天保留期內維持可讀，`/api/public/srpp/calculations` 對 V1 package 以 V1 語意重播，不能以 V1 digest 包裝 V2 輸出。producer 只對已驗證 V2 政策登錄發布新 package，V1 登錄僅供舊包讀取。新政策 hash 未登錄時回 409 `POLICY_UNSUPPORTED`；不可 UPDATE／DELETE V1 registry row，不得新建 DB schema 或改排程頻率。
- [ ] 476.4 在正式切換前唯讀查 registry 的 hash 前綴／formulaVersion／登錄時間及 package 數，核對 SRPP 最終 policy bundle hash（目前仍可能重建，必須以交付當下最終值為準；不得寫進公式 fixture）。以 `scripts/srpp-policy-register.rb --bundle <最終新 hash> --policy <已審核文件> --manifest spec/fixtures/srpp_formula_manifest_v2.json` 產生離線 insert-only SQL；核對完整 bundle hash、policy document、V2 manifest 與 digest 後依正式維運程序登錄新 row，保留 V1 row。V2 服務與新 row 都生效後，下一個既有五分鐘 producer tick 才可見新 package；若未具備兩者，不得聲稱已修復或讓舊 hash 冒稱 V2。不得在 GET 路徑登錄政策。
- [ ] 476.5 `docs/openapi/docker-external-api.yaml` 將 `SrppDepositGroup.estimatedAnnualInterest` 與 `SrppCashIncomeData` 缺值語意、原因碼及合成例明示；API 路徑、`SrppMetric` 五欄、owner scope、`Cache-Control: private, no-store` 不變。以現有 renderer 重產本專案和 `/Users/steven/Project/SRPP/docs/9090 Port API Swagger.md`，並更新機械 contract test、BFF 嚴格驗證與 backend 無 Spring 單元測試。覆蓋一般利率 null、明確零、同分組混合、在途 null、負在途、收益分類、V1/V2 固定包重播、registry 缺 V2、producer 不再發布 V1、JCS hash 與來源身分；另以跨專案驗證案例證明靜態 snapshot totals／rowCounts／depositGroups 任一不符會整包 fail closed，而一般存款利率未知只拒收受影響估息／收益欄，靜態對帳通過欄位仍可使用。
- [ ] 476.6 SRPP 消費端先跑 direct `/api/assets/latest` 原有 L0，再於同一已驗證 owner selector 下只核對計算 API 的 assets revision `snapshot-<id>-<digest>` 可解析的 snapshot ID、持久化 snapshot totals、`rowCounts`、`depositGroups` 的分組 key／金額；任一身分／靜態金額／列數／分組 key 或金額不符，須 fail closed 整包拒用；一般存款利率未知只拒收受影響估息／收益欄，已對帳靜態快照仍可用。現有按需端點沒有凍結逐持股身分與逐筆報價供 direct assets 對照，不能聲稱 holding/quote parity。頂層 `capturedAt` 屬 `/api/public/srpp/calculation-context` 的 context 建立時間；`sourceVector` 僅含 `revision`、`dataAsOf`、`bodySha256` 等來源欄，不含 `capturedAt`。因此 `liveStockValue`、`liveTotalAssets` 及 `ALLOCATION_GAP` 一律標 `VALUATION_CAPTURE_DIFFERENT_OR_UNVERIFIABLE` 並回退本機，以 direct assets 的較新估值為準；其他計算值各自通過獨立對帳後才可採用，不能由 snapshot 合計相符推定整包可用。SRPP 每日 D-179 summary 不得作為即時估值或配置差距採用入口；交付前須在每日規格、實際排程及驗證器確認此路徑停用，不能只依本任務宣稱已暫停。本項由 SRPP 專案配套實作與驗收，本任務不得改 SRPP 的排程或投資判斷閘門；本次不新增 quote revision 欄位、9090 route、行情 I/O、券商下單或任何交易動作。

V2 manifest 的完整 canonical JSON（與 fixture 位元組相同；檔尾單一換行不參與 JCS）：

```json
{"calculations":{"allocation":{"assetKey":"ASSET_KEY_V1","calculationId":"ASSET_MGMT_TOTAL_EXPOSURE_V1","division":"MathContext(34,HALF_EVEN)"},"assets":{"calculationId":"ASSET_MGMT_ASSETS_RECON_V2","dataAsOf":"MIN_LIVE_STOCK_UPDATED_AT_V1","depositInterest":"SnapshotAggregateCalculator.depositEstimatedInterest","depositNullRatePolicy":"ORDINARY_UNAVAILABLE_TRANSIT_ZERO_V2","inputs":"LatestAssetsDto.Response(snapshot,liveAssets,targetPriceComplete)","revisionProjection":"SNAPSHOT_DETAIL_EXCLUDING_DISPLAY_FIELDS_V1","tolerance":"max(0.01,max(abs(detail),abs(reported))*0.0001)"},"cashIncome":{"calculationId":"ASSET_MGMT_GROSS_INCOME_V2","depositNullRatePolicy":"ORDINARY_UNAVAILABLE_TRANSIT_ZERO_V2","netCalculation":"NOT_VERIFIED"},"completedTechnicals":{"calculationId":"ASSET_MGMT_TECHNICALS_UNAVAILABLE_V1","status":"CALCULATOR_NOT_VERIFIED"},"funding":{"calculationId":"ASSET_MGMT_FUNDING_UNAVAILABLE_V1","status":"CALCULATOR_NOT_VERIFIED"}},"decimal":"canonical-string","formulaVersion":"ASSET_MGMT_SRPP_V2","hash":"RFC8785-JCS+SHA-256","offsetLessTimezone":"Asia/Taipei","schema":"SRPP_FORMULA_MANIFEST_V2","timezone":"Asia/Taipei"}
```

## 驗證

先跑 `bash scripts/spec-check.sh` 並完成另一支 agent 的 spec 對抗式審查；修改程式前記錄 spec review gate。實作後執行：

```bash
python3 -c 'import hashlib,json; p="spec/fixtures/srpp_formula_manifest_v2.json"; v=json.load(open(p)); c=json.dumps(v,ensure_ascii=False,sort_keys=True,separators=(",",":")); print(hashlib.sha256(c.encode()).hexdigest())' # 必須輸出 0f3d9b67dcb7d519f8ef5e9ee378d86eaf26228409199bc487036732e0186e86
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml test
ruby scripts/tests/docker-external-api-openapi-test.rb
ruby scripts/render-9090-openapi-docs.rb --check
bash scripts/spec-check.sh
```

在主 worktree 依 `.agents/skills/run-stack/SKILL.md` 重建 business-services、BFF 與 gateway 後，對 9090 及 Tailscale 同名 HTTPS exact GET 唯讀驗收：新 hash 未登錄為 409、登錄後首個 producer tick 的新 package 為 V2；舊 V1 package 固定 ID 與按需計算仍可讀且 V1 digest 不變。讀回兩種存款缺值、收益原因碼與 JCS hash，確認無 GET 寫入、無券商或交易副作用。SRPP 端以 2026-10-05 兩時點資料做唯讀重播：持久化欄位相同、live 值不同時只降級價格依賴項；持久化欄位不同時整包拒用。另以新交易日計時證明 API 結果被採用且核心草稿時間改善，不能只用 API latency 宣稱日報加速。

## 完成報告

待實作與驗收後回填：變更檔案、獨立 spec review 結果、V1/V2 registry 與 package 讀回、9090/Tailscale 驗收、SRPP 重播與實際日報計時證據。未完成前標示 `SPEC_ONLY`，不得回報已部署。
