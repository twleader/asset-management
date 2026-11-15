VERDICT: PASS

## 判定
critical: 0   major: 0   minor: 1

## Findings

### [Minor] 新任務檔缺少規定的任務欄位、段落與可直接執行的驗證指令
位置：`spec/tasks/t593_claude_model_discovery.md:3`、`:14–20`

證據：完整讀取新檔後，只有「交付契約」「驗證與發布」兩節；未提供模板要求的前置任務、Liquibase changeset 欄位及完成報告段落。驗證項目列出「Python回歸含既有38項」等條件，但沒有測試指令。

被違反的出處：`spec/tasks/README.md:48–55` 規定固定結構及任務欄位；`:73` 明訂驗證須提供「可直接執行的指令」；`:95–97` 要求保留完成報告段落。

修法：依模板整理現有內容，明訂前置任務及 `Liquibase changeset: 無`，補上背景、要做什麼、驗證、完成報告；驗證至少列出實際測試入口：
`python3 -B -m unittest scripts/tests/test_srpp_local_ai_bridge.py`。另外列明 host installer 的檔名、來源選擇及功能回讀步驟，保留本輪「容器來源未變則不重建」的限定。

## 已查無發現

- **機械證據與範圍**：採用呼叫端提供的 `BLOCK: 0／CHECK: 0`，未重跑會產生檔案或外部副作用的檢查。`git status` 確認範圍為兩份規格修改及一份未追蹤任務檔。
- **既有識別字與計數**：確認 `scripts/srpp-local-ai-bridge.py`、`default_registry()`、`load_registry()`、`capabilities()`、`validate_selection()` 均存在；`scripts/tests/test_srpp_local_ai_bridge.py` 的 `test_*` 方法確為 38 項。無發現。
- **wire 相容性**：backend／BFF 的 `AiModel` 均為不可變 `record`，欄位與新規格一致；`SrppSettingsLocalAi.java:71–73` 已接受 `RUNTIME`，模型數量上限為 256；`SrppSettingsPresentation.java:18` 已傳遞工具目錄及 revision。未找到必須修改 Java/BFF/Vue 才能承載第四模型的證據。
- **內部一致性與資料來源**：Task 593 已明文取代舊 CONFIGURED 清單的權威性；動態結果使用具體 resolvedModel、先處理費用修飾再去重、無固定清單 fallback，三份材料方向一致。20 秒快取及 2 MiB 上限均有既有程式依據。
- **架構鐵則逐項核對**：Controller 直接讀 Repository／處理外部 HTTP：否；新增可變 DTO：否；新增頁面及漏建專屬 BFF：不涉及；跨頁 BFF、直打 business、裸 fetch／EventSource：未描述；自創跨頁例外：無；同義資料分流不同 business API：無；新增逆向服務依賴或外部行情直連：無；新增冗餘 DB 欄位或衍生值：無；新增業務分類硬編 enum：無；新增不符命名規則的 endpoint：無。
- **費用與發布邊界**：本輪明文要求逐模型 included 證據，且禁止僅靠名稱、API 目錄或 `extra_usage=false` 放行；未知拒絕、不購買點數、不改帳戶費用的條件一致。host 先驗收、合併後改指 main 的流程，可由既有 installer 與操作文件支撐。

## 自我挑戰

- 曾懷疑 `supportsEffort` 缺省處理互相矛盾。靜態查閱已安裝 CLI 2.1.325，可見產生器只在支援時加入 `supportsEffort:true` 及 effort 清單。但需求已要求未知／不完整 metadata 安全拒絕，「沒有能力即 null」也可限於已驗證的不支援狀態；不足以證實兩者必然衝突，因此未列 finding。
- 未把「現有 `claude_proof()` 尚無逐模型 included 驗證」列為缺陷：這是待實作規格，新需求已明確要求補足該證據並對未知狀態拒絕，不能把尚未實作的新要求誤報為現況錯誤。
- 保留任務檔格式 finding，因為缺漏可由新檔全文與 README 直接比對；它屬文件與驗證入口的補充，不影響實作方向，故列 Minor。
- 本輪未修改檔案、啟動 CLI、呼叫 POST、重建容器或執行模型推論；實機模型數量及成功解讀仍屬後續驗收事項。