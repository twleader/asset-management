# [t596] Claude 官方訂閱登入介面與過期憑證前置攔截

**對應 Requirements:** Requirement 208（情境AI工具的既有訂閱與不額外付費）
**前置任務:** t592、t593、t594、t595
**Liquibase changeset:** 無

## 背景

真usage的429期限後，發現Claude auth status仍loggedIn但Keychain access已過期。使用者已手動重新登入，現已確認cleanenv的claude.ai/pro及本地expiry有效，並要求憑證過期由介面直接引導官方登入，不應只教終端指令。登入與usage限流不同，任何登入成功都不能取消費用冷卻或授權推論。新增flow會改host共用登入，須主要管理者及session/CSRF保護。

## 要做什麼

## Task 596：Claude 過期前置攔截與官方訂閱登入介面

1. CLI auth status的loggedIn=true不等於access有效。claude_proof在真正usage HTTP前讀正常Keychain credential的expiresAt（epoch毫秒），若明確過期回AI_AUTH_EXPIRED、零usage/模型；存在但格式非有限正數回AI_AUTH_REQUIRED，不以過期metadata positive回退。缺少expiry沿既有fresh服務端proof，不把本地期限當費用授權。保留Task595 cooldown優先與等待期間原probe零native/usage，所有negative state/time不因登入而清除或縮短。
2. 新增獨立本地登入狀態查詢（不查usage/不跑模型），即使費用限流仍可顯示VALID/REQUIRED/EXPIRED/UNKNOWN/AUTHENTICATING，含nullable expiresAt與安全code。VALID只指本地正常CLI訂閱登入，不代表可免費推論。偵測EXPIRED/REQUIRED時，主要管理者頁面自動開一次站內登入提示；不自動POST登入、不自動開外站。登入按鈕是使用者明確動作，費用429不能被誤當必須重登。
3. 固定從私有registry選claude_cli工具，host只執行該固定executable的 --safe-mode auth login --claudeai。禁止--console、setup-token、任意command/env/provider/browser參數。clean_environment只在此子程序固定BROWSER=/usr/bin/true抑制host開頁，不改正常credential directory；不整份複製登入、手動交換/刷新OAuth token或手動寫Keychain。OAuth PKCE、交換與保存均由原生CLI管理，沒有模型回合；原生登入可能執行其必要帳號/政策網路檢查，不包裝任何額外usage query。
4. 本機CLI2.1.325的精確授權來源是 https://claude.com/cai/oauth/authorize ，manual redirect必為 https://platform.claude.com/oauth/code/callback ，client_id固定9d1c250a-e61b-44d9-88ed-5944d1962f5e。URL只接受HTTPS精確host/path、無userinfo/非標準port/fragment、無重複/未知query；必備code=true,response_type=code,redirect_uri,client_id,scope,code_challenge,code_challenge_method=S256,state。scope集合精確為org:create_api_key,user:profile,user:inference,user:sessions:claude_code,user:mcp_servers,user:file_upload,user:plugins,user:privacy_consents:write（順序不限、無重複）；這是native訂閱登入授權集合，不代表本功能可用API計費。可選orgUUID為有界UUID，login_hint/login_method僅在native正常URL有明確已審格式時允許，預設不傳email/sso；其他變動安全拒絕，不暗換登入供應商或自製OAuth頁。
5. 每個host最多一個登入session，opaque256bit flowId與BFF session binding，綁配置主要管理者實際owner。狀態WAITING_FOR_URL/WAITING_FOR_CODE/VERIFYING/SUCCEEDED/FAILED/CANCELLED/EXPIRED；有效期最多300秒，所有stdout合計≤128KiB，URL僅≤8192字元，processgroup/selector/pipe/本機callback必須完成、取消、逾時、超限均清理。native本機callback只能loopback，正式start驗證此邊界；登入session不持久化，terminal snapshot短期保留供UI讀，host重啟不得讓舊session可重播。相同binding重複start回同active flow，不同binding回BUSY，不啟動第二程序。
6. 使用native stdout的manual URL，由使用者在官方頁完成授權、貼回完整code#state。complete只允單行有界ASCII字串（總≤4096、恰一個#、code/state非空、拒控制字元/嵌入空白），以constant-time核對memory state後最多寫入native stdin一次；錯binding/session不得回傳他人URL或取消他人flow，錯state/格式回安全錯誤，不把任何碼寫檔/日誌/DB/localStorage/URL query。Native CLI退出0後仍以cleanenv auth status+本地expiresAt確認個人訂閱登入有效，才SUCCEEDED；無推論、無費用query、不假稱費用已確認，失敗不自動重試或回退舊帳號。
7. 登入與Claude模型執行互斥，Claude登入中對Claude probe/execute回AI_AUTH_IN_PROGRESS，避免檢查A帳號再執行B；Codex/Ollama仍可選及執行，不用全域model鎖阻擋它們五分鐘。相反，Claude模型active時拒絕新的Claude登入。native authstart與usage協調鎖避免開始後再發本bridge的Claude費用query；已有metadata/模型操作以有界BUSY回覆處理，不忙迴圈。登入成功只清記憶體Claude metadata/目錄快取，不動持久backoff、模型/effort或任何金融結果。
8. 新頁面專屬路徑（不新增9090）：BFF /api/bff/portfolio-advice/srpp/ai-auth/claude，business /api/portfolio-advice/srpp/ai-auth/claude；GET /state?toolId=<registryId>、POST /start {toolId}、GET /sessions/{flowId}、POST /sessions/{flowId}/complete {code}、POST /sessions/{flowId}/cancel {}。host固定bearer保護POST /auth/claude/state|start|status|complete|cancel，closed JSON欄位由Java加入server-derived ownerId/sessionBinding與必要toolId/flowId/code，不能讓browser自選owner/binding/執行檔。CLI登入URL/授權碼不進path/query，所有回應Cache-Control:no-store。schemaVersion=1、state/flow/錯誤DTO獨立不可變records，所有nullable欄位明確，敏感record toString遮蔽URL/code，不能改既有capability/interpret wire。
9. BFF獨立BootstrapResponse={schemaVersion:1,csrfToken:opaque256bit,login:AuthLocalState}；AuthLocalState={schemaVersion:1,toolId,loginState,expiresAt:null|UTC ISO8601,code:null|safeCode}；AuthFlow={schemaVersion:1,flowId,toolId,status,authorizationUrl:null|validatedOfficialUrl,expiresAt:UTC ISO8601,code:null|safeCode}。flowId/binding用base64url的32byte隨機值，flowId長43；ownerId為server配置主要管理者long ID。URL只能在WAITING_FOR_CODE（或首次ready）回覆，complete後/terminal均null；不得回傳token、refresh token、原始stdout/stderr、OAuth回應或登入密碼。
10. Host登入是改共用帳號的特權操作。BFF每次state/start/status/complete/cancel必從真Oidc principal驗ACTIVE+ADMIN+configuredAdmin，與fresh BusinessUserClient.configuredAdmin權威ID一致，且effective owner就是本人、沒有非本人代看；不能只看role或client supplied flags。BFF WebSession內產生獨立binding及CSRFnonce（不接受caller傳binding），POST JSON必帶X-SRPP-AI-Auth-CSRF，constant-time驗nonce＋嚴格同源Origin；缺失/跨Origin/跨WebSession/重播拒絕且零登入業務/host操作呼叫（既有通用登入身分解析除外；本流程的CSRF/Origin須在額外configured-admin權威查詢前拒絕）。Session值只留server/browser memory，不localStorage。forward顯式self headers並清Tenant context防shared WebClient覆寫為代看target。business也fresh驗AppUser ACTIVE/ADMIN與UserAdminService configuredAdmin email/ID、不能只用未涵蓋此路徑的AdminGate。
11. 所有auth路徑明確排除request/error ingestion及前端console/error reporter；不記body/header nonce/URL/code。新controller專用安全Advice攔截parse/transport/timeout/general例外，只白名單靜態碼訊息，不能用現行GlobalExceptionHandler的ex.getMessage或raw WebClient body。HTTP client固定host.docker.internal:8766、Bearer token file、no proxy/redirect、嚴格大小/期限與閉合wire驗證，controller只委派Service，service依auth port，host讀自身registry/normal credential。新安全碼AI_AUTH_IN_PROGRESS加入原capability白名單及前端映射，其它auth flow錯誤用獨立safe allowlist（AUTH_BUSY/FAILED/TIMEOUT/INVALID_CODE/SESSION_NOT_FOUND等）不可公開provider字串。
12. UI在Claude工具列提供主要管理者本人可用的重新登入動作及站內dialog。明確expired/required本地狀態可自動開站內提示一次（dismiss後不反覆開）；限流但local login VALID不得強迫重登。按鈕user gesture先建立安全空視窗、取得驗證URL後導向官方頁（清opener），popup被阻擋保留target=_blank rel=noopener noreferrer官方連結。只接受授權碼，不要password/API token；pending/驗證中/成功/失敗/取消/逾時皆有非技術文案。code提交後立即清memory；所有timers/舊owner回應在切換user、卸載、取消時清除/忽略，過期flow不得完成新session。poll只有auth status，最多每2秒一次、不使用loadRoot/center作登入輪詢；SUCCEEDED後最多單次重載local auth及工具清單（normalcap若cooldown仍只回限流），不把登入成功當成可免費執行。
13. 新host auth邏輯優先獨立scripts/srpp_claude_auth.py，bridge由同目錄明確importlib載入並註冊sys.modules，避免installer/test不同worktree module path快取問題。原生唯一認證來源仍cleanenv正常Claude Code-credentials，由CLI自身登入更新，不任意掃其它帳號/mtime或採環境API key。bootstrap/native-auth的local check與usage查詢分開；既有Codex access-only同步、Task595退避/fee gates、LOCAL與數值hash不變，無DB/交易/寄信/正式政策改動。
14. 測試須涵蓋過期guard零HTTP、local auth在429仍能顯過期、expiry malformed/missing規則、所有Task595/594回歸；native假CLI的URL/PKCE state/code、未知host/client/scope/duplicates/長輸出、跨binding/owner/replay/取消/timeout/cleanup、Claude模型與auth互斥且Codex/Ollama不受阻；BFF真principal/role/configured-admin/停用/代看/Origin/CSRF/跨session拒絕零hostcall、安全例外/DTO/密碼與URL不進ingestion；production browser合成流程的auto-dialog/user-popup/blockedfallback/貼碼/成功後一次reload/取消過期及owner races。部署需重建recreate business/bff/frontend與host，主權威.env/secret/TLS不覆寫。實機native start取得可驗的官方manualURL後立即cancel，零model、前後正常credential/token/registry bytes比對（只存bool），不能為測試強迫已登入使用者重登或輸出完整URL/code。若正式登入需要使用者授權，就在UI由使用者完成，不自動批准。
15. 保留真usage Retry-After現有期限，不刪/縮state；只有期限後才可fresh fee proof，最多一次合成Claude解讀，若仍拒絕如實記外部條件。不為完成驗收買點數、切API、使用stale成功proof或重試模型。獨立spec/arch審查與測試/feature部署通過後feature commit/push、main no-ff merge/push、乾淨main同服務重建、host來源main與remote/ref/config/TLS/舊run hash回讀。原Task595 main014daf49/e1526483與歷史WAITING/SAFE_REFUSAL收據保留；本次是使用者新增登入UX要求的續作。

- [ ] 596.1 實作host獨立auth manager、expiry guard、安全URL/state/期限與CLI lifecycle。
- [ ] 596.2 實作page BFF/business auth ports/routes/DTO/owner/CSRF/advice，原功能回歸。
- [ ] 596.3 實作登入dialog與官方頁/貼碼/狀態/owner lifecycle，不以中心GET輪詢。
- [ ] 596.4 完成審查、測試、feature原生start/cancel部署驗收、兩段Git與main回讀。

## 驗證

```bash
bash scripts/spec-check.sh
python3 -B -m unittest discover -s scripts/tests -p 'test_srpp*ai*.py'
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest='SrppSettings*,SrppClaudeAuth*,ShortTerm*' test
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f bff/pom.xml -Dtest='Srpp*Auth*,SrppSettings*' test
cd frontend && npm test && npm run build
```

以main權威.env於feature重建recreate business-services/bff/frontend，installer reload host；查本地auth狀態與費用限流分開、主要管理者native start/manualURL安全檢查後cancel、零模型/不印URL與credential。production browser使用合成OIDC/BFF，涵蓋本地expired提示、授權頁與貼碼、費用仍limited及取消/跨owner安全；正式TLS/9090/主權威設定/原數值hash回歸。不得以真usage刷重試、不碰broker/mail/政策。獨立Codex spec與arch PASS，feature commit/push、main noffmerge/push後同服務由main重建再回讀；所需OAuth使用者互動不得假造成功。

## 完成報告

最終測試、審查及feature部署收據存docs/reviews/t596_claude_subscription_login；main私有收據存/tmp/t596-acceptance/main-runtime.json。此檔凍結實作契約，實際外部限制與人工授權狀態以日期收據保留，不存URL/code/token/帳號資料。
