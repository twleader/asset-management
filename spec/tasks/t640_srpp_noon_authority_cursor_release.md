# [t640] 午報權威讀取游標的規則包配套發布

**對應 Requirements:** Requirements 187／193（不可變日報計算包與正式規則身分）
**前置任務:** t639
**Liquibase changeset:** 無

## 背景

2026-11-27 Codex 12:00於原started_at 12:00:48起跑後約2分18秒、主體17/68批自行INCOMPLETE；source/runtime PASS且authority progress仍CONTINUE、餘約1962秒，並非工具錯誤或已到截止。使用者要求診斷修復後完整發布。本輪不同於早上版本宣告修復；所有驗收收據重新取得，原午報結果不覆寫、不補報。

## 要做什麼

- [ ] 640.1 源端唯一feature `/Users/steven/.codex/worktrees/noon-authority-cursor-20261127/SRPP` 建本機有界權威讀取游標：每call一完整batch＋END＋唯一精確next command，下call顯式ACK上一批。ACK只為LLM自述，不是閱讀證明；原子保存來源identity、已ack、原started_at/deadline，錯ack/source drift/重置延限拒絕。縮批新plan從0仍沿原started_at；startup只shared不讀downstream，core與條件pack同工具導航，不產交易授權、不補報。源端CLI細節與mutation/原子/時限/來源測試由SRPP實作者交付，不宣稱資產端已測該行為。
- [ ] 640.2 等源端最後凍結與rules/runtime/Codex/Claude/model三consumer檢查後才copy精確manifest bytes。model、Swagger、formal policy、formula V3與calculator來源hash完全不變；只更新bundle metadata，原generator產insert-only SQL與report/exit golden。111report及15exit各case金融expected全文相同，其他未改golden原bytes相同；若非metadata變化即停止查證，不擴改金融規則求PASS。
- [ ] 640.3 重新跑authority gate/4tests、formula與兩golden checks、兩Ruby registration generator測試、backend四類authority/module/settings/registry測試與offline package；本輪不得沿用早上收據。上一輪擴充D215 generator drift已證既存且本輪未修改fixture，不把它標PASS；必要gate失敗仍停止依賴動作，另存有界診斷。
- [ ] 640.4 最終spec內容定稿後獨立Codex CLI spec與arch審查/gates，外部收據記完成狀態而不在gate後改spec。兩repo feature commit、main no-ff merge、push與remote refs精確讀回後，從merged main受追蹤SQL正式insert-only registry，四欄全文比對並驗歷史rows未變。不UPDATE/DELETE舊row。
- [ ] 640.5 使用正式main設定只rebuild/recreate必要business-services，健康、新image/container、47configured env keys與實際掛載相等、無關容器保留、JAR model/manifest/Swagger/SQL全文相同逐一存證；環境鍵數以本輪實際值為準，不把歷史47當不變門檻，不輸出秘密。BFF原TTL recovery先驗，不例行restart。host9090三項維護GET由主控按Swagger執行；正式日報採用仍NOT_YET_EXECUTED，不建草稿/寄信/交易/POST或手動觸發producer。

## 驗證

以下都是本輪重新執行的資產端驗收；source游標測試結果由SRPP實作者提供，不能由資產端hash PASS取代。正式Git/SQL/部署由release operator在主控確認後執行，準備階段不改正式容器。

```sh
bash scripts/spec-check.sh
python3 -B scripts/generate-srpp-formula-v3.py --srpp-root /Users/steven/.codex/worktrees/noon-authority-cursor-20261127/SRPP --check
python3 -B scripts/check-srpp-report-authority.py --srpp-root /Users/steven/.codex/worktrees/noon-authority-cursor-20261127/SRPP --check
python3 -B scripts/generate-srpp-report-golden.py --srpp-root /Users/steven/.codex/worktrees/noon-authority-cursor-20261127/SRPP --check
python3 -B scripts/generate-srpp-long-short-exit-golden.py --srpp-root /Users/steven/.codex/worktrees/noon-authority-cursor-20261127/SRPP --check
ruby scripts/tests/srpp-report-policy-register-test.rb
ruby scripts/tests/srpp-policy-register-test.rb
SRPP_RELEASE_WORKTREE=/Users/steven/.codex/worktrees/noon-authority-cursor-20261127/SRPP python3 -B scripts/tests/check-srpp-report-authority-test.py
mvn -q -f backend/pom.xml -Dtest=DecisionPolicyAuthorityTest,SrppReportModuleCalculatorTest,SrppSettingsAuthorityValuesTest,SrppPolicyRegistryServiceTest test
mvn -q -f backend/pom.xml -DskipTests package
git diff --check
```

## 完成報告

本輪external receipts：`/Users/steven/.codex/release-receipts/srpp-noon-authority-cursor-20261127/`。完成狀態、實際hash/refs/registry/container/9090與限制記此處；最後spec gate後不為回填狀態改本檔。原午報INCOMPLETE與沒有交付結果不改寫，維護發布不代表補報或正式日報已採用。
