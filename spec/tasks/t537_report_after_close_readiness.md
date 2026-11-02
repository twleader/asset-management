# [t537] 補足新規則包收盤後首次報告計算結果

**對應 Requirements:** Requirement 187／193（不可變報告計算與規則包發布鏈）
**前置任務:** t536
**Liquibase changeset:** 無

## 背景

新bundle17d105ec於2026-11-02的23:15登錄，當日210個既有base都為舊hash；scheduler14:00後立即停止，故新hash GET必回503 CONTEXT_NOT_READY。使用者要求修復及實際成功驗證。不能藉改錯誤碼、舊包改hash、GET計算或放寬金融條件假成功。

## 要做什麼

- [ ] 537.1 保留盤中09:05<=now<12:00及12:00<=now<14:00流程；僅同日cached台股日曆true且14:00<=now<24:00，針對exact current authority／Swagger、active snapshot owner、12:00 scope缺Codex或Claude包做背景初始化。先registry與authority驗證；owner-date transaction lock內再次查兩consumer，皆存在就零capture／計算／新增。不得回補09:05或前日、不靠GET觸發。每程序每owner/date/policy/swagger scope最多3次失敗嘗試，正常盤中重試不變；新hash新scope可再試，失敗具名日誌、不回空200。
- [ ] 537.2 真實canonical persisted DB/cache capture與原calculators/publisher；不新vendor、broker、HTTP、refresh或金融來源寫入。input、facts、所有receipt使用真實capture時鐘；新增closed frozen來源publication-context：mode=AFTER_CLOSE_INITIALIZATION、tradingDate=當日、reportSlot=12:00、capturedAt=input/facts最終capture時刻，sourceVector與input/content hashes精確保護。
- [ ] 537.3 Envelope validator於發佈與讀取均嚴格核對publication-context及實際台北當日14:00後capture。此mode不接受任何ACTIONABLE_FOR_REPORT腿或任何正lots／units／limitPrice／amount，包括缺actionability status的腿；必須拒絕整包／交易rollback，不得修改計算結果、填0或刪除腿。既有盤中無marker包不變；closed／stale quote、pending／subaccount等原閘不變。真實PARTIAL／UNAVAILABLE與blocked legs可發布，FINAL只指immutable完成不指可交易。
- [ ] 537.4 增加scheduler、publisher/envelope與read服務測試：正常盤中不變、preopen／false/unknown calendar零工作、14:00邊界、晚間缺scope一次初始化、雙consumer存在skip、缺一consumer修復、鎖內recheck競態、真實時鐘與marker、3次失敗上限、新hash重試隔離、跨日不補、stale/closed不正量、正status/qty/price/amount與缺status變體拒絕、GET不capture、不重算、exact新hash讀回。
- [ ] 537.5 僅OpenAPI description新增背景初始化及晚間診斷純讀語意，wire路由/schema不變。依既有renderer→sync→renderer --check→sync --check順序同步managed SRPP feature `/private/tmp/srpp-report-after-close-20261102`，凍結最終source新hash後同步model/manifest、原RubySQL、兩goldens；公式與全部golden cases/expected不變。不得編輯primary SRPP或main，不能自行跳過工具的dirty-worktree gate。
- [ ] 537.6 實作與獨立review及測試通過後委派run-stack驗feature並檢查GET仍純讀；雙repocommit/no-ffmerge/push後從乾淨main重建business-services並insert-only登錄新hash，exact registry/JAR讀回後等背景tick產生真實包。使用者本次要求允許同日晚間read-only GET診斷驗收新hash包；核對owner/consumer/date/noon slot/policy/swagger/build/input/source/content hash、12模組與非可執行marker，保留實際PARTIAL/UNAVAILABLE及阻擋。正式金融採用仍待合法時窗；不POST、不重跑報告、不寄信、不交易。若當日午夜已過，不能偽造date或跨日回填，須如實保留阻擋。

## 驗證

```bash
bash scripts/spec-check.sh
/usr/local/apache-maven/apache-maven-3.9.11/bin/mvn -q -f backend/pom.xml -Dtest=SrppReportProducerSchedulerTest,SrppReportReadServiceTest,SrppReportRepositoryPostgresTest,SrppReportModuleCalculatorTest,SrppReportShortUsCalculatorTest test
python3 scripts/check-srpp-report-authority.py --srpp-root /private/tmp/srpp-report-after-close-20261102 --check
python3 scripts/generate-srpp-report-golden.py --srpp-root /private/tmp/srpp-report-after-close-20261102 --check
python3 scripts/generate-srpp-long-short-exit-golden.py --srpp-root /private/tmp/srpp-report-after-close-20261102 --check
ruby scripts/tests/srpp-report-policy-register-test.rb
ruby scripts/tests/srpp-policy-register-test.rb
git diff --check
```

## 完成報告

- 實作只改既有scheduler與pure envelope validator；原GET、publisher、計算公式、model、targets不變。新增8個scheduler測試與4個真實PostgreSQL測試覆蓋當日缺scope、重啟／競態／3次上限、完整時鐘、stale/closed、正值／actionable回滾及純讀GET重播。
- 五類Java主測試59項，58項通過、唯一略過為原有opt-in大小探測；新增stale/closed單項亦通過，合計60項／59項實際通過。Ruby13tests／307assertions通過，authority gate與renderer／identity checks通過；spec機械檢核BLOCK0／CHECK0、獨立spec critical0major0、架構0findings。
- 受控renderer／sync只更新SRPP四個固定生成檔；新bundle `7a1f8ea0e59be76eee18f03d304ca017d24b4eadf6f037ee65af618ac19974c7`，Swagger `d8211f7c50da18c0eba69bb2dfdfcc4f4b2a6cc5ff6341ec28d6d63dd00116f3`。兩golden的cases／expected完全不變，僅bundle metadata更新。
- 測試收據 `/private/tmp/asset-afterclose-tests/`；Git、部署、registry與最終實際HTTP／重播／來源驗證收據集中 `/private/tmp/asset-afterclose-release/`。未產生的收據不得預先當作驗收完成；晚間診斷成功不等於正式金融採納。
