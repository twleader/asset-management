VERDICT: PASS

## 判定
critical: 0   major: 0   minor: 1

## Findings

### [Minor] Runtime 檔數措辭把已包含的 manifest 寫成額外一檔

位置：`spec/requirements.md:7020`（211.3）。

證據：

- 原文為「同步 `srpp-simulator/runtime` **23 檔與 runtime-manifest**」，容易解讀為總共 24 檔。
- 指定 SRPP feature worktree 的 `scripts/export_settings_runtime.py:10` 列出 22 個 `SOURCES`；第 16 行另外加入 `runtime-manifest.json`；第 34 行回傳的 `files=len(payload)` 已包含 manifest，因此總數為 23。
- 本 repo 執行 `rg --files srpp-simulator/runtime | rg -c '.'`，結果亦為 `23`。

被違反的出處：本輪審查判準「內部一致性／計數漂移」；原 exporter 的檔數定義已包含 manifest。

修法：將 211.3 改為「同步 `srpp-simulator/runtime` 共 23 檔（含 `runtime-manifest.json`）」，不修改 exporter 或白名單。

## 已查無發現

- **機械證據**：採用提供的 `spec-check.sh` 結果：`BLOCK: 0、CHECK: 0`，未重跑。
- **可查證性**：以 `rg`、`cat` 查到 `expected_formal_document`、authority checker、Ruby registry generator、四組 golden generator、formula V3 generator、runtime exporter、`SrppRiskRubricV1/V2` 與 `DecisionPolicyAuthorityTest`，無虛構識別字發現。
- **來源身分與語意**：指定 SRPP feature worktree 的 `assumptions/policy_manifest.json:3–5、232` 與材料所列 D-221／2.69.0／3.24.0／完整 bundle hash 一致；相關來源相對 `dd59b1c` 的 `git diff` 無差異。`leader_shock.py` 與 `cross_market_context.py` 的存在、事件下限及取較低分行為可查證，無發現。
- **Rubric 與公式界線**：`SrppRiskRubricV2.java:23–24` 的雜湊文件包含類別代碼、上限及決策門檻，未納入評分敘述；`SrppSettingsRiskV2Test.java:9` 所列 hash 與 Task 597 一致。formula V3 的來源集合亦未包含兩支 D221 報告端腳本，無發現。
- **發布一致性**：對照 `CLAUDE.md:506–512`，Task 597 保留雙 repo 落地、insert-only registry、乾淨 main 重建、authority 回讀及合法時窗採用的順序，無發現。

架構鐵則逐條核對本次 spec diff：

| 檢查項 | 結果 |
|---|---|
| Controller 直讀 Repository、外部 HTTP、upsert 或領域判斷 | 否，無此設計變更 |
| 新增或修改 DTO 違反不可變 record | 不適用，明定不新增 DTO |
| 新頁面缺專屬 BFF | 不適用，無新頁面 |
| 前端跨頁 BFF、直打 resource 或繞過 `api/index.js` | 否，無前端呼叫變更 |
| 自創跨頁共用例外 | 否 |
| 同義值改走不同 business API | 否，無 API 來源變更 |
| 前端直連 business、business 直連行情或 external 反向呼叫 | 否 |
| FK／字串冗餘或新增可計算衍生欄位 | 否，無 schema 變更 |
| 新業務分類缺 DB／seed／設定管理 | 不適用，未新增資產端業務分類 |
| 新 endpoint 違反 method／命名規則 | 不適用，明定不新增 endpoint |

資料來源方面，本次未引入新行情讀取路徑，也未對 DB 表、欄位或索引形貌提出新斷言；無發現。

## 無法查證

提供的離線 dry-run PASS、案例數與 Java 測試結果屬呼叫端證據，本輪未重新執行 generator、Maven 或部署驗收。尚未同步的 authority/runtime 是本任務預定工作，不據此判為「規格宣稱已完成卻未實作」。

## 自我挑戰

- **保留檔數 Minor**：原句可能只是把 manifest 特別點名；但 exporter 的 `files` 明確包含它，改成「共 23 檔，含 manifest」可消除歧義。既有原工具與 `--check` 已約束產物集合，因此不升為 Major。
- **撤下「評分敘述改變就必須改 rubric hash」疑點**：實查 Java 雜湊文件後，確認評分敘述不在雜湊範圍；維持原 hash 有程式證據支持。
- **撤下「runtime 缺 `leader_shock.py`」疑點**：本任務明定由 SRPP 報告端計算，既有 exporter 是情境模擬 runtime 的固定集合；沒有證據要求將報告端腳本加入資產端。
- **不把 golden 欄位差異列為問題**：四組產物並非全部具有 `policyBundleSha256`／`originalCalculatorHashes`；Task 597 的敘述可合理解讀為重產後允許的整體差異，不能據此認定要求新增欄位。
- 歷史記憶僅用來定位 Task 504 發布鏈，判斷已以本輪 `CLAUDE.md` 重新查證。

