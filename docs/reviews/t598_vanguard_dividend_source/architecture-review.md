VERDICT: PASS

## 判定

critical: 0   major: 0   minor: 0

審查範圍：`HEAD c263aeab45804e4e91292f5785916ef1cda70eba` 至目前工作樹的 Task 598 變更，包含 tracked diff 與 untracked 新檔；staged diff 為空。實際檢視：

- `CanonicalDividendResolver.java`、`DividendFetchClient.java`。
- 新增 `VanguardDividendClient.java`、`service/port/VanguardDividendSource.java`。
- 新增兩支測試、Vanguard fixtures 與 provenance。
- `spec/` 變更僅用於確認任務範圍，不重做 spec 審查。
- 額外讀取既有 `DividendData`、`DividendNormalization` 作為型別與基準線查證。

## Findings

無。本輪未確認任何本次 diff 新增或擴大的架構違規；前輪唯一 Minor 已解決。

## 已查無發現

**前輪 port 放置問題：已解決。**

以 `grep -ran -E 'VanguardDividendSource|VanguardDividendClient' external-materials-service/src/main/java` 查得：

```text
…/service/port/VanguardDividendSource.java:8:public interface VanguardDividendSource {
…/client/DividendFetchClient.java:37:    private final com.steven.assets.externalmaterials.service.port.VanguardDividendSource vanguard;
…/client/DividendFetchClient.java:42:                               com.steven.assets.externalmaterials.service.port.VanguardDividendSource vanguard) {
…/client/DividendFetchClient.java:67:                        com.steven.assets.externalmaterials.service.port.VanguardDividendSource vanguard) {
…/client/VanguardDividendClient.java:6:import com.steven.assets.externalmaterials.service.port.VanguardDividendSource;
…/client/VanguardDividendClient.java:21:public final class VanguardDividendClient implements VanguardDividendSource {
```

介面自身的查證結果為：

```text
…/service/port/VanguardDividendSource.java:1:package com.steven.assets.externalmaterials.service.port;
…/service/port/VanguardDividendSource.java:3:import com.steven.assets.externalmaterials.model.marketdata.DividendData;
…/service/port/VanguardDividendSource.java:5:import java.time.LocalDate;
…/service/port/VanguardDividendSource.java:10:    DividendData.DividendFetchResult fetch(String code, LocalDate from, LocalDate to);
```

`ls …/client/VanguardDividendSource.java` 回傳 `No such file or directory`，沒有留下舊位置的重複介面。符合 `structure.md` §4.1 的 `service/port/` 目錄規定及檔首 Task513 修訂；回傳值來自中立 `model/marketdata`，符合 `CLAUDE.md`「Clean Architecture 原則」的依賴反轉要求。

| 維度 | 查證方式與結果 |
|---|---|
| 模組界線 | 以 `git diff`、新增檔全文及 `grep -ran` 查 URL、反向服務引用、Repository／JDBC／EntityManager。新增官方 URL 僅位於 external 的 `VanguardDividendClient.java:23`；backend 僅於 `CanonicalDividendResolver.java:284` 新增來源順位。未新增 business 外呼、external 反向呼叫 business、跨服務讀表或券商操作，符合 `structure.md` §8。 |
| 分層職責 | 本次沒有 Controller、Repository、JPA Entity 變更；新增 adapter 負責官方資料抓取與格式驗證，沒有使用者持倉或 snapshot 判斷。未新增 Service 組裝 `ResponseEntity`／`HttpServletResponse`，符合 §2.2、§4.2。 |
| DTO 與依賴方向 | port 使用既有不可變 `DividendData.DividendFetchResult`；新增 `Product`、`Document` 亦為 record。未新增可變 DTO、JPA 註解或 entity 參照，符合 §2.2。 |
| BFF 與前端 | `git diff --name-only HEAD` 與 untracked 清單皆無 BFF／frontend 變更。因此新增頁面專屬 BFF、跨頁呼叫、裸 `fetch`／SSE、前端計算及多 panel 並行要求，本次均不適用。 |
| 正規化與分類 | 未新增持久化欄位、FK／字串冗餘或衍生值儲存。VOO／VT 映射及 INC／CGST／CGLT／ROC 是 adapter 的商品識別與上游協定碼，未新增可管理的投資業務分類。 |
| 金額與日期 | `grep -ran` 確認 `VanguardDividendClient.java:32` 啟用 `USE_BIG_DECIMAL_FOR_FLOATS`、`:74` 使用 `BigDecimal`，`:71` 以 `LocalDate` 驗證日期。沿用既有 DTO 的 ISO 日期字串；`git show HEAD:…/DividendData.java` 證實此傳輸型別原已存在。 |
| API、命名與 migration | 無新 endpoint、資料表、欄位、enum 或 Liquibase changeset；API 命名、軟停用、changeset 撞號／冪等／註冊檢查均無本次適用項目。新 port package 與 adapter 放置符合 §4.1。 |
| 資料來源 | 本次只新增歷史配息來源，沒有即時價／收盤價讀寫變更，也沒有以預設值或 `o`／`y` 補價。adapter 於 `:83` 回傳 `PARTIAL`；`DividendFetchClient.java:101–109` 的 upcoming 完整性判定保持既有邏輯。 |

另以 `grep -ran -E '<testsuite '` 唯讀檢視既有 Surefire XML：external 為 `14 + 15 + 6 + 2 + 9 = 46`，backend 為 `4 + 18 + 2 = 24`，各 suite 的 errors、failures、skipped 均為 0，與更正後的 70 筆紀錄一致。

## 無法查證

- 本輪未執行 Maven 或其他測試；上述數量為既有 XML 收據，不代表本輪重新執行，也不能單憑 XML 證明測試綁定目前全部檔案內容。
- 未連線官方來源、運行中 DB 或 9090，未執行同步、preview／apply 或部署。因此不對正式資料、fixtures 的官方原文真實性及部署後 readback 作驗收結論。本次無 schema 變更，未據此提出 schema 漂移 finding。

## 自我挑戰

1. **是否只審新增變更：**以 `git diff` 確認 tracked 變更，並以 `git ls-files --others --exclude-standard` 補齊新增檔。未將既有 Controller 注入 Repository 或 SSE 基準線列入 findings。
2. **是否踩到誤報陷阱：**未以外層 class 判定 DTO 非 record；未把註解中的 `@Data` 當標註；未將 fixtures 的 JSON 數字誤認為 Java `double` 金額欄位。
3. **規範是否明文存在：**已重新查證 `CLAUDE.md:309–314` 的依賴方向與依賴反轉，以及 `structure.md:364` 的 port 放置規定。前輪 Minor 因實際搬移且引用完成更新而結案。
4. **可疑項目是否屬既有契約：**配息日期字串經 `git show HEAD` 確認為既有 DTO 契約，本次僅沿用，不列新增違規；封閉商品映射與上游 type code 亦不擴張解讀為必須提供設定頁的業務分類。

本輪沒有保留、撤下或降級的新增 finding。