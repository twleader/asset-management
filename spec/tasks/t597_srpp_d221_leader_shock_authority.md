# [t597] D221 半導體龍頭事件下限與 D204 費半連動的 SRPP 權威同步

**對應 Requirements:** Requirement 211
**前置任務:** Task 591（D220 權威同步）
**Liquibase changeset:** 無

## 背景

2026-11-13 黃仁勳請辭，SRPP 兩軌讀到官方公告與大量許可新聞，卻因半導體分級只認週期／競爭／展望／製程／市占事件而記 0～1 分；D204 美股→台股只看 IXIC（-0.91%），未看同日 SOX -3.43%。使用者於 2026-11-16 核准 SRPP D221 第一～三節：半導體類別新增龍頭重大事件下限（十家固定名單、三種事件、5 個台股交易日窗口，公告後 SOX 任一完成日 ≤ -3% 或連兩日累計 ≤ -5% 升至 2 分），D204 美股→台股改評 IXIC 與 SOX 取較低分。六項類別、上限、5／8／3／4 門檻與交易規則不變。最終 SRPP bundle `727be98b36a55a1127af02f4002c6797ab12cae56e603d466e7060e6f7d62d27`（SRPP feature `claude/risk-assessment-gap-97ab3c` commit `dd59b1c`）。

## 要做什麼

1. 精確複製最終 SRPP `assumptions/model_inputs.json` 與 `assumptions/policy_manifest.json` 原 bytes 至 `backend/src/main/resources/srpp/decision/`。
2. 以 `check-srpp-report-authority.py` 的 `expected_formal_document` 驗證 `d201-policy-document.json`（預期不變，因配置目標未改）；以 `ruby scripts/srpp-report-policy-register.rb` 原 stdout 重產 `d201-registration.sql`（先寫暫存、非空才移入）。
3. 依序以原 generator 重產 `report-same-input-golden.json`、`report-long-short-exit-golden.json`、`report-state-topup-golden.json`、short-us golden；預期只有 `policyBundleSha256` 改變，案例數與 `originalCalculatorHashes` 不變（`cross_market_context.py`／`leader_shock.py` 不在 9090 計算器集合）。
4. 以 SRPP `scripts/export_settings_runtime.py --destination <asset>/srpp-simulator/runtime` 匯出並 `--check`；`generate-srpp-formula-v3.py --srpp-root <SRPP> --check` 必須維持 PASS。
5. 確認 `SRPP_RISK_RUBRIC_V2` rubricSha256 與發布前相同（`5ca0f8fd3b82efd5c91e2f40dbe56045c9909ecc4cd966d073d932203bdb1c0b`）。
6. 執行相關 Java 離線回歸；若斷言綁定舊 decision／version，只改為同版來源取值，不改正式演算法。
7. 不新增 HTTP／DTO／DB／行情／排程／報告／交易／郵件。發布依 CLAUDE.md Task 504 鏈：雙 repo commit／no-ff merge／push 與 remote 回讀 → insert-only 登錄並回讀精確 row → 從乾淨 main 重建 business-services、srpp-simulator → 回讀 JAR authority 與 runtime bytes → SRPP host 三 GET；report-calculations 正式採用待合法時窗，否則 NOT_YET_EXECUTED。

- [x] 597.1 spec 與獨立 Codex 審查。
- [x] 597.2 authority artifacts、golden、runtime export 精確同步。
- [x] 597.3 authority check、formula V3 check、rubric hash 與 Java 離線回歸。
- [x] 597.4 獨立 Codex 架構審查、落地與發布驗收。

## 驗證

`scripts/spec-check.sh`；`check-srpp-report-authority.py --srpp-root <SRPP feature> --check`；四個 golden generator 結果；`export_settings_runtime.py --check`；`generate-srpp-formula-v3.py --check`；`mvn -o -Dtest='SrppReport*Test,SrppSettings*Test,DecisionPolicyAuthorityTest' test`。金融採用（report-calculations 200）只在合法交易日時窗驗收。

## 完成報告

離線同步、authority／formula／golden／runtime 檢查、Java 184 測例與 Codex spec／架構審查均通過，詳 `docs/reviews/t597_d221_leader_shock_authority/README.md`。已發布：SRPP main 8eac362、asset main ec0ed5bf 推送並回讀；registry insert-only 56→57；business-services／srpp-simulator 自乾淨 main 重建，JAR authority 5/5、runtime 23/23 逐位元一致；SRPP host 三 GET PASS。report-calculations 正式採用 NOT_YET_EXECUTED（待 2026-11-17 09:05–13:30）；排程入口文字尚未部署，詳 release-20261116/deployment-proof.json。
