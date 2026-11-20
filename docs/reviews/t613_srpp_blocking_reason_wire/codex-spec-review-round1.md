VERDICT: CHANGES_REQUESTED

## 判定
critical: 0   major: 1   minor: 0

## Findings

### [Major] 驗證清單引用不存在的測試類，已觸發 spec-check BLOCK

位置：`spec/tasks/t613_srpp_blocking_reason_wire.md:21`

證據：本輪提供的機械結果已明確指出：

```text
BLOCK │ spec 提到測試類 SrppReportBlockingReasonsTest，但目前 checkout 的受控測試根目錄找不到 SrppReportBlockingReasonsTest.java/.js —— 宣稱的驗證不存在（Task 160 前例）
```

任務第 21 行將 `SrppReportBlockingReasonsTest` 列入「實作後執行」清單。`scripts/spec-check.sh:250–258` 會檢查 spec 新增行中的具名測試類，沒有依「實作後」措辭豁免的分支，因此目前這份 spec 無法通過既定機械閘門。

被違反的出處：本輪審查判準鐵則第 3 條要求直接納入已提供的 BLOCK；`CLAUDE.md:39–42` 要求審查前檢查宣稱的測試類是否存在。

修法：在本輪 spec 階段，將第 21 行的具名執行清單限定為目前存在的測試類；第 613.4 項繼續保留新增單元測試與全部案例要求。待實作建立真實測試檔後，再同步補上精確類名與執行命令。不得以空測試檔或放寬 B5 消除此 BLOCK。

## 已查無發現

- **可查證性：**以 `rg --files`、`rg -an` 核對 publisher、既有測試類與 validator，除上述 BLOCK 外無發現。`SrppReportPublisher.java:53–54`、`:75–82` 確有 shared calculation、consumer compose 與後續 canonical/hash/insert 流程。
- **內部一致性：**比對 `spec/requirements.md:6095`、`spec/design.md:14282` 與 t613 第 13–17 行，去重範圍、首次出現順序、保留收據及不可變產物限制一致，無發現。
- **既有 identity 機制：**`SrppReportBuildIdentity.java:17–18` 由部署產物計算身分；`SrppReportAuthority.java:56` 納入 `serviceBuild`；publisher 第 47–48 行先加入 authority 再計 fingerprint。沿用此機制區分新產物的描述有實作依據，無發現。
- **BFF 契約：**`SrppReportResponseValidator.java:205` 對 `blockingReasons` 呼叫 `texts()`；第 315 行拒絕非字串、空字串及重複值；第 159–160 行驗證 content hash。規格保留原有拒絕行為，無發現。
- **資料來源正確性：**本輪沒有新增行情讀取或 schema 形貌斷言；提供的 B10 結果為 `db/schema.sql` 與運行中 DB 全文一致。無發現。

架構鐵則逐項核對如下；判定限於本輪 spec diff：

| 檢查項 | 結果 |
|---|---|
| Controller 直接注入 Repository、外部 HTTP 或領域判斷 | 否；變更定位於 publisher adapter。 |
| 新增／修改 DTO 是否使用不可變 record | 不適用；未提出 DTO 變更。 |
| 新頁面是否同時新增專屬 BFF | 不適用；未新增頁面。 |
| 前端跨頁 BFF、直打 resource API 或繞過 api/index.js | 否；未提出前端呼叫變更。 |
| 自創跨頁共用例外 | 否。 |
| 同義值是否改走不同 business API | 否；未變更取值 API。 |
| frontend 直連 business、business 直連行情或反向服務依賴 | 否。 |
| FK／字串冗餘或新增持久化衍生值 | 否；未新增欄位或資料模型。 |
| 新業務分類是否缺 DB、seed、設定 API 與頁面 | 不適用；未新增業務分類。 |
| 新 endpoint 的 method／path 命名 | 不適用；未新增 endpoint。 |

## 自我挑戰

- **保留上述 Major，但不將它解讀為「新功能尚未實作」的缺陷。**第 21 行確實寫「實作後執行」，屬未來驗證計畫；然而本輪判準明定已提供的 BLOCK 必須納入，且 B5 目前確實沒有未來測試名稱的豁免。問題是 spec 與現行閘門不相容，因此列 Major，未升為對現況錯誤斷言的 Critical。
- **撤下「新 build identity 無法區分新包」疑慮。**已查到 artifact hash → authority revision → fingerprint 的完整既有鏈路，無理由要求新增政策版本或 DB 欄位。
- **未把新 wire adapter 尚不存在列為 finding。**本輪屬實作前規格審查，這是合法的未來設計。
- **未要求去重 gate receipts。**兩個 calculator 的 `gate()` 分別逐次追加阻擋原因與收據；相同原因可對應不同 gate。規格只去重原因字串並保留收據，未發現矛盾。