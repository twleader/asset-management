VERDICT: PASS

## 判定
critical: 0   major: 0   minor: 1

審查範圍：第 1/3 輪，僅審 Task 596。以 `HEAD=014daf49c9fe763e00a55e26e4e7657eedf3efd1` 對照工作目錄，並納入本輪未追蹤的新檔。已核對 backend／BFF 的 auth controller、service、port、adapter、DTO、Advice／Filter，前端 API、登入元件與工具函式，以及 Python bridge／auth manager 和所附測試。規範依據僅為 `CLAUDE.md` 與 `spec/steering/structure.md`；未將既有技術債列入 finding。

## Findings

### [Minor] 新增登入 DTO 的時間戳欄位使用 String，未遵循 Instant 型別慣例

位置：

- `backend/src/main/java/com/steven/assets/dto/SrppClaudeAuthDto.java:12`、`:13`
- `bff/src/main/java/com/steven/assets/bff/portfolioadvice/SrppClaudeAuthDto.java:12`、`:13`

證據：對上述兩檔執行 `grep -ran -E 'public record (AuthLocalState|AuthFlow)'`，兩檔均得到：

```java
12: public record AuthLocalState(int schemaVersion,String toolId,String loginState,String expiresAt,String code){}
13: public record AuthFlow(int schemaVersion,String flowId,String toolId,String status,String authorizationUrl,String expiresAt,String code){@Override public String toString(){return "AuthFlow[redacted]";}}
```

另以 `git diff --no-index -- /dev/null <檔案>` 確認兩檔均為本輪新增，這四個時間戳欄位並非既有實作。

違反：`structure.md` §7，實際 `grep -ran` 結果為：

```text
622:| 時間欄位 | `LocalDate`（日期）/ `Instant`（時間戳）；wall-time 帶市場時區由 service 層處理 |
```

修法：將 backend 與 BFF 的 `AuthLocalState.expiresAt`、`AuthFlow.expiresAt` 改為 `Instant`；同步調整解析器、比較與測試。JSON 仍維持 UTC ISO8601 字串及原有 nullable 語意，保留目前嚴格的輸入格式驗證。

目前兩邊已有 `Instant.parse()` 與狀態一致性驗證，未據此認定存在資料正確性破壞，因此列為 Minor。

## 已查無發現

- **模組界線：** 對本輪新增 backend controller／service 執行 `grep -ran`，檢查外部行情網址、`JdbcTemplate`、`EntityManager`、Repository、Redis 與券商交易呼叫，無命中。本輪亦未修改 external-materials，未新增反向相依或跨服務資料表存取。
- **分層職責：** 兩個 auth Controller 均只接收參數、委派 Service 與包裝 HTTP 回應。backend Service 依賴 `SrppClaudeAuthPort`，HTTP／token-file I/O 位於 `SrppClaudeAuthLocal`。對新增 backend service 搜尋 `ResponseEntity|HttpServletResponse` 無命中。
- **DTO：** `grep -ran -E 'record|^\s*@Data\b'` 確認兩個 DTO 外層 class 都是巢狀不可變 record 的容器，沒有真正的 `@Data` 標註，也沒有 JPA 註解或 Entity 參照。
- **BFF 與前端：** 新增五種登入呼叫均經 `api/index.js` 的 `bffApi.portfolioAdvice.srpp.claudeAuth`，使用 `/bff/portfolio-advice/srpp/ai-auth/claude/...`。對登入元件與工具函式搜尋 `fetch\(|new EventSource` 無命中；未新增跨頁 BFF 呼叫或金融聚合計算。新增掛載時的 root／auth 查詢並行啟動，沒有新增多 panel 序列等待。
- **owner／CSRF 邊界：** 靜態查證 BFF 從 OIDC principal 還原身分，檢查本人 effective owner，再於 POST 的 Origin／nonce 驗證後查 fresh configured-admin。binding 與 nonce 由 WebSession 產生；下游明確帶本人 headers 並刪除 Reactor tenant context。backend 另驗 configured-admin、ACTIVE／ADMIN 與 effective owner。
- **敏感資料處理：** 對新增 Java auth 檔與 Python manager 搜尋日誌／輸出呼叫，未找到敏感內容輸出路徑；前端登入程式未命中 `console`、`localStorage` 或 `sessionStorage`。已確認專用安全 Advice、敏感 DTO 的遮蔽 `toString()`、BFF ingestion 排除，以及 host handler 的停用 request logging 與 `no-store` 回應。
- **資料模型與資料來源：** 本輪沒有 Entity、Repository、DB schema 或 Liquibase 變更，也未新增持久化／傳輸的金額欄位、投資業務分類或行情來源。登入狀態與安全碼屬閉合通訊協定，未當成可管理的投資業務分類回報。
- **過期前置檢查：** bridge 的 `claude_expiry()` 呼叫位於取得 credential 後、usage HTTP 前；既有 cooldown 優先順序仍保留。登入成功的失效處理只清記憶體 metadata／目錄快取，未見刪除持久 backoff 的新增程式。

## 無法查證

本輪依唯讀限制未執行測試、build、POST API、原生登入或容器操作。材料所述 Python 125／backend 87／BFF 16／frontend 202 與 build 通過，屬作者提供的驗證結果，本報告未獨立重跑。

實機 OAuth、callback 實際綁定位址、程序清理、正式瀏覽器流程及 runtime 日誌內容，未作動態驗證；上述查無發現限於程式碼靜態查證。

## 自我挑戰

1. **新增範圍確認：** 保留的 finding 已用兩次 `/dev/null` 新檔 diff 確認。既有 Repository Controller 與 SSE 基準線未計入。
2. **誤報排除：** 已排除「DTO 外層 class 不是 record」誤報；兩檔實際承載巢狀 records。未將閉合 auth 狀態碼誤認為投資業務分類，也未將前端格式驗證與流程控制當成金融衍生計算。
3. **規範回查：** 時間型別 finding 已重新以 `grep -ran` 核對 `structure.md:622`。ISO 字串驗證降低了格式風險，但未改變 Java 欄位仍為 `String` 的事實，因此保留為 Minor；沒有提升為 Major 或 Critical 的證據。