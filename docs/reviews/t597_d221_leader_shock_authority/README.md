# Task 597 D221 半導體龍頭事件下限權威同步

D221、policy 2.69.0／model 3.24.0，最終 bundle `727be98b36a55a1127af02f4002c6797ab12cae56e603d466e7060e6f7d62d27`（SRPP feature `claude/risk-assessment-gap-97ab3c` `dd59b1c`）。

- 精確同步 model_inputs／policy_manifest 原 bytes；canonical policy document 不變；registry SQL 由 Ruby 原 stdout 重產，仍為 insert-only（只換 bundle hash）。
- golden：same-input 111、long-short-exit 15、state-topup 22 案均 match，只有 `policyBundleSha256` 改變；short-us 13 案 bytes 不變。
- srpp-simulator runtime 共 23 檔（含 runtime-manifest）以 SRPP exporter 匯出並 `--check` PASS。
- formula V3 `452d4260…` check PASS；`SRPP_RISK_RUBRIC_V2` rubricSha256 前後皆 `5ca0f8fd3b82efd5c91e2f40dbe56045c9909ecc4cd966d073d932203bdb1c0b`。
- Java 離線：SrppReport*／SrppSettings*／DecisionPolicyAuthorityTest 等 25 類 184 測例 0 失敗；斷言皆讀同版來源，無需修改 Java（`mvn-srpp.log.gz`）。
- 獨立 Codex spec 審查第 1 輪 PASS（1 minor 已修）、架構審查第 1 輪 PASS（0/0/0）。

D221 下限與 D204 SOX 連動由 SRPP 本機計算器在保存證據上計算；9090 不計算、不新增 wire。正式部署與 9090 驗收收據見後續 release 目錄。
