# [t618] D-229補充：D-212修正窗口債券下限5%的SRPP規則包身分發布

**對應 Requirements:** Requirements 187／193／199（不可變報告計算、正式authority與美股核心計算）
**前置任務:** t617
**Liquibase changeset:** 無

## 背景

Steven 2026-11-21指定D-212修正窗口賣債後的非緊急債券下限與D-224熊市一致為5%（原設定`bull_correction_dip_d212.total_bond_floor`為0.03、散文為10%，互相矛盾）。SRPP只改該設定值與相關散文，並重建bundle；這是唯一的投資參數變更，只讓修正窗口可賣債金額更保守。9090不路由熊市／修正窗口（由SRPP `scripts/correction_dip.py`本機計算），資產端Java計算器、DTO、DB、排程、formula與Swagger都不變，只換規則包身分並重建business-services。

## SRPP凍結來源

root `/Users/steven/Project/SRPP/.claude/worktrees/srpp-logic-reports-61f21f`，凍結commit `4dcb429`，bundle `da3fe6e47db30a6082bd01fed00a2b95614acc4b4c599ea8d9cdd9e898dae02f`（前一版 `6ec353ec006a09b484489a26edecbdf390ac70d448e5357e6765cb07966773f2`）；Swagger位元組不變；`model_inputs.json`與`policy_manifest.json`位元組改變，須同步backend決策resource。

## 要做什麼

- [x] 618.1 同步凍結`policy_manifest.json`與`model_inputs.json`原始bytes至backend決策resource；原Ruby產d201-registration.sql，原Python重產report-same-input與long-short-exit golden（只變bundle行），authority gate、golden `--check`、Ruby registry測試、focused Java通過。
- [ ] 618.2 雙repo feature commit／main no-ff merge／push與remote讀回→已合併SQL insert-only registry（舊rows全文不變）→run-stack只build／force-recreate business-services（避開報告與匯出窗）→JAR bytes讀回並立即`--ff-only`快轉日報工作區→從SRPP實測9090。
- 審查記錄：Codex用量上限（約11-24恢復），本任務未執行spec審查，不以其他審查者代替；範圍與t617同型且只多一個使用者指定的設定值。
