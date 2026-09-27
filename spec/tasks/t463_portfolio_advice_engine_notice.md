# [t463] 讓資產配置建議引擎限制說明符合目前模式與使用者角色

**對應 Requirements:** Requirement 80（資產配置建議的 local／hybrid／llm 三態引擎）
**前置任務:** 無
**Liquibase changeset:** 無

## 背景

資產配置建議頁已有一則常駐「本機配置模板」說明，但目前固定要求使用者切換 `llm`。引擎設定下拉只對管理者顯示，所以一般使用者看不到可操作的下拉；另一方面，前端仍應依實際取得的 `availableEngines` 判斷 `llm` 是否可用，不可把不在選項清單中的模式說成可切換。現有文案也未交代本機／混合模式的標的分攤規則，或清楚列出分析限制。

正確說明應反映三態引擎的真實行為：`local`／`hybrid` 以固定規則模板決定配置類別比例；股票與信託基金只在相同 `(assetClass, subClass)` 群組內按現值比例分攤，存款增碼按現值比例、減碼依提領優先序逐筆抽取。不推薦尚未持有的新標的，也不分析個別標的基本面、損益、交易成本或稅務；並保留模板未經回測或個人情境驗證、不構成個人化投資建議的揭露。完整 AI 引擎的提示須依角色及實際可用選項調整，不得改動引擎或權限。

## 要做什麼

- [x] **463.1 修正文案內容**：在 `AssetAllocationAdviceView.vue` 保留常駐說明，明確說明 `local`／`hybrid` 的目標配置類別比例由固定規則模板決定，屬常見經驗法則、未經回測或個人情境驗證且不構成個人化投資建議。股票與信託基金只在相同 `(assetClass, subClass)` 群組內按既有持倉目前市值比例分攤標的層級金額；存款（現金）增碼按存款市值比例分攤，減碼依提領優先序逐筆抽取。不得把存款減碼說成一律等比例。不推薦使用者尚未持有的新標的；不分析個別標的基本面、損益、交易成本或稅務。不得把分攤規則描述為對個別標的的市場判斷或選股。
- [x] **463.2 管理者提示**：目前引擎為 `local` 或 `hybrid` 時，若 `settings.availableEngines` 含 `id = "llm"`，管理者提示可切換完整 AI 引擎，顯示名稱必須由該選項的 `label` 動態取得；不得寫死顯示名稱。若清單沒有 `llm`，改為說明完整 AI 引擎目前不可用，並提示管理者檢查引擎設定；不得暗示可以切換至不存在的選項。已選 `llm` 時不得顯示「切換為 llm」或其他要求切換至目前模式的 CTA。
- [x] **463.3 一般使用者提示**：一般使用者不顯示管理者設定下拉，也不得被指示操作隱藏控制項。當目前引擎為 `local` 或 `hybrid` 時，完整 AI 引擎提示應引導其洽系統管理者設定；不提供不存在於一般使用者介面的操作步驟。
- [x] **463.4 限定為前端呈現**：保留現有引擎選單可見性、權限、engine 值、設定儲存、BFF/business API 與生成邏輯。共用說明可常駐呈現；依目前引擎、使用者角色及 `availableEngines` 有無 `llm` 決定 CTA 文案。沿用現有引擎選項與 label 查找方式，不新增 API、設定欄位或資料庫 changeset。
- [x] **463.5 驗證呈現分支**：在 `frontend/src/utils/portfolioAdviceEngineNotice.contract.test.js` 新增 Node 契約測試，並將此檔加入 `frontend/package.json` 的 `test` script；測試至少驗證：(a) 管理者、local/hybrid、清單含 llm 時顯示該清單 label；(b) 管理者、local/hybrid、清單無 llm 時顯示不可用提示；(c) 非管理者、local/hybrid 時引導洽管理者而不指示操作選單；(d) llm 模式沒有要求切換至 llm 的 CTA；(e) 固定說明保留經驗法則免責，且完整包含股票／基金子類別分攤、存款增碼比例／減碼 waterfall、新標的禁止與分析限制。契約測試須可由 `npm --prefix frontend test` 實際執行。

## 驗證

```bash
npm --prefix frontend run build
npm --prefix frontend test
bash scripts/spec-check.sh
```

以 `run-stack` 流程從目前 main 同源的 feature worktree rebuild/recreate `asset-frontend`，確認容器健康與前端 HTTP 200。若可使用已登入 UI，分別檢查管理者與一般使用者的提示；若沒有可用的測試帳號，使用契約測試驗證各角色分支並在完成報告中註明 UI 登入驗證限制。不得改動或觸發任何引擎設定寫入、LLM 產生或交易操作以完成呈現驗證。

## 完成報告

實作變更：新增 `portfolioAdviceEngineNotice.js` 限制說明與角色提示 helper；`AssetAllocationAdviceView.vue` 採 helper 顯示，保留模板警語並於 local/hybrid 條件下依角色與 `availableEngines` 呈現 CTA。新增 Node 契約測試並加入 `frontend/package.json` 測試清單。未改引擎選擇、權限、設定 API 或生成邏輯。

驗證：`npm --prefix frontend test` 86 passed；Vite build passed；`bash scripts/spec-check.sh` BLOCK 0／CHECK 0；`git diff --check` passed。Feature runtime 的 `asset-frontend:latest` 為 `sha256:b79bfeda257a46b485436e4dea130ae04e2a2f81bc3a41535d75ff275eb4f497`，容器 running，首頁 HTTP 200；編譯 bundle 含本任務說明。沒有可用的已登入 UI 上下文，因此管理者／一般使用者狀態由契約測試驗證。

未呼叫 LLM generate、設定寫入或交易 API；共享 stack 其他服務與既有 Fubon flags 維持不變。
