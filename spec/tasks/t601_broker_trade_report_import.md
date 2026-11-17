# [t601] 受控富邦成交回報匯入與跨來源防重

**對應 Requirements:** Requirement 213（登入使用者預覽/確認原始成交回報，安全記入帳本）
**前置任務:** 無
**Liquibase changeset:** v1.168.0-broker-trade-report-import.sql

## 背景
富邦filled_history權限不足；庫存同步與帳本分離。不得由庫存增減猜成交，郵件委託書號也不是filled_no。

## 要做什麼

### 受控富邦成交回報匯入（Task 601）
登入使用者明確預覽及確認原始券商成交回報後，僅把已成交事實加入自己的交易帳本，不更新持股、存款、交割、成本或券商狀態。這是 authenticated importer 的來源/帳戶歸屬聲明，不是系統驗證 DKIM、S/MIME 或持有券商帳戶；masked account、From、Authentication-Results 均不是登入認證。無新 Gmail scope、收信排程、共用 token、9090 route 或券商呼叫。

契約：沿現有 authenticated session 與 TenantGuard（既有BFF全站CSRF disabled，不宣稱已有CSRF保護），新增 POST /api/bff/transaction/report-import/preview、/apply，proxy 到 business /api/asset-transactions/report-import/preview、/apply。request 為 sourceMessageId（非空≤200）、rawMimeBase64、attestedOwnerAccount=true、attestedAuthenticReport=true；apply另需previewHash。owner絕不取body/email/masked account，僅current ACTIVE authenticated user。BFF遇IMPERSONATE_UID代看其他人時拒403，不重設或移除使用者cookie；後端再驗CurrentUserContext.hasUser、effective id非null且DB ACTIVE；本人相等由BFF以原OIDC principal和effective identity比較後再relay，禁止代看他人且不新增actualUserId header/context欄位，否則拒絕。未登入在BFF既有防線拒絕；兩新增POST另做局部瀏覽器同源防護：要求Content-Type application/json及X-Trade-Report-Import: 1，Origin精確等於已部署frontend public origin（沿現有設定，不採caller Forwarded/Host決定），Sec-Fetch-Site必須same-origin，缺任一即403；不更動全站security/CORS或新增憑證；局部origin基準沿既有APP_PUBLIC_BASE_URL值（compose新增此現有非secret值對BFF的映射，不變更值或其他安全設定），空白/非合法HTTP(S) origin fail closed。既有TransactionBffRoutes /api/asset-transactions/** passthrough必明確排除並403拒絕report-import及其descendants，不能繞過專用handler同源防護，所有非兩exact POST路徑亦不轉送；business仍驗TenantGuard及active user。交易頁新增獨立匯入dialog與按鈕：選擇.eml、本地讀bytes/base64、輸入sourceMessageId（可由MIME Message-ID預填但不當真實身份）、勾選來源及本人帳戶兩聲明→preview逐列量價/未知費稅/信任/衝突→明確確認apply；沿既有axios與本頁BFF，禁止裸fetch。取消/換檔/改聲明使preview失效，in-flight禁重複點擊，成功刷新ledger一次；不能清掉其他新增/編輯表單state。沒有新增登入機制。

原始MIME decoded≤512KiB；HTTP encoded JSON body≤768KiB（512KiB base64為699052 bytes，加小型metadata仍在預設BFF 1MB decoder內）；BFF以本端點bounded body read/parse檢查實際bytes，不僅信Content-Length，不放寬全站decoder；驗收須經真BFF inbound decoder測接近512KiB合法MIME、decoded超限及encoded超限、parts≤16、深度≤5、成交列1..100；拒非法base64、亂碼、缺/重複主欄位、多份不一致成交正文、已識別交易表中的未知欄、非現買/現賣、未來日期、缺時間/委託號。接受multipart/signed（protocol application/x-pkcs7-signature）、quoted-printable或base64傳輸、富邦固定中文報表表格、UTF-8或Big5 MIME正文，僅HTML文字/table或一致text alternative；smime.p7s只作未驗證簽章附件忽略，其他附件拒絕，不執行HTML、不下載圖片/連結、不處理文中指令、不作任意URL讀取。精確表頭為股票名稱/交易類別/成交股數/成交單價/成交價金/委託書編號/成交時間/備註（thead tr td皆可），只選一份符合表頭的交易table，忽略非交易行銷table/圖片且不載入。股票名稱cell為代號直接接中文名，數字容合法千分逗號但不接受錯誤分組，備註&nbsp;視空字串。日期取表前「以下是帳號…在 YYYY 年 M 月 D 日…的成交資料」，與Subject如有日期須一致，不取收信Date代替成交日。由明確日期與表頭解析code/name/side/shares/price/gross/orderNo/time，股票代號4..6數字或合法債券碼；精確十進位（qty正整數、price正且scale≤6、gross=qty*price且scale≤2），不推估費稅，fee/tax=null。郵件委託號不寫broker_filled_no。先核本地股票主檔與active fubon broker；不知名稱/代碼或身份有歧義拒絕。收件地址僅展示供聲明，不據此授權owner；不保存全帳號或email至log。

preview零持久寫入、零外部呼叫，回正規化明細、NULL費稅說明、來源信任ASSERTED_BY_AUTHENTICATED_IMPORTER、SHA256(rawMime)、previewHash、可匯入/重複/衝突結果。previewHash綁owner、原始hash、messageId、完整正規化列與parserVersion；apply完整重解析、重驗hash與當下帳本，不以preview當授權快照。相同sourceMessageId不同raw hash拒409；郵件重寄/不同messageId同語意列：以owner+broker+tradeDate+orderNo+filledTime+code+side為identity，完整量價一致且既有帳本內容未被改/刪才回ALREADY_IMPORTED；不同量價/既有帳被改/刪回409，不默默跳過或覆寫。不同order/time即使相同價量不得當同一成交自動合併。

BFF展示投影：business既有response事實欄位不變；BFF在preview/apply的200與409回應增加conflictRows（原rows中status=CONFLICT或reason非空者，保留原順序）與canApply布林。canApply僅preview、HTTP200、status為READY或ALREADY_IMPORTED、previewHash非空、rows非空且conflictRows空時true，apply回應一律false；未知或不完整response fail closed，不可猜可匯入。前端只render BFF給的conflictRows與canApply，僅結合reading/confirming/applying等操作狀態，不自行filter或重新定義衝突。底層apply仍重新解析、鎖定與完整驗證，展示投影不是授權。新增HTTP records置dto/TradeReportImportDto容器，領域例外HTTP映射集中GlobalExceptionHandler且只回既有安全code/message/本人rows，不洩漏原始MIME或第三方內容。測試須覆蓋READY、ALREADY_IMPORTED、CONFLICT、409、apply後canApply=false、缺失/未知回應fail closed與前端直接render契約。

DB新增broker_trade_report_receipt（owner/sourceMessageId unique、rawSha256、parserVersion、createdAt）與broker_trade_report_receipt_line（receiptId/evidenceId聯合唯一FK）保存每封成功apply來源；即使整批ALREADY_IMPORTED也原子保存新receipt及關聯，preview仍零write。「A首次→B同義重送→B更換hash」必409。另新增broker_trade_report_import表（immutable source evidence的具名正規化例外），owner FK、parserVersion、identityHash、factsHash、canonicalFacts JSON文字（只正規化成交事實及來源聲明、不存完整MIME/帳號）、transactionId FK ON DELETE SET NULL、createdAt；owner+identityHash unique；source message/hash一致性在owner row lock下檢查。每批apply先鎖app_user FOR UPDATE、重驗ACTIVE，然後全批驗證後單一transaction插ledger與evidence，全有或全無。source=FUBON_EMAIL，brokerFilledNo=null，amount為gross、fee/tax/null、notes明示來源與費稅未知（不存私人原文）。保存ledger facts hash供replay察覺人工更正；不得靠可編輯notes去重。

跨來源：若同owner/broker/date/code/side已有非本identity的manual、SDK或其他email候選，保守409 POSSIBLE_DUPLICATE（不以價量相同推定同一成交，也不自動新增可能重複）。相關手動create/update與FubonTradeWriter使用同app_user row lock，對已存在email匯入的同日同code同side可能碰撞亦拒絕/整批rollback，避免SDK恢復後重複入帳。不得阻擋其他owner或完全不同日期/code/side。既有匯入列可透過原人工update修正但保留immutable provenance；再重寄回409 LEDGER_CHANGED，不重新新增或還原。delete保留evidence tombstone、之後replay回409。衝突response包含本人ledger id及安全reason，讓使用者走既有交易紀錄編輯/查核流程；首版不提供強制新增、覆寫、跨來源自動合併或撤銷真交易。此限制需在preview與文件明示。

測試：純parser合成fixture（不把真實使用者資料放repo），有效UTF8/Big5/多列、fees null、錯日期/未來日/缺欄/非法amount/attachment/大小深度/正文歧義、外部URL零請求；service owner isolation與inactive、preview零write、hash mismatch、同message不同hash、重送same/different message、manual/SDK跨源衝突、不同order同價量不誤合併、人工修改/delete後replay、全批rollback。PostgreSQL/Testcontainers驗owner鎖及unique併發apply兩次只一份ledger，SDK/manual競態不雙入。BFF無新增匿名allowlist、拒direct passthrough與代看他人、proxy preserve status/本人identity，新增POST局部origin/custom-header/fetch-site拒絕矩陣，現有transaction/Fubon相關回歸。既有asset_transaction.source CHECK擴為MANUAL/FUBON_SYNC/FUBON_EMAIL（其餘仍拒）；schema由Liquibase v1.168.0新增並在部署後重產db/schema.sql；不得宣稱尚未執行的runtime驗收已通過。禁止真實回報入帳、郵件寄送、部署與commit，交由協調者另行授權收尾。

## 驗證
- [ ] 601.1 spec-check與獨立CLI spec審查。
- [ ] 601.2 實作純解析、authenticated preview/apply、DB防重與回歸。
- [ ] 601.3 PostgreSQL併發與BFF測試、獨立arch審查。

## 完成判準
規格gate通過、測試真實結果可查；部署與真實資料匯入由協調者執行，未執行明列。

## 備註
只用合成測試，不保存真實帳戶、信件與成交於repo。
