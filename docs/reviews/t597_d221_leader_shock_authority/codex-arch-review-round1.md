VERDICT: PASS

## 判定

critical: 0   major: 0   minor: 0

審查範圍：分支 `claude/srpp-d221-leader-shock`，以 `HEAD 96bf862383a7657d14d07570bec12079bcaf6453` 對目前工作樹的 diff 為準；共 12 個已追蹤檔案、242 行新增／190 行刪除，與提供材料一致。另讀取未追蹤的 Task 597 任務檔及現有驗證紀錄作為背景，未將 spec 本身的一致性納入架構 findings。

已完整讀取 `CLAUDE.md` 與 `spec/steering/structure.md`。本輪未修改檔案、執行 SQL、呼叫 API、重建容器或進行 Git 寫入。

## Findings

無。本次 diff 未發現新增或擴大、且能由指定兩份規範明文支持的架構違規。

## 已查無發現

| 檢查維度 | 實際查證與結果 |
|---|---|
| 模組界線 | 執行 `git diff --name-only HEAD -- backend/src/main/java bff external-materials-service frontend db backend/src/main/resources/db scripts srpp-simulator/src`，輸出為空。本次沒有新增外部行情呼叫、服務反向依賴、跨服務讀表或券商交易程式碼；對照 `structure.md` §8。 |
| 分層職責 | 實際變更限資源檔與文件，沒有 Controller、Service、Repository、Entity 或 DTO 變更。因此未新增 Controller 直接讀 Repository、Service 組 HTTP response、Entity／Repository 業務邏輯，或 DTO 可變性問題；對照 §2.2。 |
| BFF 與前端 | `bff/`、`frontend/` 均無 diff。沒有新增頁面、跨頁 BFF 呼叫、裸 `fetch()`／`EventSource`、前端衍生計算或序列載入 panel；本輪不觸發新增頁面專屬 BFF 的要求。 |
| 資料模型、命名與 migration | 無 Entity、DTO、endpoint 或 Liquibase changeset 變更；沒有新增持久化金額／日期欄位、冗餘資料欄位或 Java 業務 enum。新增的事件代碼與公司清單是同步來源的政策資料，不能僅因清單固定就判作 Java enum 寫死。 |
| 資料來源 | 無行情讀寫與快取程式變更，未新增即時價、收盤價或缺價 fallback 路徑。JSON 中的 SOX 評分門檻不是持久化／傳輸的金額欄位，不適用金額必須使用 `BigDecimal` 的判準。 |

另對本次實際變更的權威產物完成以下核對：

- **來源與副本一致。** 以唯讀 Python 比較，backend 的 `model_inputs.json`、`policy_manifest.json` 與指定 SRPP worktree（commit `dd59b1c6584da7923df5c422c50ed90e213bf123`）原 bytes 相同，與 simulator 副本也相同。Manifest 所列 224 個來源檔案雜湊全部吻合。
- **新增政策內容位置符合材料。** `grep -ran` 查得 `model_inputs.json:745` 的 `"additional_source_indices": ["SOX"]`、`:746` 的 `"combine": "LOWEST_SCORE"`；語意比較確認新增 `execution_policy.semiconductor_leader_shock_d221`。沒有伴隨 Java／BFF／前端計算實作。
- **風險結構未變。** 對 `git show HEAD:<model路徑>` 與工作樹 JSON 做結構比較，六類風險代碼、各類 `max` 與完整 `risk_score_decision_policy` 均相同；5／8／3／4 門檻維持原值。
- **Registry SQL 維持 insert-only。** `d201-registration.sql:1` 與 HEAD 比較，只有 bundle hash 替換；仍為 `INSERT … ON CONFLICT (policy_bundle_sha256) DO NOTHING`，符合 `CLAUDE.md`〈Task 504 受控 SRPP identity／hash／runtime 發布鏈〉。僅讀取及比較，未執行 SQL。
- **Golden 沒有改案例內容。** 同輸入 golden 的 111 案、long-short-exit golden 的 15 案，結構差異都只有 `/policyBundleSha256`；案例與 calculator hashes 未變。State-topup 的 22 案及 short-us golden 沒有 diff。
- **Formula V3 實測通過。** 執行原工具 `generate-srpp-formula-v3.py --srpp-root <指定來源> --check`，回傳 `SRPP_FORMULA_V3_CHECK_PASS:452d42608d6357c8e7ddd82ff03e91b3fb82d1c56978e029fd4717cfc60f90fb`。
- **Runtime 匯出實測通過。** 原 `export_settings_runtime.py --destination <本工作樹>/srpp-simulator/runtime --check` 回傳 `status: PASS`、`files: 23`，bundle 為 `727be98b36a55a1127af02f4002c6797ab12cae56e603d466e7060e6f7d62d27`。22 個內容檔雜湊亦逐一吻合 manifest。
- `git diff --check` 無輸出。

## 無法查證

本輪嘗試執行原 `check-srpp-report-authority.py --check`，在其 golden generator 階段因唯讀沙箱無可用暫存目錄而失敗：

```text
SRPP_REPORT_AUTHORITY_CHECK_FAIL:GOLDEN_GENERATOR_FAILED:
FileNotFoundError: [Errno 2] No usable temporary directory found …
```

因此本輪**未獨立重現完整 authority gate 與 golden 計算器重播 PASS**；前述 golden 結論限於工作樹與 HEAD 的內容比較。此為執行環境限制，沒有證據支持將它列為本次架構 finding。

本輪未重跑會產生檔案的 Maven 測試，也未驗證運行中 DB、正式 registry row、JAR 或合法時窗的 report-calculations 採用狀態。架構審查的 PASS 不代表上述發布驗收已完成。

## 自我挑戰

1. **是否把既有債算入？** 所有結論均先由 `git diff` 限定範圍；未將既有 Controller 直接注入 Repository、既有 SSE 路徑或其他全樹問題列入。
2. **是否踩到誤報陷阱？** 本次沒有 DTO 變更；未以外層 class 或註解中的 `@Data` 判違規，也未把 SOX 百分比門檻當成金額欄位。
3. **規範是否確有明文？** 已用 `grep -ran` 回查兩份規範的分層、BFF、金額型別與 Task 504 insert-only 要求。歷史記憶僅協助定位權威核對項目，判準仍限本輪讀取的兩份文件。
4. **是否因缺少新計算器而誤報？** 未將 simulator 沒有新增 `leader_shock.py` 列為缺陷：本次沒有新增該計算的程式呼叫，既有 runtime exporter 的完整 23 檔檢查通過；指定規範也未要求把 SRPP 每支報告計算器都放入 simulator。
5. 無需撤回的正式 finding；暫存目錄失敗保留為查證限制，未升格為架構違規。

