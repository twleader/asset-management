VERDICT: PASS

## 判定

critical: 0   major: 0   minor: 0

審查範圍：第 2/3 輪，僅審 Task 596。以 `HEAD=014daf49c9fe763e00a55e26e4e7657eedf3efd1` 對照工作目錄的 tracked diff，並納入本輪未追蹤的新增實作檔。已核對 backend／BFF 的 auth Controller、Service、Port、adapter、DTO、Advice／Filter，前端 API、登入元件與工具函式，以及 Python bridge／auth manager；另靜態核對相關測試。判準僅採 `CLAUDE.md` 與 `spec/steering/structure.md`。

## Findings

無。前輪唯一 Minor 已修正，本輪未發現符合上述兩份規範、且由本次 diff 新增或擴大的架構違規。

## 已查無發現

- **前輪時間戳問題已修正。** 對 backend 與 BFF 的 `SrppClaudeAuthDto.java` 執行 `grep -ran -E 'public record (AuthLocalState|AuthFlow)|JsonFormat|private static boolean iso|private static void types'`，兩檔均確認：
  - 第 12 行：`AuthLocalState.expiresAt` 為 `Instant`，並加上 `@JsonFormat(shape=...STRING)`。
  - 第 13 行：`AuthFlow.expiresAt` 為 `Instant`，並加上相同字串序列化標註。
  - 第 21、22 行：原始非 null 時間值仍須為文字、以 `Z` 結尾且可由 `Instant.parse()` 解析，才進入 record 轉換。
  
  另確認第 10 行註冊 `JavaTimeModule` 並停用 `WRITE_DATES_AS_TIMESTAMPS`。符合 `structure.md` §7，第 622 行「`LocalDate`（日期）/ `Instant`（時間戳）」。

- **模組界線未擴散。** 對本輪新增 Java auth 檔執行 `grep -ran -iE`，查詢外部行情網址、`Repository|JdbcTemplate|EntityManager|RedisTemplate` 與券商委託呼叫，未命中。本輪沒有 external-materials、Entity、Repository 或券商服務變更。新增 business 外呼由 `SrppClaudeAuthLocal` 對固定 host bridge 執行，未新增直連行情／NAV／配息 API 或跨服務資料表存取，未見違反 `structure.md` §8。

- **分層職責符合。** `SrppClaudeAuthController.java:6–12` 與 `SrppClaudeAuthBffController.java:5–11` 均接收參數、委派 Service 並包裝 HTTP 回應。backend Service 依賴 `SrppClaudeAuthPort`，HTTP 與 token-file I/O 位於 `SrppClaudeAuthLocal`。搜尋新增 backend service 的 `ResponseEntity|HttpServletResponse` 無命中；相關 HTTP 類別僅出現在 Controller／Advice／Filter，符合 `structure.md` §2.2 與 `CLAUDE.md`「Clean Architecture 原則」。

- **DTO 未踩到已知誤報陷阱。** 兩份 DTO 外層 class 均承載巢狀不可變 records；搜尋真正行首 `@Data`、JPA 註解與 Entity 參照無命中。未將外層容器 class 判為違規，符合 `structure.md` §2.2、§2.3。

- **BFF 與前端呼叫邊界符合。** `frontend/src/api/index.js:444` 起新增的五種登入呼叫均使用既有 axios instance，路徑為 `/bff/portfolio-advice/srpp/ai-auth/claude/...`；對登入元件與新增工具函式搜尋 `fetch\(|new EventSource` 無命中。未新增頁面、跨頁 BFF 消費者或前端金融聚合／排序／衍生計算。掛載時 root 與本地 auth 查詢分別以 `void` 啟動，未新增序列等待。

- **本輪 popup 輪詢修正已靜態確認。** `SrppSettingsCenter.vue:1509` 在接受 flow 時，只有 `flow.authorizationUrl` 存在才呼叫 `navigateClaudeAuthPopup()`；第 1440 行的提前返回也保留已導向 popup 的狀態。登入輪詢於第 1481 行只呼叫 auth status；成功後的工具重載由第 1517 行附近的 flowId 去重條件保護。未見輪詢改用中心 GET 或新增繞過 API 層的呼叫。

- **資料模型、命名與資料來源沒有新增違規。** 以 `git status --short`、`git diff --name-only HEAD -- ...` 核對，本輪沒有 DB schema、Liquibase、Entity 或 Repository 變更，也沒有新增持久化／傳輸金額欄位。新增 endpoint 採既有頁面前綴與 kebab-case。auth 狀態、安全碼及 OAuth scope 是閉合協定值，未當成可管理的投資業務分類。bridge 的 expiry 檢查位於 usage HTTP 前；登入成功的失效處理只清記憶體 metadata／目錄快取，未新增清除持久 backoff 的操作。

## 無法查證

本輪僅執行讀檔、搜尋及 Git 差異查證，未執行測試、build、POST API、原生 CLI 登入或容器操作。作者所述 backend 87／BFF 16／frontend 202 與 build PASS，未由本輪獨立重跑。

已確認測試原始碼包含 `Instant` 型別、ISO 字串序列化斷言，以及 BFF `@SpringBootTest` application-context HTTP 斷言；測試存在不等於本輪已驗證其執行結果。實機 OAuth、callback 綁定位址、程序清理與正式瀏覽器 popup 行為亦未作動態驗證。

## 自我挑戰

1. **新增範圍：** 以工作目錄 diff 與 untracked 清單界定本輪範圍；兩份 DTO 另以 `git diff --no-index --numstat -- /dev/null <檔案>` 確認為新增檔。未納入既有 Repository Controller 或 SSE 技術債。
2. **前輪問題處理：** 撤下原時間戳 Minor，原因是四個欄位確已改為 `Instant`，且保留原始 ISO 文字驗證與明確字串序列化設定，並非只修改文件或測試說法。
3. **誤報排除：** 未將巢狀 record 容器當成可變 DTO，未將 auth 協定碼當成投資業務分類，也未將前端格式驗證及流程控制當成應移至 BFF 的金融計算。
4. **規範回查：** 已重新用 `grep -ran` 核對 `CLAUDE.md` 的分層／依賴反轉／BFF 規範，以及 `structure.md` §2.2、§2.3、§3.2、§7、§8。本輪無保留 finding；動態驗證不足列於「無法查證」，未據此推定架構違規。

