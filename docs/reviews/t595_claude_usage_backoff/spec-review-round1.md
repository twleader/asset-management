VERDICT: CHANGES_REQUESTED

## 判定
critical: 0   major: 1   minor: 0

## Findings

### [Major] 去重設計漏掉 Java 執行路徑的兩處全工具目錄讀取，仍會因選用 Codex 而查詢 Claude 費用

位置：`spec/design.md:14076`、`spec/requirements.md:7050`、`spec/tasks/t595_claude_usage_backoff.md:15`

證據：

本輪第 1 點要求「不能因選用Codex而查詢Claude費用」，但指定移除的探測只有 host interpret parent 的 `capabilities(fresh=True)`。既有 Java 呼叫鏈另有兩個會重新取得全工具目錄的入口：

- `backend/src/main/java/com/steven/assets/service/srpp/settings/SrppSettingsService.java:100`：提交 AI 模擬時呼叫 `ai.validateSelection(selection)`。
- `backend/src/main/java/com/steven/assets/service/srpp/settings/SrppSettingsLocalAi.java:89`：`validateSelection()` 執行 `selected(readCapabilities(),selection)`。
- 同檔 `:103`：真正解讀前，`interpret()` 再執行一次 `selected(readCapabilities(),selection)`。
- 同檔 `:50`：`readCapabilities()` 會發送 `GET /capabilities`，不是只讀 Java 已顯示的快照。
- `scripts/srpp-local-ai-bridge.py:913`、`:920`、`:924`：host 目錄快取逾期後，`capabilities()` 會透過 `pool.map(self.probe, tools)` 探測全部工具；現行目錄 TTL 為 20 秒。
- 同檔 `:735`：Claude probe 的費用證據來自 usage HTTP 查詢。

因此，使用者載入清單後，等到目錄與本輪允許的 Claude 正證據快取均逾期，再選 Codex 執行，Java 的 `validateSelection()` 就能觸發 Claude usage 查詢；數值模擬耗時較長時，`interpret()` 的第二次目錄讀取也有同樣問題。只移除 Python parent 的 fresh 探測無法消除這兩條路徑。選 Claude 時，也可能在 Java 觸發的 metadata 查詢後，再由 worker 做一次必要的 fresh proof。

被違反的出處：Task 595 第 1 點「不能因選用Codex而查詢Claude費用」及「Claude僅一次即時費用proof通過後才infer」；第 7 點要求驗證「只查所選工具一次」。

修法：在三份 spec 的第 1 點明確納入 Java `validateSelection()`／`interpret()` 的目錄讀取調整，定義執行路徑如何只驗已顯示的 catalog 快照與 selection，缺少快照或版本不符時要求重載；一般清單重載才可觸發全工具 metadata 探測。保留 host parent 的 registry 身分核對，以及 worker 對所選工具的即時模型／effort／費用硬閘。補上跨 Java→host 的合成測試：先載入清單，再推進時鐘超過兩種 TTL，選 Codex 時 Claude usage 呼叫數必須為零；選 Claude 時只有 worker 的一次 fresh usage 查詢。

## 已查無發現

- **機械檢查**：沿用提供的 `spec-check.sh` 輸出，`BLOCK: 0`、`CHECK: 0`；未重跑，也未將機械通過當成語意正確。
- **可查證性**：以 `rg -an` 核對 `capabilities(fresh=True)`、parent／worker 流程、usage 固定端點、20 秒目錄快取、7.5 秒 Claude metadata deadline，以及既有安全碼白名單，除上述呼叫鏈遺漏外，無其他發現。未將尚待新增的 `AI_RATE_LIMITED` 或退避實作視為不存在的既有功能。
- **內部一致性**：比對 requirements、design 與 t595 的七點契約，無另行發現互相衝突的規則。
- **資料來源**：本輪未新增行情來源、DB schema 斷言或排程時點；未使用 changelog 推定現況。無發現。

架構鐵則逐條核對：

1. Controller 直接讀 Repository、外部 HTTP 或領域判斷：本輪未描述此類變更，無發現。
2. DTO 使用不可變 `record`：本輪明示不改 BFF DTO，未提出 `@Data`／setter，無發現。
3. 新頁面同時新增專屬 BFF：本輪沒有新增頁面，不適用。
4. 跨頁 BFF、直打 business API 或裸 `fetch`／`EventSource`：本輪未提出，無發現。
5. 自創共用／慣例例外：已對照 `CLAUDE.md` 與 `structure.md` 3.2，未提出新例外，無發現。
6. 同義值使用同一 business API：本輪未新增另一套同義資料入口，無發現。
7. 前端直連 business、business 直連外部行情、external-materials 反向呼叫 business：本輪未提出，無發現。
8. FK／字串冗餘或持久化衍生業務值：本輪無 DB 變更；私有負向退避狀態用途已明示，無發現。
9. 新業務分類入 DB／seed／settings／設定頁：新增的是閉合安全協定碼，非可管理業務分類，無發現。
10. endpoint method 與 kebab-case：本輪未新增 endpoint，無發現。

## 自我挑戰

- **保留上述 Major**：已重新區分「未來功能尚未實作」與「設計漏掉既有呼叫端」。本 finding 不要求現在已有退避程式碼，而是指出依目前明列的 parent 調整仍會留下可由 TTL 逾期觸發的全工具探測；證據來自兩處 Java 實際呼叫與 host 快取逾期分支。60 秒正證據快取只能降低頻率，不能保證選 Codex 時零 Claude usage 查詢。
- **未將跨程序單飛與 fresh proof 列為矛盾**：協調鎖可以序列化查詢，而每個 worker 仍各自取得 fresh proof；不必推定它會重用成功授權。
- **未將真服務仍回 429 列為缺陷**：契約已要求尊重冷卻，並分開報告合成測試與實際未恢復狀態；不能據此推定作者會偽造可用。
- 本輪僅做唯讀靜態查證，未修改檔案、執行測試、呼叫 API 或操作部署。