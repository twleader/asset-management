VERDICT: PASS

## 判定
critical: 0   major: 0   minor: 0

## Findings

無。

## 已查無發現

- **機械證據：**採用提供的 `spec-check` 結果，`BLOCK: 0 / CHECK: 0`，未重跑。最終 `git status --short` 仍只有 `spec/design.md`、`spec/requirements.md` 與新增的 `spec/tasks/t596_claude_subscription_login.md`。
- **內部一致性：**逐字比對三份文件的 Task 596 編號條款，均為 15 條且內容相同。最後讀取的第 10 條已一致區分「既有通用登入身分解析」與「本流程額外 configured-admin 權威查詢」，並要求 CSRF／Origin 在後者之前拒絕，無發現。
- **既有識別字與行為：**已查 `scripts/srpp-local-ai-bridge.py:276` 的 `clean_environment()`、`:559` 的 `claude_cooldown()`、`:571` 的跨 process usage 協調鎖、`:875` 的 `claude_proof()`，以及 `:1155`／`:1205` 的 parent／worker 執行入口。既有流程確實先檢查 cooldown，再讀原生登入與正常 Keychain credential；本輪 expiry guard 與登入互斥屬待實作變更，沒有被誤寫成既有功能。
- **owner／tenant 與錯誤處理：**`BusinessUserClient.java:61` 確有 `configuredAdmin()`；`UserAdminService.java:83` 確有主要管理者權威查詢。`WebClientConfig.java:121` 的 filter 會以 Reactor context 覆寫 tenant headers，符合本輪要求清除 context 的理由。`WebConfig.java:25` 的 AdminGate 註冊範圍未涵蓋新 auth 路徑；`GlobalExceptionHandler.java:120` 確實使用 `ex.getMessage()`。相關現況敘述可查證，無發現。
- **原生 CLI 契約：**`ls -l` 確認目前 `~/.local/bin/claude` 指向 `2.1.325`。以 `rg -a -o` 查安裝檔內嵌程式，確認 `authLogin`、`Sz.startOAuthFlow`、`d2o`、`Pqo()`／`Q5t()` 等識別字，以及規格列出的授權來源、manual redirect、client ID、scope 集合、S256、stdout manual URL、stdin 貼碼處理與 `127.0.0.1` callback listener。官方文件也記載瀏覽器登入及手動貼回登入碼的流程，無發現。[官方認證文件](https://code.claude.com/docs/en/authentication)、[官方 CLI reference](https://code.claude.com/docs/en/cli-reference)
- **資料來源正確性：**已查本輪完整 diff，沒有新增行情來源、DB schema 形貌斷言、固定排程時間或金融公式；未用 Liquibase changelog 推定現行 schema。沿用提供的 schema／OpenAPI 機械驗證結果，無發現。

依完整 `CLAUDE.md`、`spec/steering/structure.md` §3.2 與本輪契約，架構鐵則逐條核對如下：

| 檢查項 | 結果 |
|---|---|
| Controller 直接注入 Repository、執行外部 HTTP、upsert 或領域判斷 | 否；第 11 條要求 Controller 委派 Service，Service 依 auth port。 |
| 新 DTO 使用 `@Data`／setter | 否；第 8 條明定獨立不可變 records。 |
| 新 UI 功能缺少本頁專屬 BFF | 否；第 8 條指定 portfolio-advice 本頁 BFF 前綴。 |
| 前端呼叫別頁 BFF、直打 business 或繞過 `api/index.js` | 否；本輪未提出此類呼叫或例外，既有統一 API 規範仍適用。 |
| 自行發明跨頁共用例外 | 否；未主張例外。 |
| 同義值改走不同 business API | 否；本地登入狀態與費用 proof 已明確區分語意，未建立同義值的平行權威來源。 |
| frontend 直連 business、business 直連外部行情、external-materials 反向呼叫 | 否；新 auth 鏈路經 BFF → business → host，未修改行情相依方向。 |
| FK／字串冗餘或保存可計算業務衍生值 | 否；無 DB 變更，登入 session 明定不持久化。 |
| 新業務分類硬編 enum | 否；新增項目是登入狀態與安全協定碼，未新增投資業務分類。 |
| 新 endpoint 違反 method／kebab-case 慣例 | 否；新增路徑與方法未見違規，亦未新增 9090 暴露入口。 |

## 自我挑戰

- **撤下「原生登入必須使用 TTY，因此無法透過 pipe 貼碼」的疑慮。** 安裝版 `authLogin` 有直接以 `process.stdin` 建立讀行介面、接收 `code#state` 的分支；不能僅因它是互動登入命令便判定管線不可行。
- **撤下「manual URL 與本機 callback listener 必然衝突」的疑慮。** `Sz.startOAuthFlow` 同時建立 manual／automatic URL，並依完成方式選擇 token exchange 的 redirect；規格要求採 manual URL 與限制 listener 為 loopback，可同時成立。
- **未將同 binding 重複 start 與防重播直接判為矛盾。** 第 5 條描述 host 的 active-flow 冪等處理；第 10 條描述 BFF 的請求防護，不能直接視為同層互斥契約。
- **未將尚不存在的 auth manager、routes、DTO 或 `AI_AUTH_IN_PROGRESS` 列為錯誤現況斷言。** 三份文件都將它們列為本輪待實作項目。
- **未將目前使用全域模型鎖列為本輪缺陷。** 第 7 條正是要求新增登入互斥時避免讓 Codex／Ollama 被登入流程阻擋；實作前尚未修改不構成 spec finding。
- 自我挑戰後無保留 finding。

## 無法查證

本輪未啟動 CLI、讀取真實 credential／token、呼叫 API、執行測試、操作瀏覽器或部署。原生 start／cancel 後憑證不變、實際 callback 綁定、跨 process 互斥、CSRF／重播拒絕、敏感資訊排除與 UI lifecycle，仍須由實作後測試及部署收據證明。

本判定僅涵蓋最後讀取的規格與靜態證據，不代表正式登入、429 恢復或模型推論驗收已完成。