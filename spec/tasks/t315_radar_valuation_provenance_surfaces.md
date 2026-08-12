# [t315] 交易雷達 PE／PB／殖利率逐分量 provenance UI 與匯出閉環

**對應 Requirements:** Requirement 65（估值 component 必須逐項可追溯，呈現層不得代填或重算）
**前置任務:** t307（valuation composite）、t309（VALUATION evidence group）、t310（V13 停止點）
**Liquibase changeset:** 無

## 背景

後端快照已有 `FundamentalSnapshot.peEvidence/pbEvidence/dividendYieldEvidence`，每項可帶 value、percentile、provider、sourceUrls、availableAt、asOf、loss；`RadarEvidence.evidenceGroups.VALUATION.components` 另帶 `pe/pb/dividend_yield` 的 applicability 與 missingReason。交易雷達畫面目前把三個值擠在同一列，只有 PE 位置使用 generic `valuationProvider/valuationAsOf`；PB 與殖利率沒有自己的來源、日期或缺漏原因。Excel 也只有 PE generic provenance，PB／殖利率只輸出值與分位。這會讓使用者無法區分「真的有資料但未納入」、「stale」、「250 筆歷史不足」與舊快照缺欄。

本任務只呈現後端已解析的證據，不改 valuation score、confidence、action、provider selection 或 freshness 公式，不升版 V13。

## 要做什麼

- [ ] **315.1 唯一 component projection。** 把純 mapping 抽到 `frontend/src/utils/valuationEvidence.js`（名稱可調整），由 `TradingRadarView.vue` 唯一呼叫，將三個固定 component 映成 view model；不得在 Vue 另留第二份 mapping：
  - 後端 `TradingRadarEvidenceConfidenceResolver` 對整組 VALUATION 不適用時，也必須輸出 `pe`、`pb`、`dividend_yield` 三個具名 component，三者 `applicability=NOT_APPLICABLE`、不帶偽造 provider/value/reason，且整組仍退出 coverage 分母；不得維持空 components 後要求前端猜測。
  - `pe` → `fundamental.peEvidence`；`pb` → `fundamental.pbEvidence`；`dividend_yield` → `fundamental.dividendYieldEvidence`。
  - applicability/missingReason 只取 `evidence.evidenceGroups.VALUATION.components` 中同名 component。
  - value/percentile/provider/sourceUrls/availableAt/asOf/loss 只取對應 `*Evidence`；不得用 generic `valuationProvider/valuationAsOf/valuationSourceUrls` 補 PB 或殖利率，也不得在 Vue 計算分位、freshness、250 筆門檻、coverage 或 composite。
  - component/evidence group 缺欄的舊快照回明確 legacy 狀態與空 provenance，不得把空值格式化成 0 或 AVAILABLE。

- [ ] **315.2 畫面逐分量顯示。** 在既有「基本面與產業」區塊把 PE、PB、殖利率顯示成三個可獨立閱讀的項目；每項至少顯示 value（PE/PB 倍數、殖利率百分點）、自身分位、`AVAILABLE|MISSING|STALE|NOT_APPLICABLE` 中文語意、provider、as-of、available-at、missing reason，以及每個 source URL 的可點擊連結。PE loss 顯示「可信來源顯示虧損」但仍保留該 loss observation 的 provider/date/URL。ETF／其他整組不適用時保留現有整組說明，並顯示三項後端給定的 `NOT_APPLICABLE` 狀態；不得因 `fundamental.applicable=false` 直接跳過三項。舊快照顯示「舊快照未含逐分量證據」。既有 composite/coverage、EPS、ROE、營收、產業與 evidence-group 展開區保留，不在前端重算。

- [ ] **315.3 Excel/JSON 共用表新增固定欄位。** `TradingRadarExportService` 的「個股決策」表在現有 detail evidence 欄位尾端，依下列固定順序追加 18 個**唯一**欄名，禁止只重複六個 generic 名稱（`ExportDoc` 會拒絕重複 header）：
  - `PE適用狀態`、`PE Provider`、`PE來源網址`、`PE可得時間`、`PE資料日期`、`PE缺漏原因`
  - `PB適用狀態`、`PB Provider`、`PB來源網址`、`PB可得時間`、`PB資料日期`、`PB缺漏原因`
  - `殖利率適用狀態`、`殖利率 Provider`、`殖利率來源網址`、`殖利率可得時間`、`殖利率資料日期`、`殖利率缺漏原因`

  provider/URL/availableAt/asOf 只取各自 `*Evidence`；status/reason 只取同名 VALUATION evidence component。舊快照空白保留 null/空 list，不使用 generic valuation provenance 代填。xlsx 與 JSON 必須由同一 `ExportDoc` row 產生。

- [ ] **315.4 header/format/row lockstep。** 315.3 列出的 18 個 headers 必須逐字鎖進單元測試，且 headers、formats 與每一 row 的 cells 同長同序；狀態/provider/time/date/reason 為 TEXT，URLs 為 LIST_LINES。另斷言整份「個股決策」headers 無重複值，讓 `ExportDoc` 的 fail-fast 在測試中可辨別。保留既有欄位順序，僅在尾端追加，避免舊索引漂移。可新增 package-private 純 helper 供單元測試，但 production 只能保留一份 mapping。

- [ ] **315.5 測試。** 至少涵蓋：
  - 三 component 使用不同 provider/asOf/source URL 時，畫面 helper 與匯出逐項不串線；
  - PE available、PB stale、yield missing 的 applicability/reason 各自正確；
  - VALUATION 整組不適用時後端仍回三個具名 `NOT_APPLICABLE` component，UI／匯出逐項呈現且 coverage 分母不變；
  - PE loss observation 顯示 loss 與來源，不被 generic provider 覆蓋；
  - legacy snapshot 缺 `*Evidence/evidenceGroups` 時三項不顯示 0、不冒充 AVAILABLE；
  - 真實 `ExportDoc` 與渲染後 workbook 的 header/format/row 長度相等，18 欄值與 source URL 正確；
  - `frontend/src/utils/valuationEvidence.test.js` 以 Node test 直接驗上述 mapping，並把它與既有 `displayQuote.test.js` 一併納入 `npm test`；build 不得取代 mapping assertions；
  - frontend test/build 通過，runtime/browser 抽查三卡與既有基本面、evidence group 畫面都存在。

- [ ] **315.6 維護註解一致。** 修正 `FundamentalAnalysisService` 估值 composite 的 Javadoc：PE／PB／殖利率是逐 component 在各自 provider 歷史中選取，可有不同 provider/date；不得再描述成「同一 provider composite」。只改註解，不改既有 selection 行為。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -DextraArgLine=-Dnet.bytebuddy.experimental=true -Dtest=TradingRadarExportServiceTest test
(cd frontend && /Users/steven/.nvm/versions/node/v22.21.0/bin/npm test)
(cd frontend && /Users/steven/.nvm/versions/node/v22.21.0/bin/npm run build)
```

## 完成報告

（回填三種 component 的畫面／xlsx／JSON 抽查值、legacy 行為、headers/formats/rows 欄數及測試/build 證據。）
